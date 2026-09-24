package com.pickupcode.app.learner

import com.pickupcode.app.extractor.CodeExtractor
import com.pickupcode.app.extractor.CodeValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 「识别规则」管理的回归测试。
 *
 * 这里只覆盖**无 Android 依赖的纯逻辑**（校验、JSON 编解码、内置表不变量、格式 id 一致性）——
 * 项目单测是纯 JVM JUnit5，不带 Robolectric，凡是需要 Context 的存储读写都在真机/界面上验证。
 */
class RuleManagementTest {

    // ---------------------------------------------------------------
    // 正则校验
    // ---------------------------------------------------------------

    @Test
    @DisplayName("空正则被拒绝")
    fun emptyRejected() {
        val r = PatternLearner.validateRegex("   ")
        assertFalse(r.ok)
        assertNotNull(r.error)
    }

    @Test
    @DisplayName("语法错误的正则被拒绝，并带上可读原因")
    fun invalidSyntaxRejected() {
        val r = PatternLearner.validateRegex("([A-Z")
        assertFalse(r.ok, "括号不闭合必须被拒")
        assertTrue(r.error!!.contains("语法"), "错误文案应说明是语法问题：${r.error}")
    }

    @Test
    @DisplayName("过宽正则被拒绝（否则识别会退化成「什么都当取件码」）")
    fun overBroadRejected() {
        listOf(".*", ".+", "\\d*", "\\w*", "(.*)", "[\\s\\S]*").forEach { p ->
            val r = PatternLearner.validateRegex(p)
            assertFalse(r.ok, "「$p」必须被拒")
        }
    }

    @Test
    @DisplayName("缺少边界的正则可保存但要给警告")
    fun missingBoundaryWarns() {
        val r = PatternLearner.validateRegex("\\d{5}")
        assertTrue(r.ok, "能编译、不过宽 → 允许保存")
        assertNotNull(r.warning, "应提示补边界")
        assertTrue(r.warning!!.contains("边界"), r.warning)
    }

    @Test
    @DisplayName("带环视边界的正常正则：通过且无警告")
    fun wellFormedRegexOk() {
        val r = PatternLearner.validateRegex("(?<![\\dA-Za-z])\\d{5}(?![\\dA-Za-z])")
        assertTrue(r.ok)
        assertNull(r.warning, "不该有多余警告：${r.warning}")
    }

    @Test
    @DisplayName("能匹配空串的正则给出警告（会产生空候选）")
    fun emptyMatchingWarns() {
        val r = PatternLearner.validateRegex("^\\d*$")
        assertTrue(r.ok)
        assertNotNull(r.warning)
    }

    // ---------------------------------------------------------------
    // 内置覆盖的 JSON 编解码（存储格式的兼容与容错）
    // ---------------------------------------------------------------

    @Test
    @DisplayName("停用集合编解码往返一致")
    fun disabledRoundTrip() {
        val src = setOf("PING_CODE", "LONG_NUMBER_PARCEL")
        assertEquals(src, PatternLearner.decodeDisabled(PatternLearner.encodeDisabled(src)))
    }

    @Test
    @DisplayName("正则改写表编解码往返一致")
    fun regexEditsRoundTrip() {
        val src = mapOf("THREE_SEGMENT_PARCEL" to "\\d{3}-\\d{3}", "PING_CODE" to "凭(\\S+)")
        assertEquals(src, PatternLearner.decodeRegexEdits(PatternLearner.encodeRegexEdits(src)))
    }

    @Test
    @DisplayName("损坏/空/缺失的存储值一律退化为空，不抛异常")
    fun corruptStorageDegradesToEmpty() {
        assertTrue(PatternLearner.decodeDisabled(null).isEmpty())
        assertTrue(PatternLearner.decodeDisabled("").isEmpty())
        assertTrue(PatternLearner.decodeDisabled("这不是 JSON").isEmpty())
        assertTrue(PatternLearner.decodeDisabled("{\"a\":1}").isEmpty(), "对象塞进数组位置应退化")
        assertTrue(PatternLearner.decodeRegexEdits(null).isEmpty())
        assertTrue(PatternLearner.decodeRegexEdits("[[[").isEmpty())
        assertTrue(PatternLearner.decodeRegexEdits("[1,2]").isEmpty(), "数组塞进对象位置应退化")
    }

