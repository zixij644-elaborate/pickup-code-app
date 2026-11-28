package com.pickupcode.app.learner

import android.content.Context
import android.util.Log
import com.pickupcode.app.preferences.SecretCipher
import org.json.JSONArray
import org.json.JSONObject

/**
 * 常用站点缓存（参考同类产品的常用站点缓存机制 setCommonStations）。
 *
 * 用途：用户经常取件的驿站/快递柜/取件点，地址识别时优先匹配，提升多驿站/噪音场景的取件地址精度。
 * 与 [PatternLearner] 同风格：SharedPreferences 轻量 JSON 存储，无第三方依赖。
 *
 * 写入时机：每次成功保存取件记录时调用 [recordCode]（带地址），自动累计站点出现次数。
 * 读取时机（2026-09-15 起**不再**参与地址识别）：仅供「常用取件地址 → 从历史导入」列出候选，
 * 由用户在 UI 里确认后才进入识别链路。[getCommonStations] 保留给该用途。
 * 存储上限：[MAX_ENTRIES] 条，按次数排序，超出裁剪。
 */
object CommonStationStore {

    private const val PREFS = "common_stations"
    private const val KEY_STATIONS = "stations"

    fun migrateLegacyAddresses(context: Context) {
        readJson(context, PREFS, KEY_STATIONS)
        readJson(context, PICKUP_PREFS, KEY_PICKUP_POINTS)
    }

    private fun readJson(context: Context, prefsName: String, key: String): String? {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val stored = prefs.getString(key, null) ?: return null
        val json = SecretCipher.decrypt(stored, "常用取件点")
        if (json.isBlank()) return null
        if (!SecretCipher.isEncrypted(stored)) writeJson(context, prefsName, key, json)
        return json
    }

    private fun hasUnreadableEncrypted(context: Context, prefsName: String, key: String): Boolean {
        val stored = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString(key, null) ?: return false
        return SecretCipher.isEncrypted(stored) && SecretCipher.decrypt(stored, "常用取件点").isBlank()
    }

    private fun writeJson(context: Context, prefsName: String, key: String, json: String) {
        try {
            val encrypted = SecretCipher.encrypt(json, "常用取件点")
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit().putString(key, encrypted).apply()
        } catch (e: Exception) {
            Log.e("CommonStationStore", "常用取件点加密失败，放弃写入", e)
        }
    }

    /** 存储上限：只保留站点使用频次 Top-N 的文案，防止无限膨胀。 */
    private const val MAX_ENTRIES = 60

    data class StationEntry(val name: String, val count: Int)

    /** 从取件地址/站名/原文里抠候选站点名：优先取「xx驿站/xx快递柜/xx店/xx代收点/xx柜」这类站名片段。 */
    private fun extractStationName(text: String): String? {
        if (text.isBlank()) return null
        // 常见站点后缀 → 剥出前面的站名（如 长兴路北段菜鸟驿站 → 长兴路北段菜鸟驿站）
        val suffixes = listOf("菜鸟驿站", "妈妈驿站", "兔喜生活", "快递柜", "丰巢", "代收点", "自提点", "服务站", "驿站")
        for (sfx in suffixes) {
            val idx = text.indexOf(sfx)
            if (idx >= 0) {
                // 从该词往前取最多 12 个字符作为站名（避免把整串地址都当站名）
                val start = (idx - 12).coerceAtLeast(0)
                var s = text.substring(start, idx + sfx.length).trim()
                // 截到标点/括号边界
                val cut = s.indexOfFirst { it in "，,。；;：:（）()【】|｜ " }
                if (cut > 0) s = s.substring(0, cut).trim()
                if (s.length in 2..20) return s
            }
        }
        // 兜底：整串取件地址里太不确定，不硬造站名，返回 null
        return null
    }

    /** 记录一次识别：把取件地址/原文中的站点名累计出现次数。地址为空时跳过（不学噪声）。 */
    // Medium-1: read-modify-write SharedPreferences 加 @Synchronized，防多入口（分享/无障碍/短信）并发丢计数
    @Synchronized
    fun recordCode(context: Context, address: String, rawText: String = "") {
        val name = extractStationName(address.ifBlank { rawText }) ?: return
        // 用户手动删掉过的站点/地址不要再学回来（否则"删了还在"让人以为删除没生效）。
        if (SavedAddressStore.isIgnored(context, name)) return
        if (hasUnreadableEncrypted(context, PREFS, KEY_STATIONS)) return
        val cur = loadInternal(context)
        val next = LinkedHashMap<String, Int>()
        // 保留出现次数 >1 的既有条目 + 本次 +1
        for ((k, v) in cur) {
            if (k == name) continue
            if (v > 0) next[k] = v
        }
        val newCount = (cur[name] ?: 0) + 1
        next[name] = newCount
        // 裁剪：保留次数 Top-N，但保底保留本次刚写入的 name（避免低频站点反复丢失）
        val sorted = next.entries.sortedByDescending { it.value }.take(MAX_ENTRIES)
        val finalEntries = if (sorted.any { it.key == name }) sorted
        else sorted.dropLast(1) + mapOf(name to newCount).entries.first()
        val arr = JSONArray()
        for ((k, v) in finalEntries) {
            arr.put(JSONArray().put(k).put(v))
        }
        writeJson(context, PREFS, KEY_STATIONS, arr.toString())
    }

