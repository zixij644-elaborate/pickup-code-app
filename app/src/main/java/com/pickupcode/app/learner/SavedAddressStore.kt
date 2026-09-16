package com.pickupcode.app.learner

import android.content.Context
import android.util.Log
import com.pickupcode.app.preferences.SecretCipher
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 用户**预存地址**（第三个数据源，与前两个自动学习的语义不同，勿混合）：
 *  - [CommonStationStore] 的 `stations`：自动学习的**站名**频次
 *  - [CommonStationStore] 的 `pickup_points`：自动学习的**完整地址**频次
 *  - **本对象**：用户手动录入的权威数据（识别时唯一参与的匹配源）
 *
 * ## 数据模型（按用户 2026-09-15 的想法收敛为**两个字段**）
 *
 *  - [SavedAddress.fullName]：**完整名称** —— 命中后写进记录、并在**主页卡片**上显示的那个值。
 *  - [SavedAddress.keywords]：**一个至多个关键词** —— 唯一的匹配依据。
 *    OCR 文本里命中任一关键词 → 该记录就用 [SavedAddress.fullName]。
 *    **关键词留空时用完整名称兜底匹配**（否则只填名称的用户永远命中不了）。
 *
 * 例：完整名称 = `长兴路北段菜鸟驿站`，关键词 = `北段驿站`、`长兴路`。
 * OCR 里出现"北段驿站" → 主页显示并入库 `长兴路北段菜鸟驿站`。
 *
 * ## 存储
 * SharedPreferences 里存**AES-GCM 密文**（地址是 PII：家/公司/学校）。不用 Room：
 * 50 条以内无需 SQL，且避免再动数据库迁移（迁移链是雷区）。
 * 读取走进程内缓存 → 识别热路径**不做解密**。
 *
 * ## 兼容
 * 旧版本 JSON 用的是 `name` / `address` / `aliases` 三个键，[parseList] 会自动映射
 * （fullName ← address(空则 name)，keywords ← name + aliases），**升级不丢已录入数据**。
 */
object SavedAddressStore {

    private const val TAG = "SavedAddressStore"
    private const val PREFS = "saved_addresses"
    private const val KEY_LIST = "list"
    private const val KEY_IGNORED = "ignored_names"

    /** 上限：个人取件点不会很多，超过就按创建时间裁剪最旧的。 */
    const val MAX_ENTRIES = 50

    data class SavedAddress(
        val id: String = UUID.randomUUID().toString(),
        /** 完整名称：命中后写进记录、在主页显示的值。 */
        val fullName: String,
        /** 一个至多个匹配关键词（留空则用 [fullName] 兜底匹配）。 */
        val keywords: List<String> = emptyList(),
        /** 临时停用（不删）。 */
        val enabled: Boolean = true,
        /** manual（用户录入）| promoted（从历史导入）。 */
        val origin: String = "manual",
        val createdAt: Long = System.currentTimeMillis()
    )

    /** 传给纯匹配函数的轻量视图（不含 id/origin 等无关字段）。 */
    data class MatcherView(val fullName: String, val keywords: List<String>)

    // ---- 进程内缓存：识别热路径只读这里，避免每次识别都做 Keystore 解密 ----
    @Volatile private var cache: List<SavedAddress>? = null
    @Volatile private var ignoredCache: Set<String>? = null

    @Synchronized
    fun getAll(context: Context): List<SavedAddress> {
        cache?.let { return it }
        val raw = prefs(context).getString(KEY_LIST, null)
            ?: return emptyList<SavedAddress>().also { cache = it }
        val list = try {
            val json = SecretCipher.decrypt(raw, "常用取件地址")
            if (json.isBlank()) emptyList() else parseList(json)
        } catch (e: Exception) {
            Log.w(TAG, "常用地址解析失败，按空处理（原数据保留）", e)
            emptyList()
        }
        cache = list
        return list
    }

    /** 供识别管线使用的纯数据（只含 enabled 项）。 */
    fun matcherViews(context: Context): List<MatcherView> =
        getAll(context).filter { it.enabled }.map { MatcherView(it.fullName, it.keywords) }

    @Synchronized
    fun upsert(context: Context, item: SavedAddress) {
        val fullName = item.fullName.trim()
        if (fullName.isBlank()) return                      // 完整名称必填
        val cleaned = item.copy(
            fullName = fullName,
            keywords = item.keywords.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        )
        val cur = getAll(context).toMutableList()
        val idx = cur.indexOfFirst { it.id == cleaned.id }
        if (idx >= 0) cur[idx] = cleaned else cur.add(0, cleaned)
        persist(context, cur.sortedByDescending { it.createdAt }.take(MAX_ENTRIES))
    }