    // ---------------------------------------------------------------
    // 全角 → 半角归一化（真机发现：中文输入法把 [ ] 打成 【 】，存进去就是错的）
    // ---------------------------------------------------------------

    @Test
    @DisplayName("中文输入法的全角括号被归一化（ZQ【9】 → ZQ[9]）")
    fun fullWidthBracketsNormalized() {
        // 真机实测：在输入框里打 ZQ[0-9] 会被输入法变成 ZQ【9】（连 - 都没了），
        // 这里至少保证全角方括号/圆括号/花括号能救回来
        assertEquals("ZQ[0-9]", PatternLearner.normalizeRegexInput("ZQ【0-9】"))
        assertEquals("(\\d{4})", PatternLearner.normalizeRegexInput("（＼d｛4｝）"))
        assertEquals("[]", PatternLearner.normalizeRegexInput("［］"))
    }

    @Test
    @DisplayName("全角数字/字母/符号归一化，半角内容原样保留")
    fun fullWidthBasicsNormalized() {
        assertEquals("A1-2", PatternLearner.normalizeRegexInput("Ａ１－２"))
        assertEquals("abc", PatternLearner.normalizeRegexInput("ａｂｃ"))
        assertEquals(".*+?|^$", PatternLearner.normalizeRegexInput("．＊＋？｜＾＄"))
        // 已经是半角的原样返回（不得改变用户输入）
        val ascii = "(?<![\\dA-Za-z])ZQ[0-9]{6}(?![\\dA-Za-z])"
        assertEquals(ascii, PatternLearner.normalizeRegexInput(ascii))
    }

    @Test
    @DisplayName("归一化后的正则能通过校验；含全角的输入不再被误判为非法")
    fun fullWidthInputPassesValidation() {
        // 全角输入经归一化后应当可编译
        val r = PatternLearner.validateRegex("ZQ【0-9】【0-9】")
        assertTrue(r.ok, "归一化后应可保存：${r.error}")
    }

    // ---------------------------------------------------------------
    // 内置规则表的不变量
    // ---------------------------------------------------------------

    @Test
    @DisplayName("内置规则 id 唯一、非空（id 是用户覆盖的键，重复会导致覆盖错位）")
    fun builtinIdsUnique() {
        val ids = CodeExtractor.builtinRuleInfos(null).map { it.id }
        assertEquals(ids.size, ids.toSet().size, "存在重复 id：$ids")
        assertTrue(ids.none { it.isBlank() })
    }

    @Test
    @DisplayName("默认状态下全部内置规则启用、且都没有被改写")
    fun builtinDefaultsAllEnabled() {
        val infos = CodeExtractor.builtinRuleInfos(null)
        assertTrue(infos.isNotEmpty())
        assertTrue(infos.all { it.enabled }, "出厂状态不应有停用项")
        assertTrue(infos.all { !it.overridden }, "出厂状态不应有改写项")
        assertTrue(infos.all { it.regex.isNotBlank() }, "每条都要有可展示的正则")
    }

    @Test
    @DisplayName("依赖捕获组的特殊模式标记为不可改写（只为防用户改坏导致崩溃）")
    fun specialRulesNotEditable() {
        val infos = CodeExtractor.builtinRuleInfos(null).associateBy { it.id }
        listOf("PREFIXED_CODE", "NEXT_LINE_CODE", "LABEL_FOR_CODE", "PING_CODE", "COUPON_NUMBER").forEach { id ->
            val info = infos[id]
            assertNotNull(info, "缺少特殊模式 $id")
            assertFalse(info!!.editable, "$id 依赖捕获组，不应允许改写成任意正则")
        }
        // 评分类规则必须可改写
        assertTrue(infos["THREE_SEGMENT_PARCEL"]!!.editable)
        assertTrue(infos["PURE_NUMBER_FOOD"]!!.editable)
    }

