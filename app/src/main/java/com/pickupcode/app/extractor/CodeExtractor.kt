package com.pickupcode.app.extractor

import android.graphics.Rect
import android.content.Context
import android.util.Log
import com.pickupcode.app.learner.PatternLearner
import com.pickupcode.app.ocr.OCREngine
// R1 拆分：品牌/地址/校验知识已移入 BrandResolver / AddressExtractor / CodeValidator，
// 此处用 member import 保持 extract() 函数体零改动
import com.pickupcode.app.extractor.BrandResolver.sourceFromLine
import com.pickupcode.app.extractor.BrandResolver.FOOD_BRAND_KEYWORDS
import com.pickupcode.app.extractor.CodeValidator.isExcluded
import com.pickupcode.app.extractor.CodeValidator.classifyFormat
import com.pickupcode.app.extractor.CodeValidator.THREE_SEGMENT_PARCEL
import com.pickupcode.app.extractor.CodeValidator.FOUR_SEGMENT_PARCEL
import com.pickupcode.app.extractor.CodeValidator.LETTER_TWO_SEGMENT_PARCEL
import com.pickupcode.app.extractor.CodeValidator.LETTER_DASH_FIVE_PARCEL
import com.pickupcode.app.extractor.CodeValidator.LONG_NUMBER_PARCEL
import com.pickupcode.app.extractor.CodeValidator.DIGIT_DASH_PARCEL

object CodeExtractor {

    data class ExtractedCode(val code: String, val type: CodeType, val source: String, val confidence: Float)
    enum class CodeType { pickup_food, pickup_parcel, coupon }

    // 边界统一用 (?<![\dA-Za-z])/(?![\dA-Za-z]) 而非 \b：Android(ICU) 的 \b 把中文当词字符，
    // 码值紧贴中文（如 "749019复制"）时 \b 失效漏抓；桌面 JVM 测不出来（ASCII \b），真机必现。    // A8-3-3315: letter prefix + 3 dash-separated segments, e.g. locker codes (A/B/C prefix)
    private val LETTER_THREE_SEG_PARCEL = Regex("(?<![\\dA-Za-z])([A-Za-z]\\d{1,2})-(\\d{1,2})-(\\d{3,6})(?![\\dA-Za-z])", RegexOption.IGNORE_CASE)
    private val LETTER_DASH_THREE_PARCEL = Regex("(?<![\\dA-Za-z])([A-Za-z])-(\\d{3,4})(?![\\dA-Za-z])", RegexOption.IGNORE_CASE)
    private val LETTER_NUMBER_FOOD = Regex("(?<![\\dA-Za-z])([A-Z]\\s*[-]?\\s*\\d{2,4})(?![\\dA-Za-z])", RegexOption.IGNORE_CASE)
    private val PURE_NUMBER_FOOD = Regex("(?<![\\dA-Za-z-])(\\d{2,5})(?![\\dA-Za-z])")
    private val PREFIXED_CODE = Regex("(取[餐件货单]码|取餐号|取单号|排号|券号|提取码)[:：]?\\s*(?:为|是)?\\s*([A-Za-z0-9\\-]{2,12})")
    // 菜鸟/驿站类通知标准句式：凭1-6-5020到...取（件）；容忍 OCR 在码值与方位词间插入空格
    private val PING_CODE = Regex("(?:凭|好评码|提取码|券号)[:：]?\\s*([A-Za-z0-9\\-]{2,12}?)\\s*(?=(?:到|至|去|领|取|在|格|号柜|菜鸟|驿站|快递柜))", RegexOption.IGNORE_CASE)

