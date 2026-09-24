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
    // 码值紧贴中文（如 "306284复制"）时 \b 失效漏抓；桌面 JVM 测不出来（ASCII \b），真机必现。    // B6-2-7041: letter prefix + 3 dash-separated segments, e.g. locker codes (A/B/C prefix)
    private val LETTER_THREE_SEG_PARCEL = Regex("(?<![\\dA-Za-z])([A-Za-z]\\d{1,2})-(\\d{1,2})-(\\d{3,6})(?![\\dA-Za-z])", RegexOption.IGNORE_CASE)
    private val LETTER_DASH_THREE_PARCEL = Regex("(?<![\\dA-Za-z])([A-Za-z])-(\\d{3,4})(?![\\dA-Za-z])", RegexOption.IGNORE_CASE)
    // 字母+数字（餐饮取餐码）：**不允许内部空格**。2026-09-16 真实语料回归（58 张真机截图）发现：
    // 美团券页把「¥10.8 ¥16」OCR 成「H 10.8 *6」，旧的 \s* 写法把 "H 10"（码值里还带空格）当取餐码入库。
    private val LETTER_NUMBER_FOOD = Regex("(?<![\\dA-Za-z])([A-Z]-?\\d{2,4})(?![\\dA-Za-z])", RegexOption.IGNORE_CASE)
    private val PURE_NUMBER_FOOD = Regex("(?<![\\dA-Za-z-])(\\d{2,5})(?![\\dA-Za-z])")
    private val PREFIXED_CODE = Regex("(取[餐件货单]码|取餐号|取单号|排号|提取码)[:：]?\\s*(?:为|是)?\\s*([A-Za-z0-9\\-]{2,12})")
    // 券号（团购券/到店券的数字券码）：OCR 常按字符间隙拆出空格（券号1246 1046 4170 008），
    // 贪婪捕获整段后去空格/间隔点还原完整码；PREFIXED/PING 不再管券号（12 位上限且类型错）。
    private val COUPON_NUMBER = Regex("券号[:：]?\\s*([\\d][\\d\\s·.]{3,28}[\\d])")
    // 菜鸟/驿站类通知标准句式：凭3-7-4162到...取（件）；容忍 OCR 在码值与方位词间插入空格
    private val PING_CODE = Regex("(?:凭|好评码|提取码)[:：]?\\s*([A-Za-z0-9\\-]{2,12}?)\\s*(?=(?:到|至|去|领|取|在|格|号柜|菜鸟|驿站|快递柜))", RegexOption.IGNORE_CASE)

    // 跨行前缀：上一行是取件码/凭条等词 + 下一行开头是码（后接地址/通知等）；去掉行尾$锚点，
    // 否则"412908 到长兴路..."这类码后跟真实地址的会被漏抓（需保留开头强锚定 + 后不能紧邻数字/破折号）
    private val NEXT_LINE_CODE = Regex("^\\s*([A-Za-z0-9\\-]{2,12})\\s*(?![-\\d])")
    private val CODE_KEYWORD_NEAR = Regex("(取[件餐货]码|取餐号|驿站|快递柜|自提柜|取件点)")
    /**
     * 「标签在上一行、码值在下一行」用的**整行标签**（`^..$` 锚定，标签行自身不含码值才算）。
     * 真机案例（2026-09-16 真实语料）：美团外卖配送页「取餐号」(y=803) 正下方 y=843 是唯一真实码 QTP07，
     * 但 OCR 行数组里中间插进了地图标签行，"按数组下标的下一行"规则完全取不到 → 见 [nearestWholeLineCodeBelow]。
     */
    private val LABEL_FOR_CODE = Regex("^(取[餐件货单][码号]|取餐号|取单号|提取码|凭条号)$")
    /** 整行就是一个码值（跨行标签规则的取值约束，避免从句子里抠片段）。 */
    private val WHOLE_LINE_CODE = Regex("^([A-Za-z0-9][A-Za-z0-9\\-]{1,11})$")
    private const val LABEL_GAP_LINES = 3
    private const val LABEL_GAP_MIN_PX = 48
    /**
     * 餐饮"局部证据"标签：字母+数字码不能只靠"全屏某处出现取餐"就采信
     * （真机反例：高德/美团地图上的长兴高速编号 S26，同一屏有「取餐号」但相隔 13 行 / 纵向 400px）。
     */
    private val FOOD_LABEL_LOCAL = Regex("(取[餐单][码号]|排号|请取餐|正在制作|等待取餐|取餐)")
    private const val FOOD_LABEL_WINDOW_LINES = 2
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
        // ⚠️ 这里**不能出现单字词**：历史上含裸字「柜」，导致
        // 「【xx银行】储蓄卡消费验证码 618008…柜台业务请咨询」被当成快递信号放行，长数字 618008 以取件码入库。
        // 要表达"柜"必须用复合词；isFinancialNoise 另有长度 ≥2 的兜底校验，防止以后再被加进单字词。
        "取件", "快递", "包裹", "驿站", "代收点", "货栈", "提货", "开箱", "运单", "取餐", "取餐码",
        "快递柜", "智能柜", "自提柜", "取件柜", "云柜",
        // 餐饮取餐信号：点单/排队截图常带支付信息（微信支付/实付），若无这些词会被金融闸门误杀
        // （真机复测发现：LINLEE 取单码截图被金融噪音拦截）
        "取餐号", "取单码", "取单号", "排队", "点单"
    )

    /** 券码上下文信号词：团购券/兑换码类截图（识别目标就是券号），命中即放行金融闸门。 */
    private val COUPON_SIGNAL_KEYWORDS = listOf("券号", "券码", "兑换码", "团购券")

    /**
     * 判断一段文本是否为金融/支付类噪音（非取件场景）。
     * 命中金融词且没有快递/取件信号词 → true（应拦截）。
     * 同时命中两者 → false（可能是取件通知里带支付提醒，放行）。
     * 命中券码信号词 → false（团购券截图带"到店消费/无门槛券"等金融词，
     * 但识别目标恰是券号；银行/支付通知不会出现券号/券码/兑换码/团购券）。
     */
    fun isFinancialNoise(text: String): Boolean {
        if (text.isBlank()) return false
        val hasFinancial = FINANCIAL_KEYWORDS.any { text.contains(it, ignoreCase = true) }
        if (!hasFinancial) return false
        // 兜底：信号词必须 ≥2 字。单字（如「柜」）在中文里太常见，会被"柜台/柜员"这类金融文案误命中，
        // 从而把金融验证码放行成取件码（历史上就是这么错的）。
        val hasExpressSignal = EXPRESS_SIGNAL_KEYWORDS.any { it.length >= 2 && text.contains(it, ignoreCase = true) }
        if (hasExpressSignal) return false
        val hasCouponSignal = COUPON_SIGNAL_KEYWORDS.any { it.length >= 2 && text.contains(it) }
        return !hasCouponSignal
    }

    // ---------------------------------------------------------------
    // Code extraction
    // ---------------------------------------------------------------

    // ===============================================================
    // 内置正则模式表（供识别 + 「识别规则」页展示/覆盖）
    //
    // 为什么要有这张表：以前这些正则是散在代码里的匿名 Regex，用户既看不到也管不了。
    // 现在给每条一个稳定 id，用户就能停用/改写/还原，而**出厂定义始终留在代码里**——
    // 用户覆盖只存在 SharedPreferences（见 PatternLearner 的 builtin_disabled / builtin_regex），
    // 所以「一键还原」＝丢掉覆盖即可，永远不会把默认行为改丢。
    //
    // ⚠️ id 一旦发布就不可更改（用户覆盖以 id 为键）；要调整只能新增 id。
    // ===============================================================

    internal data class BuiltinRule(
        val id: String,
        val label: String,
        val regex: Regex,
        val type: CodeType,
        val baseScore: Float = 0f,
        val ctxBonus: Float = 0f,
        val sizeBonus: Boolean = false,
        val pureNum: Boolean = false,
        val strong: Boolean = false,
        val requireLocalCtx: Boolean = false,
        /**
         * 能否改写正则。评分类规则（下面的 BUILTIN_SCORING_RULES）取整段匹配值当候选，
         * 改正则只影响"抓什么"，安全；而特殊块（BUILTIN_SPECIAL_RULES）依赖捕获组
         * （`groupValues[1]/[2]`），改坏了会直接崩，所以只允许停用/还原。
         */
        val editable: Boolean = true
    )

    /** 参与评分的 10 条内置模式。顺序即原有顺序。 */
    private val BUILTIN_SCORING_RULES: List<BuiltinRule> = listOf(
        BuiltinRule("THREE_SEGMENT_PARCEL", "三段式取件码（1-2-3456）", THREE_SEGMENT_PARCEL,
            CodeType.pickup_parcel, SCORE_THREE_SEG, strong = true),
        BuiltinRule("FOUR_SEGMENT_PARCEL", "四段式取件码（A1-2-3-45）", FOUR_SEGMENT_PARCEL,
            CodeType.pickup_parcel, SCORE_FOUR_SEG, strong = true),
        BuiltinRule("LETTER_TWO_SEGMENT_PARCEL", "两段式字母（A-1-234）", LETTER_TWO_SEGMENT_PARCEL,
            CodeType.pickup_parcel, SCORE_LETTER_TWO_SEG, strong = true),
        BuiltinRule("LETTER_DASH_FIVE_PARCEL", "字母-数字（H-24137）", LETTER_DASH_FIVE_PARCEL,
            CodeType.pickup_parcel, SCORE_LETTER_DASH_FIVE, strong = true),
        BuiltinRule("LETTER_THREE_SEG_PARCEL", "字母三段式（B6-2-7041）", LETTER_THREE_SEG_PARCEL,
            CodeType.pickup_parcel, SCORE_THREE_SEG, strong = true),
        BuiltinRule("LETTER_DASH_THREE_PARCEL", "字母+三位数字（A-123）", LETTER_DASH_THREE_PARCEL,
            CodeType.pickup_parcel, SCORE_LETTER_DASH_THREE, strong = true),
        // 兔喜式单段码（7-2914）：低分不 strong——同屏有更强段式码时被 top×0.75 过滤
        BuiltinRule("DIGIT_DASH_PARCEL", "单段式取件码（7-2914）", DIGIT_DASH_PARCEL,
            CodeType.pickup_parcel, SCORE_LONG_NUM_PARCEL),
        BuiltinRule("LONG_NUMBER_PARCEL", "长数字（6-8位）", LONG_NUMBER_PARCEL,
            CodeType.pickup_parcel, SCORE_LONG_NUM_PARCEL, SCORE_CTX_BONUS),
        BuiltinRule("LETTER_NUMBER_FOOD", "取餐码（字母+数字）", LETTER_NUMBER_FOOD,
            CodeType.pickup_food, SCORE_LETTER_NUM_FOOD, SCORE_CTX_BONUS, sizeBonus = true, requireLocalCtx = true),
        BuiltinRule("PURE_NUMBER_FOOD", "取餐码（纯数字）", PURE_NUMBER_FOOD,
            CodeType.pickup_food, SCORE_PURE_NUM_FOOD, SCORE_CTX_BONUS, sizeBonus = true, pureNum = true)
    )

    /** 走独立代码块的特殊模式：停用 = 跳过对应代码块；不支持改写正则（逻辑依赖捕获组）。 */
    private val BUILTIN_SPECIAL_RULES: List<BuiltinRule> = listOf(
        BuiltinRule("PREFIXED_CODE", "前缀匹配（取件码: XXX）", PREFIXED_CODE,
            CodeType.pickup_parcel, SCORE_PREFIXED, strong = true, editable = false),
        BuiltinRule("NEXT_LINE_CODE", "跨行前缀取码（标签在上一行）", NEXT_LINE_CODE,
            CodeType.pickup_parcel, SCORE_PREFIXED, strong = true, editable = false),
        BuiltinRule("LABEL_FOR_CODE", "标签行正下方取码", LABEL_FOR_CODE,
            CodeType.pickup_parcel, SCORE_PREFIXED, strong = true, editable = false),
        BuiltinRule("PING_CODE", "凭条号句式（凭 3-7-4162 到…取）", PING_CODE,
            CodeType.pickup_parcel, SCORE_PREFIXED - PING_BASE_PENALTY, strong = true, editable = false),
        BuiltinRule("COUPON_NUMBER", "券号（券号: 长数字）", COUPON_NUMBER,
            CodeType.coupon, SCORE_PREFIXED, strong = true, editable = false)
    )

    internal val ALL_BUILTIN_RULES: List<BuiltinRule> get() = BUILTIN_SCORING_RULES + BUILTIN_SPECIAL_RULES

    /** 永不匹配的正则。把某条特殊模式替换成它 = 跳过对应代码块（比给每个块加大段 if 缩进更不容易改错）。 */
    private val NEVER_MATCH = Regex("(?!)")

    /** 供「识别规则」页展示的内置模式快照（已套用用户覆盖）。 */
    data class BuiltinRuleInfo(
        val id: String,
        val label: String,
        val regex: String,
        val type: String,
        val editable: Boolean,
        val enabled: Boolean,
        val overridden: Boolean
    )

    /**
     * 这条码值是否**已经能被内置评分正则抓到**（已套用用户对内置正则的停用/改写）。
     *
     * 用途：用户确认一个码值"是对的"时，判断要不要把它学成新规则 ——
     * 内置本来就能抓到的形状，学了也不会被用上（自学习规则基础分只有 65，永远抢不过内置），
     * 只会往规则表里堆一条永远命不中的垃圾规则。所以这种一律不学。
     *
     * 注意只检查**评分规则**：前缀/凭条号/券号那几个特殊模式依赖周围标签文本，
     * 单独一个码值字符串本来就不会被它们匹配，不构成"已覆盖"。
     */
    internal fun builtinCovers(code: String, context: Context? = null): Boolean {
        if (code.isBlank()) return false
        val ov = if (context == null) PatternLearner.BuiltinOverrides()
                 else PatternLearner.cachedBuiltinOverrides(context)
        return BUILTIN_SCORING_RULES.any { b ->
            if (ov.isDisabled(b.id)) return@any false
            runCatching { effectiveRegex(b, ov).containsMatchIn(code) }.getOrDefault(false)
        }
    }

    fun builtinRuleInfos(context: Context?): List<BuiltinRuleInfo> {
        val ov = if (context == null) PatternLearner.BuiltinOverrides()
                 else PatternLearner.getBuiltinOverrides(context)
        return ALL_BUILTIN_RULES.map {
            BuiltinRuleInfo(
                id = it.id,
                label = it.label,
                regex = ov.regexFor(it.id) ?: it.regex.pattern,
                type = it.type.name,
                editable = it.editable,
                enabled = !ov.isDisabled(it.id),
                overridden = ov.regexFor(it.id) != null
            )
        }
    }

    /**
     * 取某条内置规则当前生效的正则：优先用户改写版。
     * 用户把正则改坏（语法错）时**回退到出厂正则**并记警告——识别不能因为一条自定义规则整体失效。
     */
    private fun effectiveRegex(rule: BuiltinRule, ov: PatternLearner.BuiltinOverrides): Regex {
        val edited = ov.regexFor(rule.id) ?: return rule.regex
        return try {
            Regex(edited)
        } catch (_: Exception) {
            android.util.Log.w("CodeExtractor", "内置规则 ${rule.id} 的自定义正则无法编译，已回退到默认")
            rule.regex
        }
    }

    fun extract(lines: List<OCREngine.TextLine>, screenHeight: Int = 0, context: Context? = null, source: String = "screen"): List<ExtractedCode> {
        // 文本预处理：全角→半角归一化 + 词级纠错表（参考同类产品实现 normalizeText / textCorrections）
        val lines = lines.map { it.copy(text = OcrCorrections.apply(normalizeText(it.text))) }
        val candidates = mutableListOf<Candidate>()
        val allText = lines.joinToString(" ") { it.text }
        val isFoodContext = FOOD_KEYWORDS.any { allText.contains(it, ignoreCase = true) }
        val isParcelContext = PARCEL_KEYWORDS.any { allText.contains(it) }
        val avgFontHeight = lines.mapNotNull { it.boundingBox?.height()?.toFloat() }
            .takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f

        // 用户对内置正则的覆盖（停用 / 改写）。热路径带 2s 缓存，避免每次识别都读盘+解析 JSON。
        val ov = if (context != null) PatternLearner.cachedBuiltinOverrides(context)
                 else PatternLearner.BuiltinOverrides()
        // 特殊模式（依赖捕获组、逻辑与代码绑定）只支持停用 → 换成永不匹配的正则即可跳过
        fun special(id: String, original: Regex) = if (ov.isDisabled(id)) NEVER_MATCH else original
        val prefixedCode   = special("PREFIXED_CODE", PREFIXED_CODE)
        val nextLineCode   = special("NEXT_LINE_CODE", NEXT_LINE_CODE)
        val labelForCode   = special("LABEL_FOR_CODE", LABEL_FOR_CODE)
        val pingCode       = special("PING_CODE", PING_CODE)
        val couponNumber   = special("COUPON_NUMBER", COUPON_NUMBER)

        for (i in lines.indices) {
            val line = lines[i]
            // 单行匹配
            prefixedCode.find(line.text)?.let { m ->
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
            // 跨行：OCR 常把「取件码/凭取」拆成两行（如 上一行结尾「取」+ 本行「件码041327」）
            if (i > 0) {
                val prev = lines[i - 1].text.trim()
                // 仅当本行以裸前缀字+码开头（件/餐/货/单+码）且无空格分隔，才尝试拼接上一行尾字
                if (line.text.trim().matches(JOINED_PREFIX_CODE)) {
                    val joined = prev.takeLast(1) + line.text.trim()
                    prefixedCode.find(joined)?.let { m ->
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
                val nextMatch = nextLineCode.find(nextLine)
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

        // 标签行 + **竖直正下方**的码（2026-09-16 真实语料回归新增）：
        // OCR 行数组顺序不可靠——地图/浮层标签会插进标签与码值之间，"数组下一行"规则会漏掉真实码。
        // 真机案例：美团外卖配送页「取餐号」在 y=803，唯一真实码 QTP07 在 y=843，而中间隔了 4 个数组下标。
        for ((i, labelLine) in lines.withIndex()) {
            val labelMatch = labelForCode.find(labelLine.text.trim()) ?: continue
            val token = nearestWholeLineCodeBelow(lines, i) ?: continue
            if (!isValidStrongContextCode(token)) continue
            val isFood = labelMatch.value.contains("餐") || labelMatch.value.contains("单")
            candidates.add(Candidate(
                token,
                if (isFood) CodeType.pickup_food else CodeType.pickup_parcel,
                SCORE_PREFIXED,
                sourceFromLine(labelLine, if (isFood) "取餐号" else "取件码", lines, allText),
                strong = true
            ))
        }

        data class Rule(val regex: Regex, val type: CodeType, val baseScore: Float,
                        val ctxBonus: Float = 0f, val sizeBonus: Boolean = false, val pureNum: Boolean = false,
                        val minMatchLen: Int = 0, val isLearned: Boolean = false, val strong: Boolean = false,
                        val requireLocalCtx: Boolean = false)

        // 凭条号句式（凭3-7-4162到...取）：菜鸟驿站/快递柜典型通知，优先且绕过 food 上下文干扰
        for (line in lines) {
            pingCode.findAll(line.text).forEach matchLoop@{ m ->
                val code = m.groupValues[1].trim('-')
                if (hasAdjacentNoise(line.text, m) || !isValidStrongContextCode(code) || code.length < 2) return@matchLoop
                var s = SCORE_PREFIXED - PING_BASE_PENALTY
                if (PARCEL_KEYWORDS.any { line.text.contains(it) }) s += PING_PARCEL_BONUS
                if (THREE_SEGMENT_PARCEL.matches(code) || FOUR_SEGMENT_PARCEL.matches(code)) s += PING_MULTISEG_BONUS
                candidates.add(Candidate(code, CodeType.pickup_parcel, s,
                    sourceFromLine(line, "凭条号", lines, allText), strong = true))
            }
        }

        // 券号提取（团购券/到店券）：一次捕获整段跨空格长数字后去空格/间隔点还原完整码。
        // 不走 isValidStrongContextCode/isExcluded（其格式白名单最长 8 位数字、14 位上限，
        // 15 位团购券号必被拒）；用 内容噪声检查 + 6..20 位纯数字 独立校验。
        // 类型固定 coupon：受"券码识别"开关控制，无到期提醒，与二维码券码同通道。
        for (line in lines) {
            couponNumber.findAll(line.text).forEach { m ->
                val digits = m.groupValues[1].replace(Regex("[\\s·.]"), "")
                if (digits.length in 6..20 && digits.all { it.isDigit() } &&
                    !CodeValidator.isContentNoise(digits)) {
                    candidates.add(Candidate(digits, CodeType.coupon, SCORE_PREFIXED,
                        sourceFromLine(line, "券号", lines, allText), strong = true))
                }
            }
        }

        // 评分类规则由内置表生成：跳过被用户停用的，正则取用户改写版（改坏了自动回退到出厂版）
        val rules = mutableListOf<Rule>()
        for (b in BUILTIN_SCORING_RULES) {
            if (ov.isDisabled(b.id)) continue
            rules.add(
                Rule(
                    effectiveRegex(b, ov), b.type, b.baseScore, b.ctxBonus, b.sizeBonus, b.pureNum,
                    strong = b.strong, requireLocalCtx = b.requireLocalCtx
                )
            )
        }

        // Load auto-learned patterns
        // B3: 记住"编译后 pattern -> 存储用 regex 字符串"，命中时用来 touchRule 刷新 lastUsedAt
        val regexToLearned = mutableMapOf<String, String>()
        if (context != null) {
            val learned = com.pickupcode.app.learner.PatternLearner.cachedLearnedPatterns(context)
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
                    // 三种类型都支持：自动学习只会产出 parcel/food，但**用户手动添加**的规则可以是券码
                    val type = when (rule.type) {
                        "pickup_food" -> CodeType.pickup_food
                        "coupon" -> CodeType.coupon
                        else -> CodeType.pickup_parcel
                    }
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

        for ((lineIdx, line) in lines.withIndex()) {
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
                    // 弱证据餐饮规则（字母+数字）必须有**局部**餐饮信号，不能只靠"全屏某处出现取餐"：
                    // 真机反例——地图上的高速编号 S26 与「取餐号」同屏但相隔 13 行 / 纵向 400px，曾被当成取餐码入库。
                    if (rule.requireLocalCtx && !hasLocalFoodSignal(lines, lineIdx, line, avgFontHeight)) return@matchLoop
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

        if (candidates.isEmpty()) {
            // 无候选时也要留快照——这正是最需要调试面板的场景（此前直接 return，面板无数据）
            debugCapture(lines, emptyList(), emptyList(), allText, source, screenHeight, context)
            return emptyList()
        }

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
        }
        val seen = mutableSetOf<String>()
        val results = mutableListOf<ExtractedCode>()
        // 子串消除（真机日志对照分析新增）：OCR 截断/规则重叠会产生"长码的子串"（如 3-6-403 是 3-6-4035 的子串、
        // 3-7-4162 的子串 7-4162），只保留最长者，避免短残码入库
        val byLen = candidates.sortedByDescending { it.code.length }
        val keptCands = byLen.filter { c -> byLen.none { o -> o !== c && c.code in o.code && o.code.length > c.code.length } }
        // 阈值基准必须是"最高分候选"：keptCands 是按**码长**排序的（为上面子串消除服务），
        // 取 firstOrNull() 会拿到"最长候选"的分数 —— 同屏只要有个低分长数字码，阈值就被拉低，
        // 本应被 top×STRONG_PASS_RATIO 淘汰的纯数字噪声会全部放行，且输出顺序也按码长而非分数。
        val top = keptCands.maxOfOrNull { it.score } ?: 0f
        for (c in keptCands.sortedByDescending { it.score }) {
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
        // 调试快照：候选 + 最终结果 + 屏幕高度一并入栈（面板展示 / 语料导出）
        debugCapture(lines, candidates, results, allText, source, screenHeight, context)
        return results
    }

    /** 调试快照（仅 DEBUG）：把候选与最终结果写入 RecognitionDebugStore，供面板与语料导出消费。 */
    private fun debugCapture(
        lines: List<OCREngine.TextLine>,
        candidates: List<Candidate>,
        results: List<ExtractedCode>,
        allText: String,
        source: String,
        screenHeight: Int,
        context: Context?
    ) {
        if (!com.pickupcode.app.BuildConfig.DEBUG || context == null) return
        fun toInfo(code: String, score: Float, type: CodeType, src: String): RecognitionDebugStore.CandidateInfo {
            val li = lines.indexOfFirst { it.text.contains(code) }
            return RecognitionDebugStore.CandidateInfo(
                code = code, score = score, type = type.name, source = src,
                lineIndex = li, context = if (li >= 0) lines[li].text else "?"
            )
        }
        RecognitionDebugStore.capture(
            lines = lines,
            candidates = candidates.map { toInfo(it.code, it.score, it.type, it.source) },
            allText = allText,
            source = source,
            screenHeight = screenHeight,
            finalResults = results.map { toInfo(it.code, it.confidence * SCORE_PREFIXED, it.type, it.source) }
        )
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

    /**
     * 餐饮候选是否具备**局部**证据（2026-09-16 真实语料回归新增）：
     * - 本行含餐饮关键词（如「取餐码 A12」）
     * - 本行字体明显偏大（大号取餐号）
     * - 前后 ±[FOOD_LABEL_WINDOW_LINES] 行内出现餐饮标签（跨行取餐号）
     * 反例：美团外卖地图页的 S26（长兴高速编号）离最近的「取餐号」13 行、纵向 400px。
     */
    private fun hasLocalFoodSignal(
        lines: List<OCREngine.TextLine>,
        lineIdx: Int,
        line: OCREngine.TextLine,
        avgFontHeight: Float
    ): Boolean {
        if (FOOD_KEYWORDS.any { line.text.contains(it, ignoreCase = true) }) return true
        val h = line.boundingBox?.height() ?: 0
        if (h > LARGE_FONT_HEIGHT_PX) return true
        if (avgFontHeight > 0 && h > avgFontHeight * FONT_SIZE_RATIO_THRESHOLD) return true
        val from = maxOf(0, lineIdx - FOOD_LABEL_WINDOW_LINES)
        val to = minOf(lines.size - 1, lineIdx + FOOD_LABEL_WINDOW_LINES)
        for (j in from..to) if (FOOD_LABEL_LOCAL.containsMatchIn(lines[j].text)) return true
        return false
    }

    /**
     * 取标签行**竖直正下方**最近的整行码值（跨行标签规则的几何版）。
     * 只处理"标签独占一行"的情形（标签与码同行的由 PREFIXED_CODE 负责，见 [LABEL_FOR_CODE] 的整行锚定）。
     */
    private fun nearestWholeLineCodeBelow(lines: List<OCREngine.TextLine>, labelIdx: Int): String? {
        val labelBox = lines.getOrNull(labelIdx)?.boundingBox ?: return null
        val maxGap = maxOf(labelBox.height() * LABEL_GAP_LINES, LABEL_GAP_MIN_PX)
        var best: OCREngine.TextLine? = null
        for ((j, l) in lines.withIndex()) {
            if (j == labelIdx) continue
            val b = l.boundingBox ?: continue
            val gap = b.top - labelBox.bottom
            if (gap < 0 || gap > maxGap) continue
            if (WHOLE_LINE_CODE.find(l.text.trim()) == null) continue
            val cur = best
            if (cur == null || b.top < (cur.boundingBox?.top ?: Int.MAX_VALUE)) best = l
        }
        return best?.text?.trim()?.let { WHOLE_LINE_CODE.find(it)?.groupValues?.get(1) }
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
     * - 掩码手机号：`86-135****2468`（号码保护）
     * - 时间/日期尾随冒号：`20:15`、`08-0617:11:21`
     * - 国标号：`GB/T19777`（山西老陈醋标准号）
     * - 座机区号前缀：`0394-6728150`（6-8 位纯数字且前邻 3-4 位区号）
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