    /**
     * 删除一条，并把它的完整名称/关键词记入忽略名单 —— 否则 [CommonStationStore.recordCode]
     * 下次又会把它当"常用站点"学回来（用户会觉得"删了没用"）。
     */
    @Synchronized
    fun delete(context: Context, id: String) {
        val cur = getAll(context)
        val target = cur.firstOrNull { it.id == id } ?: return
        persist(context, cur.filterNot { it.id == id })
        rememberIgnored(context, listOf(target.fullName) + target.keywords)
    }

    @Synchronized
    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        val cur = getAll(context)
        val idx = cur.indexOfFirst { it.id == id }
        if (idx < 0) return
        persist(context, cur.toMutableList().also { it[idx] = it[idx].copy(enabled = enabled) })
    }

    /**
     * 批量写入（「从历史记录导入」用）。
     * 导入来源只有站名/地址串本身，所以默认 **完整名称 = 该串、关键词 = 该串**（能命中，用户可再补关键词）。
     * 同名视为同一条：更新而不是产生重复。
     */
    @Synchronized
    fun upsertAll(context: Context, items: List<SavedAddress>) {
        if (items.isEmpty()) return
        val cur = getAll(context).toMutableList()
        for (item in items) {
            val fullName = item.fullName.trim()
            if (fullName.isBlank()) continue
            val idx = cur.indexOfFirst { it.fullName == fullName }
            if (idx >= 0) cur[idx] = cur[idx].copy(keywords = item.keywords) else cur.add(item.copy(fullName = fullName))
        }
        persist(context, cur.sortedByDescending { it.createdAt }.take(MAX_ENTRIES))
    }

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_LIST).apply()
        cache = emptyList()
    }

    // ---- 忽略名单 ----

    fun ignoredNames(context: Context): Set<String> {
        ignoredCache?.let { return it }
        val raw = prefs(context).getString(KEY_IGNORED, null)
        val set = try {
            if (raw.isNullOrBlank()) emptySet() else {
                val arr = JSONArray(raw)
                buildSet {
                    for (i in 0 until arr.length()) {
                        arr.optString(i, "").takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
            }
        } catch (_: Exception) { emptySet() }
        ignoredCache = set
        return set
    }

    fun isIgnored(context: Context, nameOrAlias: String): Boolean =
        nameOrAlias.isNotBlank() && ignoredNames(context).contains(nameOrAlias.trim())

    @Synchronized
    private fun rememberIgnored(context: Context, names: List<String>) {
        val merged = ignoredNames(context) + names.map { it.trim() }.filter { it.isNotBlank() }
        val arr = JSONArray(); merged.sorted().forEach { arr.put(it) }
        prefs(context).edit().putString(KEY_IGNORED, arr.toString()).apply()
        ignoredCache = merged
    }

    // ---- 内部 ----

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    private fun persist(context: Context, list: List<SavedAddress>) {
        val stored = try {
            SecretCipher.encrypt(toJson(list), "常用取件地址")
        } catch (e: Exception) {
            // 与 API Key 同一策略：加密不可用时**拒绝明文落盘**，只记日志不改动既有数据
            Log.e(TAG, "加密失败，放弃写入常用取件地址（拒绝明文落盘）", e)
            return
        }
        prefs(context).edit().putString(KEY_LIST, stored).apply()
        cache = list
    }

    private fun toJson(list: List<SavedAddress>): String {
        val arr = JSONArray()
        for (s in list) {
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("fullName", s.fullName)
                put("keywords", JSONArray().apply { s.keywords.forEach { put(it) } })
                put("enabled", s.enabled)
                put("origin", s.origin)
                put("createdAt", s.createdAt)
            })
        }
        return arr.toString()
    }

    /**
     * 解析存储的 JSON。**向后兼容旧 schema**：
     *  - 新键：`fullName` / `keywords`
     *  - 旧键：`address` / `name` / `aliases` → fullName 取 address（空则 name），keywords 取 name + aliases
     */
    private fun parseList(json: String): List<SavedAddress> {
        val arr = JSONArray(json)
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue

                fun strArray(key: String): List<String> {
                    val a = o.optJSONArray(key) ?: return emptyList()
                    return buildList {
                        for (j in 0 until a.length()) {
                            a.optString(j, "").takeIf { it.isNotBlank() }?.let { add(it) }
                        }
                    }
                }

                val legacyName = o.optString("name", "").trim()
                val legacyAddr = o.optString("address", "").trim()
                val fullName = o.optString("fullName", "").trim()
                    .ifBlank { legacyAddr.ifBlank { legacyName } }
                if (fullName.isBlank()) continue

                val keywords = (strArray("keywords") + strArray("aliases") + listOf(legacyName))
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it != fullName }
                    .distinct()

                add(
                    SavedAddress(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        fullName = fullName,
                        keywords = keywords,
                        enabled = o.optBoolean("enabled", true),
                        origin = o.optString("origin", "manual"),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
        }
    }
}