    // 跨行前缀：上一行是取件码/凭条等词 + 下一行开头是码（后接地址/通知等）；去掉行尾$锚点，
    // 否则"231607 到育新路..."这类码后跟真实地址的会被漏抓（需保留开头强锚定 + 后不能紧邻数字/破折号）
    private val NEXT_LINE_CODE = Regex("^\\s*([A-Za-z0-9\\-]{2,12})\\s*(?![-\\d])")
    private val CODE_KEYWORD_NEAR = Regex("(取[件餐货]码|取餐号|驿站|快递柜|自提柜|取件点)")
    private val ORDER_LONG_SQL = Regex("(?<![\\dA-Za-z])\\d{6,}-\\d{5,}(?![\\dA-Za-z])")
    private val ORDER_SHORT_SQL = Regex("(?<![\\dA-Za-z])\\d{2,4}-\\d{3,4}-\\d{4,}(?![\\dA-Za-z])")
    // 热循环正则预编译：避免每行/每次调用重复编译 Regex（原在 normalizeText 与逐行前缀匹配内 new）
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val JOINED_PREFIX_CODE = Regex("^[餐件货单]码[A-Za-z0-9].*")
    // OCR 把段间分隔符"-"读成中文"一"（如 取件码:3一1-1099）——仅当"一"夹在数字之间时还原为"-"
    private val DASH_LIKE_OCR_REGEX = Regex("(?<=\\d)一(?=\\d)")
    // 上下文邻接噪声（真机日志对照分析新增）：码值紧邻的字符暗示这不是取件码
    private val AREA_CODE_BEFORE = Regex("\\d{3,4}-$")
    private val NEXT_LINE_UI_NOISE = Regex("订单|详情|查看物流|查看更多|评价|确认收货|申请售后|待收货|待发货|待付款")

    private const val SCORE_PREFIXED = 100f; private const val SCORE_THREE_SEG = 95f
    private const val SCORE_FOUR_SEG = 95f
    private const val SCORE_LETTER_TWO_SEG = 85f
    private const val SCORE_LETTER_DASH_FIVE = 85f
    private const val SCORE_LETTER_DASH_THREE = 80f
    private const val SCORE_LETTER_NUM_FOOD = 80f
    private const val SCORE_PURE_NUM_FOOD = 75f; private const val SCORE_LONG_NUM_PARCEL = 60f
    private const val LARGE_FONT_HEIGHT_PX = 60; private const val FONT_SIZE_RATIO_THRESHOLD = 1.5f

    // PING_CODE（凭条号）评分：略低于前缀码，命中驿站/快递柜或三段式码再加分
    private const val PING_BASE_PENALTY = 2f
    private const val PING_PARCEL_BONUS = 8f
    private const val PING_MULTISEG_BONUS = 10f

    // 上下文/位置/尺寸加分与类型惩罚（按语义分开命名；同值不同义的 5f/10f/50f 不合并）
    private const val SCORE_CTX_BONUS = 10f            // 上下文加分（Rule.ctxBonus 实参 + 关键词/大字体行）
    private const val SCORE_PURE_NUM_5DIGIT_BONUS = 15f // 纯数字 5 位（外卖取餐码典型长度）
    private const val SCORE_NEAR_KEYWORD_BONUS = 15f   // 候选与关键词行相邻（±2 行）加分
    private const val SCORE_CONFLICT_TYPE_PENALTY = 8f // 命中规则但屏幕上下文相反时的小额扣分
    private const val SCORE_FOOD_NO_SIGNAL_PENALTY = 35f // 纯数字但无任何外卖信号时扣分
    private const val SCORE_CROSS_TYPE_PENALTY = 50f   // 整批候选与屏幕上下文类型不一致时的大额惩罚
    private const val SCORE_ORDER_LONG_NUM_PENALTY = 50f // 订单号长数字取件码形态扣分
    private const val SCORE_ORDER_DIGIT_PENALTY = 30f  // 订单纯数字形态扣分
    private const val SCORE_MULTISEG_LONG_NUM_PENALTY = 40f // 多段拼接长数字扣分
    private const val SCORE_LEARNED_BASE = 65f         // 已学规则基础分（低于内置规则）
    private const val SCORE_LEARNED_DECAYED_BASE = 20f // 已学规则衰减后基础分（不抢先，自愈）
    private const val STRONG_PASS_RATIO = 0.75f        // 强规则候选通过线（>= top 的 75%）
    private val POS_BONUS_Y_RANGE = 0.1f..0.6f   // 屏幕高度中段（候选码大概率所在区域）
    private const val POS_BONUS_VALUE = 5f             // 中段位置加分
    private const val SIZE_BIG_FONT_BONUS = 10f        // 大字体行加分
    private const val SIZE_RATIO_BONUS = 8f            // 明显大于均值的行加分
    private const val PURE_NUM_BIG_FONT_FOOD_BONUS = 5f // 纯数字+大字体（无关键词）时的少量加分
    private const val RECORD_MISS_SNIPPET_LEN = 500    // 未识别时反馈给 PatternLearner 的文本截断长度

