package com.pickupcode.app.service

import android.content.Context
import android.util.Log
import com.pickupcode.app.BuildConfig
import com.pickupcode.app.data.CodeHistory
import com.pickupcode.app.data.CodeRepository
import com.pickupcode.app.extractor.AddressExtractor
import com.pickupcode.app.extractor.CodeExtractor
import com.pickupcode.app.extractor.ExpiryExtractor
import com.pickupcode.app.learner.CommonStationStore
import com.pickupcode.app.notification.CodeNotificationManager
import com.pickupcode.app.ocr.OCREngine
import com.pickupcode.app.preferences.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 识别后处理管线——三条路径（无障碍/分享/短信）共用的「逐码落库 + 通知」逻辑。
 * 每个码：取卡片窗口地址 → 取柜号 → 保存 → 常用站点学习 → 通知。
 *
 * 路径差异通过参数注入（screenshotPath / shareSource / timestamp / 通知回调），
 * 核心顺序三路径完全一致。
 */
object RecognitionPipeline {

    /** 单码处理结果：code + 保存 id + 是否重复。 */
    data class SavedCode(val code: String, val type: CodeExtractor.CodeType, val source: String,
                         val id: Long, val existed: Boolean, val address: String)

    /**
     * 逐码处理所有识别结果：取地址 → 取柜号 → 落库 → 常用站点学习。
     * 通知由调用方基于返回的 [SavedCode] 列表自行分发（三路径通知策略不同）。
     * @param allResults 识别出的 (code, type) 列表
     * @param codeSources code → 来源名（品牌/驿站）
     * @param lines OCR 行（逐码窗口地址用）
     * @param allText OCR 全文（柜号提取 + 站点学习用）
     * @param fullAddress 全屏兜底地址
     * @param rawSnippet 入库的原始文本片段
     * @param screenshotPath 截图路径（短信路径为空串）
     * @param shareSourcePkg/Name 分享来源（短信/无障碍为空串）
     * @param timestamp 入库时间戳
     * @param aiAddressHints AI（视觉通道）给出的 code→地址/站点，仅在本地管线**拿不到地址**时采用
     * @param aiCabinetHints AI 给出的 code→柜号，仅在本地取不到柜号时采用
     * @return 保存的码列表（供通知分发 / Kuaidi100 回填等后续使用）
     */
    suspend fun finalize(
        context: Context,
        allResults: List<Pair<String, CodeExtractor.CodeType>>,
        codeSources: Map<String, String>,
        lines: List<OCREngine.TextLine>,
        allText: String,
        fullAddress: String,
        rawSnippet: String,
        screenshotPath: String = "",
        shareSourcePkg: String = "",
        shareSourceName: String = "",
        timestamp: Long = System.currentTimeMillis(),
        aiAddressHints: Map<String, String> = emptyMap(),
        aiCabinetHints: Map<String, String> = emptyMap(),
        repo: CodeRepository
    ): List<SavedCode> {
        // 🔒 兜底：身份码/出库码页面一律不入库（截图拒采在无障碍路径已做，这里防其它入口漏网）
        if (com.pickupcode.app.util.SensitivePageGuard.isIdentityCodePage(allText)) {
            android.util.Log.w("RecognitionPipeline", "身份码/出库码页面，拒绝入库")
            return emptyList()
        }
        val saved = mutableListOf<SavedCode>()
        val seen = mutableSetOf<String>()
        // 同屏码数（多码时位置类证据要防串台）
        val multiCode = allResults.distinctBy { "${it.first}|${it.second}" }.size > 1
        // 全屏兜底仲裁只算一次（避免多码同屏每码重跑全量 extractLocation）：
        // 单码同屏全屏地址必然属于本卡，几何兜底照常采信；多码同屏防串台仅采信文本证据型来源
        val fallbackAddr = AddressExtractor.resolveAddress(
            lines, allText, perCodeAddr = "", fullAddress = fullAddress,
            multiCodeOnScreen = multiCode
        )
        // 预存地址（用户录的"完整名称 + 关键词"）命中一次即可复用。
        // 用户期望：**命中关键词就必须用我录的完整名称** —— 因此它是最高优先，
        // 压过逐码窗口地址与全屏兜底（2026-09-15 修：此前窗口地址赢，导致关键词命中"没作用"）。
        val savedMatch = com.pickupcode.app.extractor.SavedAddressMatcher.match(
            lines, com.pickupcode.app.learner.SavedAddressStore.matcherViews(context)
        )
        // 柜号与码无关，同屏只提取一次（仅在有取件码时使用）
        var cabinetCache: String? = null
        for ((code, type) in allResults) {
            val key = "$code|$type"
            if (key in seen) continue
            seen.add(key)
            val source = codeSources[code] ?: "unknown"

            // 地址优先级：预存命中的完整名称 > 逐码窗口地址 > 仲裁后的全屏兜底
            // 多码同屏时，预存地址只作用于"命中关键词那一行所属的码"，避免一条地址套到所有码上。
            val perCodeAddr = AddressExtractor.extractAddressForCode(lines, code)
            val savedAddr = if (savedMatch != null &&
                (!multiCode || AddressExtractor.isLineInCodeWindow(lines, code, savedMatch.lineIndex))
            ) {
                savedMatch.fullName
            } else {
                ""
            }
            val localAddr = perCodeAddr.ifBlank { fallbackAddr }
            // 地址优先级（2026-09-17 调整）：**预存完整名称 > AI 视觉读图结果 > 本地窗口 > 全屏兜底**。
            // 为什么把 AI 抬到本地启发式之上：用户要的就是"让 AI 读照片里的地址"。
            // 真机对比（菜鸟「运单号后五位」那张截图）：本地 S0-label 抽出的是一段 OCR 噪声，
            // 而视觉模型读出的驿站全称明显正确。预存地址仍排第一 —— 那是用户亲手录的权威值。
            val aiAddr = aiAddressHints[code].orEmpty()
            val effAddr = savedAddr.ifBlank { aiAddr.ifBlank { localAddr } }
            if (effAddr.isNotBlank()) {
                val from = when {
                    savedAddr.isNotBlank() -> "预存地址"
                    aiAddr.isNotBlank() -> "AI读图"
                    else -> "本地"
                }
                Log.d("RecognitionPipeline", "地址来源=$from：$code @ $effAddr")
            }
            // 独立柜号（仅取件码）；本地取不到时用 AI 的
            val cabinet = if (type == CodeExtractor.CodeType.pickup_parcel) {
                if (cabinetCache == null) cabinetCache = AddressExtractor.extractCabinetNumber(lines, allText)
                cabinetCache!!.ifBlank { aiCabinetHints[code].orEmpty() }
            } else ""

            // 到期时刻（v6）：快递码算提醒时刻；取餐/券码恒为 0 不提醒
            val expiryTime = ExpiryExtractor.expiryTimeFor(rawSnippet, type, timestamp) ?: 0L

            val save = repo.save(CodeHistory(
                code = code,
                type = type.name,
                source = source,
                rawTextSnippet = sanitizeSnippet(rawSnippet),
                pickupAddress = effAddr,
                cabinetNumber = cabinet,
                screenshotPath = screenshotPath,
                shareSourcePkg = shareSourcePkg,
                shareSourceName = shareSourceName,
                timestamp = timestamp,
                expiryTime = expiryTime
            ))
            // 覆盖更新的旧截图成孤儿文件（系统清理前不回收），立即删除。
            // 走 Dispatchers.IO：分享/短信路径的调用方协程可能跑在 Default 上，别让文件 IO 占用 CPU 池
            if (save.replacedScreenshotPath.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    try { java.io.File(save.replacedScreenshotPath).delete() } catch (_: Exception) {}
                }
            }
            saved.add(SavedCode(code, type, source, save.id, save.existed, effAddr))

            // 到期提醒排程：重复识别（existed）也重排——同码第二条短信可能带来新时限，
            // 若只在 !existed 时排程会漏掉更新后的提醒（FLAG_UPDATE_CURRENT 天然覆盖旧闹钟）
            if (expiryTime > 0 && AppPreferences.isExpiryRemindEnabled(context)) {
                CodeNotificationManager.scheduleExpiryReminder(context, code, type, source, expiryTime)
            }

            // 常用站点学习：带地址的取件记录累计站点频次
            if (type == CodeExtractor.CodeType.pickup_parcel && effAddr.isNotBlank()) {
                CommonStationStore.recordCode(context, effAddr, rawSnippet)
            }
        }
        return saved
    }

    /** 通知分发：同码同 type 已存在 → 重复提示；否则正常通知。 */
    suspend fun notifySaved(context: Context, dupCountProvider: suspend () -> Int,
                            code: String, type: CodeExtractor.CodeType, source: String, id: Long, existed: Boolean) {
        if (existed) {
            val dupCount = dupCountProvider()
            CodeNotificationManager.showDuplicate(context, code, type, source, id, dupCount)
        } else {
            CodeNotificationManager.show(context, code, type, source, id)
        }
    }

    // 手机号（1开头 11 位）
    private val MOBILE_REGEX = Regex("1[3-9]\\d{9}")

    /** 脱敏：掩码手机号 + 截断，避免全屏/短信原文带 PII 入库（H-A）。 */
    private fun sanitizeSnippet(text: String?, maxLen: Int = 200): String {
        if (text.isNullOrBlank()) return ""
        val masked = MOBILE_REGEX.replace(text) { m ->
            val d = m.value
            d.take(3) + "****" + d.takeLast(4)
        }
        return masked.take(maxLen)
    }

    /** 识别日志（三路径统一格式）。 */
    fun logSaved(tag: String, code: String, type: CodeExtractor.CodeType, source: String, address: String, existed: Boolean) {
        // H4: release 不落 PII（码/地址/来源）
        if (BuildConfig.DEBUG) {
            Log.d(tag, "识别入库: $code (${type.name}) from $source @ $address${if (existed) " [DUPLICATE]" else ""}")
        }
    }

    /** 该类型是否被用户开启（三路径共用；替换 Sms/Share/Accessibility 三份 switch 副本）。 */
    fun isTypeEnabled(type: CodeExtractor.CodeType, settings: AppPreferences.Settings): Boolean = when (type) {
        CodeExtractor.CodeType.pickup_food -> settings.enableFoodCodes
        CodeExtractor.CodeType.pickup_parcel -> settings.enableParcelCodes
        CodeExtractor.CodeType.coupon -> settings.enableCouponCodes
    }

}