    /** 读取常用站点列表（按使用次数降序），供地址识别优先匹配。 */
    fun getCommonStations(context: Context): List<StationEntry> {
        val raw = readJson(context, PREFS, KEY_STATIONS) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONArray(i)
                    val name = e.optString(0, "")
                    val count = e.optInt(1, 0)
                    if (name.isNotBlank() && count > 0) add(StationEntry(name, count))
                }
            }.sortedByDescending { it.count }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 清空（调试/设置用）。 */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** 内部读取原始 map，避免每次解析。 */
    private fun loadInternal(context: Context): Map<String, Int> {
        val raw = readJson(context, PREFS, KEY_STATIONS) ?: return emptyMap()
        return try {
            val arr = JSONArray(raw)
            buildMap {
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONArray(i)
                    put(e.optString(0, ""), e.optInt(1, 0))
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // ---------------------------------------------------------------
    // C2: 常用取件点归并（自 PatternLearner 迁入，2026-08-13）
    // 存储沿用 PatternLearner 的 PREFS 文件 "pattern_learner"，保证迁移数据不丢。
    // 与上方的站点缓存（recordCode/getCommonStations，按站名聚合）并存：
    // 本段按「完整地址」聚合（用户级常用地址），上方按「站名」聚合（识别级优先匹配）——语义不同，勿合并。
    // ---------------------------------------------------------------

    private const val PICKUP_PREFS = "pattern_learner"
    private const val KEY_PICKUP_POINTS = "pickup_points"
    private const val MAX_PICKUP_POINTS = 50

    data class PickupPoint(val name: String, val count: Int, val lastUsedAt: Long)

    @Volatile
    private var cachedPickupPoints: Map<String, PickupPoint>? = null

    /** 在 IO 线程预热常用取件点内存缓存，避免详情页首帧异步插入 AssistChip 导致布局下推。 */
    fun warmPickupPointsCache(context: Context) {
        if (cachedPickupPoints == null) {
            loadPickupPointsMap(context)
        }
    }

    fun isPickupPointsCacheReady(): Boolean = cachedPickupPoints != null

    /** 纯内存查询常用取件点（零 IO / 零解密）；调用前可用 [isPickupPointsCacheReady] 判断是否已预热。 */
    fun peekFrequentPickupPoint(address: String): PickupPoint? {
        if (address.isBlank()) return null
        val point = cachedPickupPoints?.get(address.trim()) ?: return null
        return if (point.count >= 2) point else null
    }

    private fun loadPickupPointsMap(context: Context): Map<String, PickupPoint> {
        if (hasUnreadableEncrypted(context, PICKUP_PREFS, KEY_PICKUP_POINTS)) return emptyMap()
        val json = readJson(context, PICKUP_PREFS, KEY_PICKUP_POINTS)
        val result = if (json == null) {
            emptyMap()
        } else {
            try {
                val arr = JSONArray(json)
                buildMap {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val addr = o.optString("address", "")
                        if (addr.isNotBlank()) {
                            put(addr, PickupPoint(o.optString("name"), o.optInt("count"), o.optLong("last", 0L)))
                        }
                    }
                }
            } catch (_: Exception) {
                emptyMap()
            }
        }
        cachedPickupPoints = result
        return result
    }

    /** 记录一次取件地址出现（识别/标记已取时调用），用于归并"常用取件点"。 */
    // B13: read-modify-write 需原子化——识别线程与详情页「标记已取」可并发调用，无锁会丢失计数
    @Synchronized
    fun registerPickupPoint(context: Context, address: String) {
        if (address.isBlank()) return
        if (hasUnreadableEncrypted(context, PICKUP_PREFS, KEY_PICKUP_POINTS)) return
        val key = address.trim()
        val json = readJson(context, PICKUP_PREFS, KEY_PICKUP_POINTS)
        val map = linkedMapOf<String, PickupPoint>()
        if (json != null) {
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    map[o.optString("address")] = PickupPoint(o.optString("name"), o.optInt("count"), o.optLong("last", 0L))
                }
            } catch (e: Exception) { Log.w("CommonStationStore", "常用取件点JSON损坏，已重置", e) }
        }
        val prev = map[key]
        map[key] = PickupPoint(key, (prev?.count ?: 0) + 1, System.currentTimeMillis())
        val sorted = map.entries.sortedByDescending { it.value.count }.take(MAX_PICKUP_POINTS)
        val out = JSONArray()
        val trimmedMap = linkedMapOf<String, PickupPoint>()
        for ((addr, p) in sorted) {
            trimmedMap[addr] = p
            out.put(JSONObject().apply { put("address", addr); put("name", p.name); put("count", p.count); put("last", p.lastUsedAt) })
        }
        writeJson(context, PICKUP_PREFS, KEY_PICKUP_POINTS, out.toString())
        cachedPickupPoints = trimmedMap
    }

    /** 该地址是否已是常用取件点（出现 >= 2 次）。返回 null 表示不是。 */
    fun isFrequentPickupPoint(context: Context, address: String): PickupPoint? {
        if (address.isBlank()) return null
        val map = cachedPickupPoints ?: loadPickupPointsMap(context)
        val point = map[address.trim()] ?: return null
        return if (point.count >= 2) point else null
    }

    /**
     * 全部常用取件点（按次数降序，含只出现 1 次的）。
     * 供「常用取件地址」页的**从历史记录导入**使用——自动学到的完整地址是导入的最佳候选。
     */
    fun getPickupPoints(context: Context): List<PickupPoint> {
        val json = readJson(context, PICKUP_PREFS, KEY_PICKUP_POINTS) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val addr = o.optString("address", "")
                    if (addr.isNotBlank()) {
                        add(PickupPoint(addr, o.optInt("count"), o.optLong("last", 0L)))
                    }
                }
            }.sortedByDescending { it.count }
        } catch (_: Exception) { emptyList() }
    }
}
