package com.pickupcode.app.extractor

import android.content.Context
import com.pickupcode.app.learner.PatternLearner

/** 码格式校验：合法格式白名单、排除规则与 pattern-ID 分类（自 CodeExtractor 拆出，R1）。 */
object CodeValidator {

    /** 5 个共享解析正则（CodeExtractor 识别用 + 本类的格式分类表用，单一归属）。
     *  边界说明（重要）：不用 \b，改用显式环视 (?<![\dA-Za-z]) / (?![\dA-Za-z])。
     *  桌面 JVM 的 \b 是 ASCII 语义，但 Android libcore 的 java.util.regex 基于 ICU，
     *  \b 把中文视为词字符——码值紧贴中文时（如 OCR 行 "749019复制"）末尾 \b 失效导致漏抓。
     *  （PatternLearner.PURE_CANDIDATE 早已用同样写法规避此坑，见其注释。） */
    internal val THREE_SEGMENT_PARCEL = Regex("(?<![\\dA-Za-z])(\\d{1,3})-(\\d{1,2})-(\\d{3,6})(?![\\dA-Za-z])")
    internal val FOUR_SEGMENT_PARCEL = Regex("(?<![\\dA-Za-z])([A-Za-z]?\\d{1,2})-(\\d{1,2})-(\\d{1,2})-(\\d{2,4})(?![\\dA-Za-z])")
    internal val LETTER_TWO_SEGMENT_PARCEL = Regex("(?<![\\dA-Za-z])([A-Z])-(\\d{1,2})-(\\d{3,4})(?![\\dA-Za-z])", RegexOption.IGNORE_CASE)
    internal val LETTER_DASH_FIVE_PARCEL = Regex("(?<![\\dA-Za-z])([A-Za-z])-?(\\d{5,6})(?![\\dA-Za-z])", RegexOption.IGNORE_CASE)
    internal val LONG_NUMBER_PARCEL = Regex("(?<![\\dA-Za-z])(\\d{6,8})(?![\\dA-Za-z])")
    /** 兔喜生活/妈妈驿站式单段码：柜架组号-码（如 5-3858、12-3456）。
     *  注意：必须与 VALID_CODE_FORMATS 全串白名单一致；评分低（60），
     *  同屏存在更强段式码时会被 top×0.75 过滤，避免把 "1-6-5020" 的子串 "6-5020" 误抓成码。 */
    internal val DIGIT_DASH_PARCEL = Regex("(?<![\\dA-Za-z])(\\d{1,2})-(\\d{3,5})(?![\\dA-Za-z])")

    private const val PATTERN_PREFIXED = "PREFIXED_CODE"

