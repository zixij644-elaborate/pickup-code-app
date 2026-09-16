package com.pickupcode.app.learner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 自动学习规则生成/迁移的回归测试。
 * tokenize/tokenToRegex 为 private，用反射访问（与 AddressExtractorTest 同做法）。
 */
class PatternLearnerTest {

    private fun invokePrivate(name: String, arg: String): String {
        val m = PatternLearner::class.java.getDeclaredMethod(name, String::class.java)
        m.isAccessible = true
        return m.invoke(PatternLearner, arg) as String
    }

    private fun tokenize(s: String) = invokePrivate("tokenize", s)
    private fun tokenToRegex(tok: String) = invokePrivate("tokenToRegex", tok)

    @Test
    @DisplayName("tokenize 把码值折叠成字符类 run（1-6-5020 → d-d-d4）")
    fun tokenizeCollapsesRuns() {
        assertEquals("d-d-d4", tokenize("1-6-5020"))
        assertEquals("L-d5", tokenize("D-06003"))
    }

    @Test
    @DisplayName("自动生成正则用显式环视边界，不再用 \b（ICU 下 \b 对中文失效）")
    fun generatedRegexUsesLookarounds() {
        val re = tokenToRegex("d-d-d4")
        assertFalse(re.contains("\\b"), "不得再生成 \\b 边界: $re")
        assertTrue(re.startsWith("(?<![\\dA-Za-z])"), re)
        assertTrue(re.endsWith("(?![\\dA-Za-z])"), re)

        val regex = Regex(re)
        assertTrue(regex.containsMatchIn("1-6-5020"))
        // 历史缺陷：\b 边界下码值紧贴中文时漏抓
        assertTrue(regex.containsMatchIn("取件码1-6-5020到长兴路"), "紧贴中文必须命中: $re")
    }

    @Test
    @DisplayName("旧规则首尾 \b 迁移为环视边界，中间的 \b 不动，且迁移幂等")
    fun migrateBoundaryOnlyTouchesEnds() {
        assertEquals(
            "(?<![\\dA-Za-z])\\d-\\d-\\d{4}(?![\\dA-Za-z])",
            PatternLearner.migrateBoundary("\\b\\d-\\d-\\d{4}\\b")
        )
        assertEquals("a\\bb", PatternLearner.migrateBoundary("a\\bb"), "中间的 \\b 不得改写")
        val once = PatternLearner.migrateBoundary("\\b\\d{6}\\b")
        assertEquals(once, PatternLearner.migrateBoundary(once), "迁移必须幂等")
    }
}