    @Test
    @DisplayName("统计用的格式 id 与内置规则表保持一致（否则统计标签和规则管理会对不上）")
    fun formatIdsCoveredByBuiltinTable() {
        val builtinIds = CodeExtractor.builtinRuleInfos(null).map { it.id }.toSet()
        // classifyFormat 能产出的 id 必须都能在内置表里找到
        listOf(
            "3-7-4162" to "THREE_SEGMENT_PARCEL",
            "A1-2-3-45" to "FOUR_SEGMENT_PARCEL",
            "A-1-234" to "LETTER_TWO_SEGMENT_PARCEL",
            "H-24137" to "LETTER_DASH_FIVE_PARCEL",
            "123456" to "LONG_NUMBER_PARCEL",
            "7-2914" to "DIGIT_DASH_PARCEL"
        ).forEach { (code, expectedId) ->
            val actual = CodeValidator.classifyFormat(code)
            assertEquals(expectedId, actual, "「$code」的格式 id 变了")
            assertTrue(actual in builtinIds, "格式 id $actual 不在内置规则表里，统计标签会失配")
        }
        // 兜底分支的 id 也要在表里
        assertTrue(CodeValidator.classifyFormat("取件码ABC") in builtinIds, "兜底 id 不在内置表里")
    }

    // ---------------------------------------------------------------
    // 用户确认通道：比"同形状出现 3 次"强得多的正面证据 → 1 条即成规
    // ---------------------------------------------------------------

    @Test
    @DisplayName("用户确认的码，内置抓不到 → 立刻建规则（不需要累计 3 次）")
    fun confirmedCodeUncoveredByBuiltinBecomesRule() {
        val d = PatternLearner.decideVerified("ZQ123456", emptySet(), builtinCovers = false)
        assertTrue(d is PatternLearner.VerifiedDecision.Add, "应建规则，实际=$d")
        val add = d as PatternLearner.VerifiedDecision.Add
        // 与自动学习同一套边界约定：显式环视，不用 \b（\b 在中文邻接下会漏抓）
        assertTrue(add.regex.startsWith("(?<![\\dA-Za-z])"), add.regex)
        assertTrue(add.regex.endsWith("(?![\\dA-Za-z])"), add.regex)
        assertTrue(
            Regex(add.regex).containsMatchIn("取件码ZQ123456到长兴路"),
            "码值紧贴中文时必须命中：${add.regex}"
        )
    }

    @Test
    @DisplayName("内置正则本来就能抓到的形状：不学（学了也用不上，只会堆垃圾规则）")
    fun confirmedCodeCoveredByBuiltinIsSkipped() {
        assertSame(
            PatternLearner.VerifiedDecision.Skip,
            PatternLearner.decideVerified("3-7-4162", emptySet(), builtinCovers = true)
        )
        assertSame(
            PatternLearner.VerifiedDecision.Skip,
            PatternLearner.decideVerified("ZQ123456", emptySet(), builtinCovers = true),
            "即使形状新，只要内置已覆盖也不该建规则"
        )
    }

    @Test
    @DisplayName("已有同形自定义规则：只续命，不重复建")
    fun confirmedCodeMatchingExistingRuleRefreshes() {
        val add = PatternLearner.decideVerified("ZQ123456", emptySet(), false)
            as PatternLearner.VerifiedDecision.Add
        // 同形状的另一个码值 → 应命中同一条规则（说明 tokenize 的泛化生效）
        val again = PatternLearner.decideVerified("ZQ999888", setOf(add.regex), builtinCovers = false)
        assertTrue(again is PatternLearner.VerifiedDecision.Refresh, "应续命，实际=$again")
        assertEquals(add.regex, (again as PatternLearner.VerifiedDecision.Refresh).regex)
    }

    @Test
    @DisplayName("不可用的值一律不学：含 X（任意字符）的 token、过短、过长、空白")
    fun unusableConfirmedValuesAreSkipped() {
        listOf("#-1234", "A", "1234567890123456789012", "   ").forEach {
            assertSame(
                PatternLearner.VerifiedDecision.Skip,
                PatternLearner.decideVerified(it, emptySet(), builtinCovers = false),
                "「$it」不该成规"
            )
        }
    }

    @Test
    @DisplayName("builtinCovers：内置能抓的为 true，抓不到的为 false")
    fun builtinCoversDetectsCoveredShapes() {
        assertTrue(CodeExtractor.builtinCovers("3-7-4162"), "三段式应被内置覆盖")
        assertTrue(CodeExtractor.builtinCovers("7-2914"), "单段式应被内置覆盖")
        assertFalse(CodeExtractor.builtinCovers("ZQ123456"), "字母+6位数字内置抓不到")
        assertFalse(CodeExtractor.builtinCovers(""), "空串不算覆盖")
    }
}