    private val EXCLUDE_PATTERNS = listOf(
        Regex("\\b1[3-9]\\d{9}\\b"), Regex("\\b0\\d{2,3}-?\\d{7,8}\\b"),
        Regex("\\d{1,2}:\\d{2}"), Regex("[￥¥\$]\\s*\\d+"), Regex("\\d+\\.?\\d*\\s*[元块]"),
        Regex("\\b\\d{12,}\\b"), Regex("\\b\\d{4}年\\d{1,2}月\\b"),
        Regex("\\d+\\s*[个份件杯张条]"), Regex("\\d+\\s*[分钟小时]"), Regex("\\d+\\s*[号桌台]"),
        Regex("\\d+\\s*[号楼层室]"), Regex("\\d+\\s*[折]"), Regex("\\d+\\s*[分](?![钟])"),
        Regex("\\d+\\s*[毫厘克千克升毫升]"),
        // 容量/重量/尺寸等规格（英文单位后缀），如 120ml / 500ML / 2kg / 15cm — 不是取件码
        Regex("\\b\\d+(?:\\.\\d+)?\\s*(?:ml|ML|mL|l|L|g|kg|mg|cm|mm|km|GB|MB|KB|TB)\\b"),
        Regex("\\b\\d{4}-\\d{1,2}\\b"), // date suffix like 1124-15
        Regex("\\b\\d{6,8}-\\d{5,}\\b"), // full order number
        Regex("\\b[xX]\\d{1,2}\\b"), // shopping cart quantity marker (x1, x2, ...) — not a pickup code
        // --- 参考同类产品干扰排除实现（isInterferenceCode） ---
        // URL 碎片（http/https/ftp 开头的链接残片）
        Regex("https?://\\S*"), Regex("ftp://\\S*"),
        // 域名模式（xxx.com / xxx.cn / xxx.net 等，OCR 常读到的纯数字假域名也在此类）
        Regex("(?:[a-zA-Z0-9-]+\\.)+(?:com|cn|net|org|top|xyz|io|cc|me|tv|edu|gov)(?:/[a-zA-Z0-9-_?=&#.]*)?"),
        // 运单号/快递单号模式（纯数字10-25位，排除被当成取件码的运单号）
        Regex("\\b\\d{10,25}\\b"),
        // 快递品牌 + 码 + 包裹 干扰（如"中通123456包裹"、"顺丰800123包裹(?!位置)"—SmsParser 防误判）
        Regex("(?:中通|圆通|申通|韵达|顺丰|邮政|EMS|极兔|京东|德邦|百世|菜鸟|丰巢)\\s*[A-Za-z0-9\\-]{3,12}\\s*包裹(?!位置)"),
        // 包裹编号/包裹号/包裹# 干扰（如"包裹编号1234"、"包裹*1234"—SmsParser 防误判）
        Regex("包裹(?:编号|号|[*＊:：#])\\s*[A-Za-z0-9\\-]{2,12}"),
        // 车位/车库/号楼/栋/单元 后缀 —— 数字+地点后缀不是取件码（如"42车位"、"2栋"）
        Regex("\\d+\\s*(?:车位|车库|号楼|栋|单元|层|室|户|号院)"),
        // 尾号/运单号/单号 + 数字 干扰（如"运单尾号 6824"、"单号123456"）
        Regex("(?:尾号|运单号|单号|订单号)\\s*[:：]?\\s*\\d+"),
        // 客服电话/400电话（如 400-xxx-xxxx、95338 等）
        Regex("\\b\\d{3,5}-\\d{3,5}-\\d{4}\\b"),
        // 取件时间段（如"8:00-21:00"、"9-21点"）——不是取件码
        Regex("\\d{1,2}[:：]\\d{2}\\s*[-~～]\\s*\\d{1,2}[:：]\\d{2}"),
        // 纯日期（MM-DD 如 08-05）——不是取件码
        Regex("\\b(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\\d|3[01])\\b")
    )

    /**
     * 公开：校验字符串是否为合法取餐/取件码格式（复用全部已知规则）。
     * 供 AI 提取结果过滤噪声（AI 不比正则可靠，需格式白名单把关）。
     * 增强版（参考同类产品实现 isValidPickupCode 的 11 项过滤）：先做格式匹配，
     * 再做内容排除（全零全一/递增序列/4连重复/xxx/手机号/拼音噪声等）。
     * 注意：此为**无上下文**校验，2-3 位纯数字一律拒绝（42/123 裸数字噪声）；
     * 带"取餐码/取件码"强前缀的短码请用 CodeExtractor 的上下文校验路径。
     */
    fun isValidPickupCode(code: String): Boolean {
        val c = code.trim()
        if (c.length !in 1..14) return false
        // 第一步：格式白名单匹配（原有检查）
        if (!VALID_CODE_FORMATS.any { it.matches(c) }) return false
        // 第二步：内容排除（参考同类产品，过滤噪声数字模式）
        return !isContentNoise(c)
    }

    /**
     * 内容噪声检查（与格式无关）：全零全一 / 递增序列 / 4 连重复 / xxx / 手机号子串 / 拼音噪声等。
     * 拆出供强前缀上下文路径复用（取餐码 123 这类短码跳过格式白名单但必须过内容检查）。
     */
    internal fun isContentNoise(code: String): Boolean {
        val c = code.trim()
        val stripped = c.replace("-", "").replace(" ", "")
        val len = stripped.length
        if (len < 2 || len > 20) return true
        // 86 开头手机号子串
        if (stripped.startsWith("86") && stripped.length in 8..13) return true
        // 全 0 或全 1
        if (stripped.all { it == '0' } || stripped.all { it == '1' }) return true
        // 递增数字序列（0123 ~ 7890）——全串精确匹配，不用子串 contains 避免误杀（如 10123 包含 0123）
        if (stripped.length == 4 && INCREMENTING_DIGITS.contains(stripped)) return true
        // 递增字母序列（abcd ~ wxyz）——同样全串精确匹配
        val lower = stripped.lowercase()
        if (lower.length == 4 && INCREMENTING_LETTERS.contains(lower)) return true
        // 含"手机/电话/时间"拼音噪声
        if (listOf("手机", "电话", "时间", "shouji", "dianhua", "shijian").any { lower.contains(it) }) return true
        // 4 连重复字符（aaaa、1111）
        if (Regex("(.)\\1{3,}").containsMatchIn(stripped)) return true
        // xxx 模式（占位/噪声）
        if (Regex("[xX]{3,}").containsMatchIn(stripped)) return true
        return false
    }