    private val FOOD_KEYWORDS = FOOD_BRAND_KEYWORDS + listOf(
        "取餐", "取餐码", "取餐号", "取单码", "取单号", "请取餐", "正在制作", "等待取餐"
    )
    private val PARCEL_KEYWORDS = listOf(
        "菜鸟", "驿站", "丰巢", "妈妈驿站", "兔喜", "免喜", "快递超市",
        "京东快递", "顺丰", "中通", "圆通", "申通", "韵达", "极兔", "邮政",
        "取件码", "取货码", "提取码", "快递柜", "货架", "韵达超市", "欢猫智柜"
    )

    // ---------------------------------------------------------------
    // 文本预处理：全角→半角归一化（参考同类产品实现 normalizeText）
    // OCR 有时会把数字/符号读成全角（如 ０１２３、：、，），导致正则匹配失败。
    // ---------------------------------------------------------------

    /** 全角数字 → 半角映射 */
    private val FULLWIDTH_DIGITS = mapOf(
        '０' to '0', '１' to '1', '２' to '2', '３' to '3', '４' to '4',
        '５' to '5', '６' to '6', '７' to '7', '８' to '8', '９' to '9'
    )

    /**
     * 归一化 OCR 文本：全角转半角 + 压缩空白（参考同类产品的文本归一化实现 normalizeText）。
     * 逐字符遍历，把全角数字/符号/空格转为半角等价物，然后把制表符/换行转空格，
     * 最后压缩连续空白为单个空格、去首尾空白。
     */
    fun normalizeText(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                '\t', '\n', '\r' -> sb.append(' ')
                '_', '~' -> sb.append('-')
                '（' -> sb.append('(')       // 全角左括号 （
                '）' -> sb.append(')')        // 全角右括号 ）
                '，' -> sb.append(',')         // 全角逗号 ，
                '；' -> sb.append(';')         // 全角分号 ；
                '：' -> sb.append(':')         // 全角冒号 ：
                '～', '–', '—' -> sb.append('-')  // ～ – —
                '　' -> sb.append(' ')          // 全角空格
                '【' -> sb.append('[')          // 【
                '】' -> sb.append(']')          // 】
                '－' -> sb.append('-')          // 全角减号 －
                '、' -> sb.append(',')          // 、
                '。' -> sb.append('.')          // 。
                '．' -> sb.append('.')          // 全角句号 ．
                else -> {
                    val d = FULLWIDTH_DIGITS[ch]
                    if (d != null) sb.append(d) else sb.append(ch)
                }
            }
        }
        // OCR 把段间分隔符"-"读成中文"一"（如 取件码:3一1-1099）：仅当"一"夹在数字之间时还原为"-"
        val built = DASH_LIKE_OCR_REGEX.replace(sb.toString(), "-")
        // 压缩连续空白为单个空格，去首尾
        return built.replace(WHITESPACE_REGEX, " ").trim()
    }

    // ---------------------------------------------------------------
    // 金融/支付短信相关性拦截（参考同类产品实现 isExpressRelatedSms）
    // 银行、支付类通知的截图/短信里常出现数字（金额、验证码、余额），极易被当成取件码。
    // 规则：命中金融词且未命中快递词 → 判定为金融噪音，整段不识别。
    // ---------------------------------------------------------------

    /** 金融/支付强信号词：出现即高度怀疑是非取件的资金类通知。 */
    private val FINANCIAL_KEYWORDS = listOf(
        "银行", "信用卡", "借记卡", "储蓄卡", "账户", "出账", "入账", "到账", "余额",
        "支付宝", "微信支付", "财付通", "账单", "消费", "转账", "退款", "还款",
        "支付", "人民币", "收付款", "手续费", "交易", "红包到账", "零钱", "花呗", "借呗"
    )

    /** 快递/取件强信号词：与金融词对冲，命中则说明可能是含支付信息的取件通知。 */
    private val EXPRESS_SIGNAL_KEYWORDS = listOf(
        "取件", "快递", "包裹", "驿站", "代收点", "货栈", "柜", "提货", "开箱", "运单", "取餐", "取餐码"
    )

    /**
     * 判断一段文本是否为金融/支付类噪音（非取件场景）。
     * 命中金融词且没有快递/取件信号词 → true（应拦截）。
     * 同时命中两者 → false（可能是取件通知里带支付提醒，放行）。
     */
    fun isFinancialNoise(text: String): Boolean {
        if (text.isBlank()) return false
        val hasFinancial = FINANCIAL_KEYWORDS.any { text.contains(it, ignoreCase = true) }
        if (!hasFinancial) return false
        val hasExpressSignal = EXPRESS_SIGNAL_KEYWORDS.any { text.contains(it, ignoreCase = true) }
        return !hasExpressSignal
    }

    // ---------------------------------------------------------------
    // Code extraction
    // ---------------------------------------------------------------

    fun extract(lines: List<OCREngine.TextLine>, screenHeight: Int = 0, context: Context? = null, source: String = "screen"): List<ExtractedCode> {
        // 文本预处理：全角→半角归一化 + 词级纠错表（参考同类产品实现 normalizeText / textCorrections）
        val lines = lines.map { it.copy(text = OcrCorrections.apply(normalizeText(it.text))) }
        val candidates = mutableListOf<Candidate>()
        val allText = lines.joinToString(" ") { it.text }
        val isFoodContext = FOOD_KEYWORDS.any { allText.contains(it, ignoreCase = true) }
        val isParcelContext = PARCEL_KEYWORDS.any { allText.contains(it) }
        val avgFontHeight = lines.mapNotNull { it.boundingBox?.height()?.toFloat() }
            .takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f

        for (i in lines.indices) {
            val line = lines[i]
            // 单行匹配
            PREFIXED_CODE.find(line.text)?.let { m ->
                // OCR 常把前缀与码值间的连字符读进码值（取件码-12345 → "-12345"），trim 掉首尾 '-' 再校验
                val code = m.groupValues[2].trim('-')
                if (hasAdjacentNoise(line.text, m)) return@let
                if (isValidStrongContextCode(code)) {
                    val p = m.groupValues[1]
                    candidates.add(Candidate(code,
                        if (p.contains("餐") || p.contains("单")) CodeType.pickup_food else CodeType.pickup_parcel,
                        SCORE_PREFIXED, sourceFromLine(line, p, lines, allText), strong = true))
                }
            }
            // 跨行：OCR 常把「取件码/凭取」拆成两行（如 上一行结尾「取」+ 本行「件码067865」）
            if (i > 0) {
                val prev = lines[i - 1].text.trim()
                // 仅当本行以裸前缀字+码开头（件/餐/货/单+码）且无空格分隔，才尝试拼接上一行尾字
                if (line.text.trim().matches(JOINED_PREFIX_CODE)) {
                    val joined = prev.takeLast(1) + line.text.trim()
                    PREFIXED_CODE.find(joined)?.let { m ->
                        val code = m.groupValues[2].trim('-')
                        if (isValidStrongContextCode(code)) {
                            val p = m.groupValues[1]
                            candidates.add(Candidate(code,
                                if (p.contains("餐") || p.contains("单")) CodeType.pickup_food else CodeType.pickup_parcel,
                                SCORE_PREFIXED, sourceFromLine(line, p, lines, allText), strong = true))
                        }
                    }
                }
            }
        }

        val prefixKw = listOf("取餐码", "取餐号", "取单码", "取单号", "取件码", "取货码", "排号", "提取码")
        for (i in lines.indices) {
            if (prefixKw.any { lines[i].text.contains(it, ignoreCase = true) } && i + 1 < lines.size) {
                val nextLine = lines[i + 1].text.trim()
                // 下一行是订单页 UI 文案（如"202 查看订单详情>"）时不走跨行前缀路径，避免抓错行
                if (NEXT_LINE_UI_NOISE.containsMatchIn(nextLine)) continue
                // Match pure numbers or letter-dash-number codes on the next line
                val nextMatch = NEXT_LINE_CODE.find(nextLine)
                if (nextMatch != null && !hasAdjacentNoise(nextLine, nextMatch) &&
                    isValidStrongContextCode(nextMatch.groupValues[1].trim('-'))) {
                    val code = nextMatch.groupValues[1].trim('-')
                    val isFood = lines[i].text.contains("餐") || lines[i].text.contains("单")
                    candidates.add(Candidate(code,
                        if (isFood) CodeType.pickup_food else CodeType.pickup_parcel,
                        SCORE_PREFIXED, sourceFromLine(lines[i], if (isFood) "取餐码" else "取件码", lines, allText), strong = true))
                }
            }
        }

        data class Rule(val regex: Regex, val type: CodeType, val baseScore: Float,
                        val ctxBonus: Float = 0f, val sizeBonus: Boolean = false, val pureNum: Boolean = false,
                        val minMatchLen: Int = 0, val isLearned: Boolean = false, val strong: Boolean = false)

        // 凭条号句式（凭1-6-5020到...取）：菜鸟驿站/快递柜典型通知，优先且绕过 food 上下文干扰
        for (line in lines) {
            PING_CODE.findAll(line.text).forEach matchLoop@{ m ->
                val code = m.groupValues[1].trim('-')
                if (hasAdjacentNoise(line.text, m) || !isValidStrongContextCode(code) || code.length < 2) return@matchLoop
                var s = SCORE_PREFIXED - PING_BASE_PENALTY
                if (PARCEL_KEYWORDS.any { line.text.contains(it) }) s += PING_PARCEL_BONUS
                if (THREE_SEGMENT_PARCEL.matches(code) || FOUR_SEGMENT_PARCEL.matches(code)) s += PING_MULTISEG_BONUS
                candidates.add(Candidate(code, CodeType.pickup_parcel, s,
                    sourceFromLine(line, "凭条号", lines, allText), strong = true))
            }
        }

        val rules = mutableListOf(
            Rule(THREE_SEGMENT_PARCEL, CodeType.pickup_parcel, SCORE_THREE_SEG, strong = true),
            Rule(FOUR_SEGMENT_PARCEL, CodeType.pickup_parcel, SCORE_FOUR_SEG, strong = true),
            Rule(LETTER_TWO_SEGMENT_PARCEL, CodeType.pickup_parcel, SCORE_LETTER_TWO_SEG, strong = true),
            Rule(LETTER_DASH_FIVE_PARCEL, CodeType.pickup_parcel, SCORE_LETTER_DASH_FIVE, strong = true),
            Rule(LETTER_THREE_SEG_PARCEL, CodeType.pickup_parcel, SCORE_THREE_SEG, strong = true),
            Rule(LETTER_DASH_THREE_PARCEL, CodeType.pickup_parcel, SCORE_LETTER_DASH_THREE, strong = true),
            // 兔喜式单段码（5-3858）：低分不 strong——同屏有更强段式码时被 top×0.75 过滤，
            // 防 "1-6-5020" 的子串 "6-5020" 被误抓；带"取件码为"前缀的走 PREFIXED_CODE 高分强路径。
            Rule(DIGIT_DASH_PARCEL, CodeType.pickup_parcel, SCORE_LONG_NUM_PARCEL),
            Rule(LONG_NUMBER_PARCEL, CodeType.pickup_parcel, SCORE_LONG_NUM_PARCEL, SCORE_CTX_BONUS),
            Rule(LETTER_NUMBER_FOOD, CodeType.pickup_food, SCORE_LETTER_NUM_FOOD, SCORE_CTX_BONUS, true),
            Rule(PURE_NUMBER_FOOD, CodeType.pickup_food, SCORE_PURE_NUM_FOOD, SCORE_CTX_BONUS, true, true)
        )

        // Load auto-learned patterns
        // B3: 记住"编译后 pattern -> 存储用 regex 字符串"，命中时用来 touchRule 刷新 lastUsedAt
        val regexToLearned = mutableMapOf<String, String>()
        if (context != null) {
            val learned = com.pickupcode.app.learner.PatternLearner.getLearnedPatterns(context)
            // 诊断：已加载的自学习规则概览（仅 Debug 构建，避免无障碍热路径日志开销）
            if (com.pickupcode.app.BuildConfig.DEBUG) {
                val active = learned.count { it.enabled && it.badCount < 3 }
                android.util.Log.d("LearnedDiag", "自学习规则共 ${learned.size} 条（启用 $active / 停用 ${learned.size - active}）: " +
                    learned.filter { it.enabled && it.badCount < 3 }
                        .joinToString { "${it.regex}[${it.type}]${if (it.decayed) "(衰减)" else ""}" })
            }
            for (rule in learned) {
                // A1: 用户手动停用的规则不再参与识别
                // A1: 用户手动停用 → 跳过；badCount ≥ 3 → 自动停用
                if (!rule.enabled || rule.badCount >= 3) continue
                try {
                    val regex = Regex(rule.regex)
                    val type = if (rule.type == "pickup_food") CodeType.pickup_food else CodeType.pickup_parcel
                    // 已学规则基础分低；B3: 若已衰减(超期未用)则进一步压到极低分，仍参与但不抢先，
                    // 若后续真实被用到会经 touchRule 解除衰减 —— 让衰减可自愈，而非单向永久弃用。
                    val base = if (rule.decayed) SCORE_LEARNED_DECAYED_BASE else SCORE_LEARNED_BASE
                    rules.add(Rule(regex, type, base, SCORE_CTX_BONUS, minMatchLen = 3, isLearned = true))
                    regexToLearned[regex.pattern] = rule.regex
                } catch (_: Exception) { /* skip invalid regex */ }
            }
        }

        // B3: 命中已学规则时先记录 code→规则，待最终 results 确定后再 touchRule 刷新 lastUsedAt，
        // 避免"幽灵匹配"（规则命中但候选因分数过低未进入最终结果）也给规则续命、架空衰减机制。
        val learnedHits = mutableMapOf<String, String>()

        for (line in lines) {
            val pos = posBonus(line, screenHeight)
            val size = sizeBonus(line, avgFontHeight)
            for (rule in rules) {
                rule.regex.findAll(line.text).forEach matchLoop@{ m ->
                    if (hasAdjacentNoise(line.text, m)) return@matchLoop
                    if (isExcluded(m.value, context)) {
                        // 诊断：被自学习排除词命中时单独提示（普通排除原因不逐条打，避免刷屏）
                        if (context != null && PatternLearner.isLearnedExcluded(m.value, context)) {
                            android.util.Log.d("LearnedDiag", "候选 ${m.value} 被自学习排除词命中剔除（规则 ${rule.regex.pattern}）")
                        }
                        return@matchLoop
                    }
                    // 诊断：已学规则命中（仅 Debug 构建）
                    if (rule.isLearned && com.pickupcode.app.BuildConfig.DEBUG) {
                        android.util.Log.d("LearnedDiag", "已学规则命中候选 ${m.value}（${rule.regex.pattern}）")
                    }
                    // Auto-learned rules: reject over-short matches (e.g. X1 / A1 2-char noise)
                    if (rule.minMatchLen > 0 && m.value.length < rule.minMatchLen) return@matchLoop
                    var s = rule.baseScore + pos
                    if (rule.sizeBonus) s += size

                    if (rule.pureNum) {
                        val n = m.value.length
                        val kw = FOOD_KEYWORDS.any { line.text.contains(it, ignoreCase = true) }
                        val big = avgFontHeight > 0 && line.boundingBox != null &&
                            line.boundingBox.height() > avgFontHeight * FONT_SIZE_RATIO_THRESHOLD
                        if (n <= 2 && !kw && !big) return@matchLoop
                        if (n == 5) s += SCORE_PURE_NUM_5DIGIT_BONUS
                        if (isFoodContext) {
                            if (kw || big) s += SCORE_CTX_BONUS
                            else if (line.boundingBox != null && line.boundingBox.height() > LARGE_FONT_HEIGHT_PX) s += PURE_NUM_BIG_FONT_FOOD_BONUS
                            else s -= SCORE_FOOD_NO_SIGNAL_PENALTY
                        } else if (!kw && !big) return@matchLoop
                    }

                    val ctxOk = when (rule.type) { CodeType.pickup_food -> isFoodContext; CodeType.pickup_parcel -> isParcelContext; CodeType.coupon -> false }
                    if (ctxOk) s += rule.ctxBonus
                    val conflict = when (rule.type) { CodeType.pickup_food -> isParcelContext && !isFoodContext; CodeType.pickup_parcel -> isFoodContext && !isParcelContext; CodeType.coupon -> false }
                    if (conflict) s -= SCORE_CONFLICT_TYPE_PENALTY

                    // B3: 命中已学规则 → 先记录，待最终结果确定后统一 touchRule（见 extract 尾部）
                    if (context != null && rule.isLearned && m.value.length >= 3) {
                        regexToLearned[rule.regex.pattern]?.let { r ->
                            learnedHits[m.value] = r
                        }
                    }

                    candidates.add(Candidate(m.value, rule.type, s, sourceFromLine(line,
                        if (rule.type == CodeType.pickup_food) "food" else "parcel", lines, allText), strong = rule.strong))
                }
            }
        }

        if (candidates.isEmpty()) return emptyList()

        if (isParcelContext && !isFoodContext) candidates.replaceAll { c -> if (c.type == CodeType.pickup_food) c.copy(score = c.score - SCORE_CROSS_TYPE_PENALTY) else c }
        if (isFoodContext && !isParcelContext) candidates.replaceAll { c -> if (c.type == CodeType.pickup_parcel) c.copy(score = c.score - SCORE_CROSS_TYPE_PENALTY) else c }
        val hasMultiseg = candidates.any { it.type == CodeType.pickup_parcel && (THREE_SEGMENT_PARCEL.matches(it.code) || FOUR_SEGMENT_PARCEL.matches(it.code)) }
        if (hasMultiseg) candidates.replaceAll { c -> if (c.type == CodeType.pickup_parcel && LONG_NUMBER_PARCEL.matches(c.code)) c.copy(score = c.score - SCORE_MULTISEG_LONG_NUM_PENALTY) else c }
        val hasOrder = allText.contains(ORDER_LONG_SQL) || allText.contains(ORDER_SHORT_SQL)
        if (hasOrder) {
            candidates.replaceAll { c ->
                if (LONG_NUMBER_PARCEL.matches(c.code)) c.copy(score = c.score - SCORE_ORDER_LONG_NUM_PENALTY)
                else if (c.type == CodeType.pickup_parcel && c.code.all { it.isDigit() }) c.copy(score = c.score - SCORE_ORDER_DIGIT_PENALTY)
                else c
            }
        }

        val codeKeywordLines = lines.filter { it.text.contains(CODE_KEYWORD_NEAR) }
        if (codeKeywordLines.isNotEmpty()) {
            candidates.replaceAll { c ->
                val nearKeyword = codeKeywordLines.any { kw ->
                    val lineIdx = lines.indexOf(kw)
                    val candidateLineIdx = lines.indexOfFirst { it.text.contains(c.code) }
                    candidateLineIdx >= 0 && kotlin.math.abs(lineIdx - candidateLineIdx) <= 2
                }
                if (nearKeyword) c.copy(score = c.score + SCORE_NEAR_KEYWORD_BONUS) else c
            }
        }

        candidates.sortByDescending { it.score }
        // 仅 Debug 构建输出诊断日志（生产裁剪掉逐行 dump + 候选遍历，避免每次识别的 IO/日志开销）
        if (com.pickupcode.app.BuildConfig.DEBUG && context != null) {
            // 逐行 OCR 结构 dump：看 TextLine 是怎么拆行的（跨行粘连/拆断是很多误报的根源）
            lines.forEachIndexed { idx, tl ->
                val bb = tl.boundingBox
                val bbS = if (bb != null) "(x=${bb.left},y=${bb.top},w=${bb.width()},h=${bb.height()})" else "(no-box)"
                android.util.Log.d("CodeExtrDiag", "LINE[$idx] $bbS conf=${tl.confidence} @ ${tl.text}")
            }
            android.util.Log.d("CodeExtrDiag", "hasOrder=" + (allText.contains(ORDER_LONG_SQL) || allText.contains(ORDER_SHORT_SQL)) + " allText=" + allText)
            for (it in candidates) {
                // 补上匹配到的原文上下文 + 所在行号，便于定位是哪个规则、哪段文本捕的
                val lineIdx = lines.indexOfFirst { l -> l.text.contains(it.code) }
                val ctx = if (lineIdx >= 0) lines[lineIdx].text else "?"
                android.util.Log.d("CodeExtrDiag", "cand: code=${it.code} score=${it.score} type=${it.type} src=${it.source} line=$lineIdx ctx=$ctx")
            }
            // 调试快照（识别调试视图用）：与日志同源，UI 面板直接消费
            RecognitionDebugStore.capture(
                lines = lines,
                candidates = candidates.map { c ->
                    val li = lines.indexOfFirst { l -> l.text.contains(c.code) }
                    RecognitionDebugStore.CandidateInfo(
                        code = c.code,
                        score = c.score,
                        type = c.type.name,
                        source = c.source,
                        lineIndex = li,
                        context = if (li >= 0) lines[li].text else "?"
                    )
                },
                allText = allText,
                source = source
            )
        }
        val seen = mutableSetOf<String>()
        val results = mutableListOf<ExtractedCode>()
        // 子串消除（真机日志对照分析新增）：OCR 截断/规则重叠会产生"长码的子串"（如 3-6-403 是 3-6-4035 的子串、
        // 1-6-5020 的子串 6-5020），只保留最长者，避免短残码入库
        val byLen = candidates.sortedByDescending { it.code.length }
        val keptCands = byLen.filter { c -> byLen.none { o -> o !== c && c.code in o.code && o.code.length > c.code.length } }
        val top = keptCands.firstOrNull()?.score ?: 0f
        for (c in keptCands) {
            if (c.code in seen) continue; seen.add(c.code)
            // 修复多通知同屏漏识别：强上下文证据码(PREFIXED/凭条/段式)不过 top×0.75 阈值，
            // 只对无证据的弱候选(纯数字噪声)做 top×0.75 过滤，避免高分码拖死同屏次高分真实码。
            if (c.strong || c.score >= top * STRONG_PASS_RATIO)
                results.add(ExtractedCode(c.code, c.type, c.source, (c.score / SCORE_PREFIXED).coerceIn(0f, 1f)))
        }
        // B3: 仅对真正进入结果的码刷新对应已学规则的 lastUsedAt（幽灵匹配不再续命）
        if (context != null) {
            for (r in results) {
                learnedHits[r.code]?.let { PatternLearner.touchRule(context, it) }
            }
        }
        if (context != null) recordLearning(context, results, allText, source)
        return results
    }

    private data class Candidate(val code: String, val type: CodeType, val score: Float, val source: String, val strong: Boolean = false)

    /**
     * 带强前缀上下文（取件码/取餐码/凭条号等）的码值校验。
     * 标准白名单把纯数字收紧到 4-5 位以上（防裸数字 42/123 噪声），但带"取餐码为123"这类
     * 强前缀时 2-3 位纯数字是真实取餐码（蜜雪/瑞幸常见，README 已声明 123 覆盖）——
     * 此处放行 2-3 位纯数字，但仍过内容噪声检查（全 0 全 1/递增/连号等一律拒绝）。
     */
    private fun isValidStrongContextCode(code: String): Boolean {
        val c = code.trim()
        if (c.isBlank() || c.length > 12) return false
        // OCR 常在码尾粘入字母（如 3-1-403x 应为 3-1-4035）：合法格式全部以数字结尾，尾部字母一律拒绝
        if (c.last().isLetter()) return false
        return if (c.all { it.isDigit() } && c.length in 2..3) {
            !CodeValidator.isContentNoise(c)
        } else {
            !isExcluded(c)
        }
    }

    private fun posBonus(line: OCREngine.TextLine, screenHeight: Int): Float {
        val box = line.boundingBox ?: return 0f
        if (screenHeight > 0 && box.centerY() in (screenHeight * POS_BONUS_Y_RANGE.start).toInt()..(screenHeight * POS_BONUS_Y_RANGE.endInclusive).toInt()) return POS_BONUS_VALUE
        return 0f
    }

    private fun sizeBonus(line: OCREngine.TextLine, avgFontHeight: Float): Float {
        val box = line.boundingBox ?: return 0f
        var b = 0f
        if (box.height() > LARGE_FONT_HEIGHT_PX) b += SIZE_BIG_FONT_BONUS
        if (avgFontHeight > 0 && box.height() > avgFontHeight * FONT_SIZE_RATIO_THRESHOLD) b += SIZE_RATIO_BONUS
        return b
    }

    /**
     * 上下文邻接噪声：码值紧邻的字符暗示这不是取件码（真机日志对照分析新增，来源：49 张真实截图）。
     * - 电量百分比：`529%`（battery）
     * - 掩码手机号：`86-182****6726`（号码保护）
     * - 时间/日期尾随冒号：`20:15`、`08-0617:11:21`
     * - 国标号：`GB/T19777`（山西老陈醋标准号）
     * - 座机区号前缀：`0394-8301307`（6-8 位纯数字且前邻 3-4 位区号）
     */
    private fun hasAdjacentNoise(line: String, match: MatchResult): Boolean {
        val code = match.value
        val after = line.getOrNull(match.range.last + 1)
        if (after == '%' || after == '*') return true
        if (after == ':' && code.length <= 8) return true
        if (match.range.first >= 3 && line.substring(match.range.first - 3, match.range.first) == "GB/") return true
        if (code.length in 6..8 && code.all { it.isDigit() } &&
            AREA_CODE_BEFORE.containsMatchIn(line.substring(0, match.range.first))) return true
        return false
    }

    // ---------------------------------------------------------------
    // Pattern learning feedback
    // ---------------------------------------------------------------

    private fun recordLearning(context: Context, results: List<ExtractedCode>, allText: String, source: String) {
        if (results.isNotEmpty()) {
            for (r in results) {
                val pid = classifyFormat(r.code)
                PatternLearner.recordAttempt(context, pid)
            }
        } else {
            PatternLearner.recordMiss(context, allText.take(RECORD_MISS_SNIPPET_LEN), source)
        }
    }
}
