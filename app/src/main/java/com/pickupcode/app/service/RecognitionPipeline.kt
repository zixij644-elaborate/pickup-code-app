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
import kotlinx.coroutines.launch
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
        aiUsedImage: Boolean = false,
        repo: CodeRepository
    ): List<SavedCode> {
        // 🔒 兜底：身份码/出库码页面一律不入库（截图拒采在无障碍路径已做，这里防其它入口漏网）
        if (com.pickupcode.app.util.SensitivePageGuard.isIdentityCodePage(allText)) {
            android.util.Log.w("RecognitionPipeline", "身份码/出库码页面，拒绝入库")
            return emptyList()
        }
        val orderedLines = OCREngine.sortReadingOrder(lines)
        val saved = mutableListOf<SavedCode>()
        val seen = mutableSetOf<String>()
        val allCodes = allResults.map { it.first }
        // 同屏码数（多码时禁止跨卡全屏兜底）
        val multiCode = allResults.distinctBy { "${it.first.uppercase()}|${it.second}" }.size > 1
        // 仅单码同屏时允许全屏兜底；多码同屏宁缺毋滥，绝不把卡 A 地址广播给卡 B
        val fallbackAddr = if (multiCode) {
            ""
        } else {
            AddressExtractor.resolveAddress(
                orderedLines, allText, perCodeAddr = "", fullAddress = fullAddress,
                multiCodeOnScreen = false
            )
        }
        // 预存地址视图只读一次，按每个码自己的卡片行分别匹配（避免全屏第一条覆盖其他卡）
        val savedViews = com.pickupcode.app.learner.SavedAddressStore.matcherViews(context)
        val aiAddrUpper = aiAddressHints.entries.associate { it.key.uppercase() to it.value }
        val aiCabUpper = aiCabinetHints.entries.associate { it.key.uppercase() to it.value }

        for ((code, type) in allResults) {
            val key = "${code.uppercase()}|$type"
            if (key in seen) continue
            seen.add(key)
            val source = codeSources[code] ?: "unknown"
            val codeUpper = code.uppercase()

            val cardLines = AddressExtractor.cardLinesForCode(orderedLines, code, allCodes)
            val perCodeAddr = AddressExtractor.extractAddressForCode(orderedLines, code, allCodes)
            val savedTargetLines = if (cardLines.isNotEmpty()) cardLines else if (multiCode) emptyList() else orderedLines
            val savedAddr = com.pickupcode.app.extractor.SavedAddressMatcher.match(savedTargetLines, savedViews)
                ?.fullName.orEmpty()
            val localAddr = perCodeAddr.ifBlank { fallbackAddr }
            val aiAddr = aiAddrUpper[codeUpper].orEmpty()
            // 地址优先级：
            // - 预存完整名称永远最高；
            // - AI 视觉读图（aiUsedImage=true）优先于本地启发式；
            // - 多码同屏的纯文本 AI（aiUsedImage=false）仅作本地取不到时的填空，避免乱序文本反覆盖本地分卡结果。
            val effAddr = when {
                savedAddr.isNotBlank() -> savedAddr
                aiAddr.isNotBlank() && (aiUsedImage || !multiCode || localAddr.isBlank()) -> aiAddr
                else -> localAddr
            }
            if (effAddr.isNotBlank()) {
                val from = when {
                    savedAddr.isNotBlank() -> "预存地址"
                    effAddr == aiAddr && aiAddr.isNotBlank() -> if (aiUsedImage) "AI读图" else "AI文本"
                    else -> "本地"
                }
                Log.d("RecognitionPipeline", "地址来源=$from：$code @ $effAddr")
            }
            // 独立柜号（仅取件码）：在本卡行内独立提取，不再全屏共用缓存
            val cabinet = if (type == CodeExtractor.CodeType.pickup_parcel) {
                val cabLines = if (cardLines.isNotEmpty()) cardLines else if (multiCode) emptyList() else orderedLines
                val cabText = cabLines.joinToString(" ") { it.text }
                AddressExtractor.extractCabinetNumber(cabLines, cabText)
                    .ifBlank { aiCabUpper[codeUpper].orEmpty() }
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
            // 覆盖更新后仅回收不再被其他码引用的旧截图。
            // 走 Dispatchers.IO：分享/短信路径的调用方协程可能跑在 Default 上，别让文件 IO 占用 CPU 池
            if (save.replacedScreenshotPath.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    repo.deleteScreenshotIfUnreferenced(save.replacedScreenshotPath)
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

    /**
     * 合并 AI 提取结果（三路径共用）：记录 code→地址/柜号提示（key 统一大写），并按类型开关与大小写去重后追加到 [allResults]。
     */
    fun mergeAiResults(
        aiResults: List<com.pickupcode.app.extractor.AIExtractor.AIResult>,
        settings: AppPreferences.Settings,
        allResults: MutableList<Pair<String, CodeExtractor.CodeType>>,
        codeSources: MutableMap<String, String>,
        aiAddressHints: MutableMap<String, String>,
        aiCabinetHints: MutableMap<String, String>
    ) {
        for (ai in aiResults) {
            val codeKey = ai.code.uppercase()
            ai.address.ifBlank { ai.station }.takeIf { it.isNotBlank() }?.let { aiAddressHints[codeKey] = it }
            ai.cabinet.takeIf { it.isNotBlank() }?.let { aiCabinetHints[codeKey] = it }
            if (!isTypeEnabled(ai.type, settings)) continue
            if (allResults.any { it.first.equals(ai.code, ignoreCase = true) && it.second == ai.type }) continue
            allResults.add(ai.code to ai.type)
            codeSources.putIfAbsent(ai.code, ai.source)
        }
    }

    /**
     * 识别后置异步验证（无障碍 & 分享共用）：地图地理编码校验 + 快递100 多运单号反查定向补全地址。
     */
    fun runPostVerifiers(
        context: Context,
        scope: kotlinx.coroutines.CoroutineScope,
        settings: AppPreferences.Settings,
        saved: List<SavedCode>,
        allText: String,
        allResults: List<Pair<String, CodeExtractor.CodeType>>,
        repo: CodeRepository,
        onGeoVerified: ((address: String, confidence: Float) -> Unit)? = null
    ) {
        if (settings.enableMapVerify) {
            val amapKey = settings.amapApiKey.ifBlank { null }
            for (record in saved.filter { it.address.isNotBlank() }) {
                scope.launch(Dispatchers.IO) {
                    try {
                        PostVerifier.verifyMap(context, record.address, amapKey) { conf, fmtAddr ->
                            onGeoVerified?.invoke(record.address, conf)
                            repo.updateGeo(record.id, true, conf, fmtAddr ?: "")
                        }
                    } catch (e: Exception) {
                        Log.e("RecognitionPipeline", "Geo verify error: ${e.message}")
                    }
                }
            }
        }
        if (settings.enableKuaidi100 && settings.kuaidi100Key.isNotBlank()) {
            val trackingNums = com.pickupcode.app.extractor.BrandResolver.findAllOrderNumbers(allText)
            if (trackingNums.isNotEmpty()) {
                val savedIdsByCode = saved.associate { it.code to it.id }
                val ocrCodes = allResults.map { it.first }
                scope.launch(Dispatchers.IO) {
                    for (trackingNum in trackingNums) {
                        try {
                            val res = PostVerifier.verifyKuaidi100(context, settings.kuaidi100Key, trackingNum, ocrCodes)
                                ?: continue
                            val pCode = res.pickUpCode ?: continue
                            val pAddr = res.pickUpAddress?.takeIf { it.isNotBlank() } ?: continue
                            val targetId = savedIdsByCode[pCode]
                            if (targetId != null) {
                                repo.getByIdSuspend(targetId)?.let { rec ->
                                    if (rec.pickupAddress.isBlank()) repo.updatePickupAddress(targetId, pAddr)
                                }
                            } else {
                                val rec = repo.findByCodeAndType(pCode, CodeExtractor.CodeType.pickup_parcel.name)
                                if (rec != null && rec.pickupAddress.isBlank()) repo.updatePickupAddress(rec.id, pAddr)
                            }
                        } catch (e: Exception) {
                            Log.e("RecognitionPipeline", "Kuaidi100 verify error: ${e.message}")
                        }
                    }
                }
            }
        }
    }
}