    // 合法取件/取餐码格式白名单（与上方解析正则一一对应，去锚点/去分组后用于全串匹配）
    private val VALID_CODE_FORMATS = listOf(
        Regex("[A-Za-z]?\\d{1,2}-\\d{1,2}-\\d{1,2}-\\d{2,4}"), // FOUR_SEGMENT
        Regex("\\d{1,3}-\\d{1,2}-\\d{3,6}"),                       // THREE_SEGMENT
        Regex("[A-Z]-\\d{1,2}-\\d{3,4}", RegexOption.IGNORE_CASE),   // LETTER_TWO_SEGMENT
        Regex("[A-Za-z]-?\\d{5,6}"),                                  // LETTER_DASH_FIVE
        Regex("[A-Za-z]\\d{1,2}-\\d{1,2}-\\d{3,6}", RegexOption.IGNORE_CASE), // LETTER_THREE_SEG
        Regex("[A-Za-z]-\\d{3,4}", RegexOption.IGNORE_CASE),          // LETTER_DASH_THREE
        Regex("\\d{1,2}-\\d{3,5}"),                                     // DIGIT_DASH_PARCEL 兔喜式（5-3858）
        Regex("\\d{6,8}"),                                            // LONG_NUMBER
        Regex("[A-Z]\\s*-?\\s*\\d{2,4}", RegexOption.IGNORE_CASE),  // LETTER_NUMBER_FOOD
        // PURE_NUMBER_FOOD：手动/AI 校验无上下文，收紧为 4-5 位，避免 2-3 位裸数字(42/123)被当合法码
        Regex("\\d{4,5}"),
        // PREFIXED_CODE / PING_CODE 格式：覆盖带前缀上下文的码值（如 取餐码AB12 等）
        // 收紧：必须同时含字母和数字（(?=.*[A-Za-z])(?=.*\d)），纯字母串与纯数字串都不再被 catch-all 放行，
        // 否则 "12/42/123" 这类 2-3 位裸数字会绕过 PURE_NUMBER_FOOD 的"4-5 位"收紧而通过校验。
        Regex("(?=.*[A-Za-z])(?=.*\\d)[A-Za-z0-9\\-]{2,12}")
    )

    /** 递增数字序列：排除 0123 / 1234 ... 7890 */
    private val INCREMENTING_DIGITS = (0..7).map { (it..it + 3).joinToString("") }.toSet()

    /** 递增字母序列：排除 abcd / bcde ... wxyz（大小写均匹配） */
    private val INCREMENTING_LETTERS = ('a'..'w').map { (it..it + 3).joinToString("") }.toSet()

    /**
     * 手动录入校验：与 AI 路径对齐——取餐码允许 2-3 位纯数字（如瑞幸「123」，过内容噪声），
     * 其余类型走全串白名单（取件码仍拒绝 2-3 位裸数字）。
     */
    fun isValidManualCode(code: String, type: String): Boolean {
        val c = code.trim()
        if (c.isEmpty()) return false
        val shortFood = type == CodeExtractor.CodeType.pickup_food.name &&
            c.all { it.isDigit() } && c.length in 2..3
        return if (shortFood) !isContentNoise(c) else isValidPickupCode(c)
    }

    internal fun isExcluded(code: String, context: Context? = null) =
        EXCLUDE_PATTERNS.any { it.containsMatchIn(code) } ||
        !isValidPickupCode(code) ||
        // A3: 用户标记"不是取件码"的可学习排除片段
        PatternLearner.isLearnedExcluded(code, context)

    private val formatPatterns = linkedMapOf(
        "FOUR_SEGMENT_PARCEL" to FOUR_SEGMENT_PARCEL,
        "THREE_SEGMENT_PARCEL" to THREE_SEGMENT_PARCEL,
        "LETTER_TWO_SEGMENT_PARCEL" to LETTER_TWO_SEGMENT_PARCEL,
        "LETTER_DASH_FIVE_PARCEL" to LETTER_DASH_FIVE_PARCEL,
        "LONG_NUMBER_PARCEL" to LONG_NUMBER_PARCEL,
        "DIGIT_DASH_PARCEL" to DIGIT_DASH_PARCEL,
    )

    internal fun classifyFormat(code: String): String {
        for ((id, regex) in formatPatterns) {
            if (regex.matches(code)) return id
        }
        return PATTERN_PREFIXED
    }

    fun getPatternId(code: String): String = classifyFormat(code)
}
