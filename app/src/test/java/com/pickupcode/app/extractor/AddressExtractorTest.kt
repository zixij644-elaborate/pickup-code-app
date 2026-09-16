package com.pickupcode.app.extractor

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

/**
 * Regression tests for AddressExtractor.
 * dedupeRepeated tested via reflection; extractAddress tested through public API.
 */
class AddressExtractorTest {

    // ---------------------------------------------------------------
    // dedupeRepeated — was broken for multi-char CJK (HIGH bug, fixed v1.0.9)
    // ---------------------------------------------------------------

    @Test
    @DisplayName("collpases 3+ repeated multi-char CJK unit")
    fun dedupeRepeated_collapsesMultiCharCjk() {
        assertEquals("长兴路北段", invokeDedupeRepeated("长兴路长兴路长兴路北段"))
        assertEquals("申通快递", invokeDedupeRepeated("申通快递申通快递申通快递"))
    }

    @Test
    @DisplayName("4-char unit repeated 3x is collapsed")
    fun dedupeRepeated_4charUnit() {
        assertEquals("申通快递取", invokeDedupeRepeated("申通快递申通快递申通快递取"))
    }

    @Test
    @DisplayName("2-char unit repeated 3x is collapsed")
    fun dedupeRepeated_2charUnit() {
        assertEquals("快递", invokeDedupeRepeated("快递快递快递"))
    }

    @Test
    @DisplayName("single-char repeat NOT collapsed (algorithm starts at len=2)")
    fun dedupeRepeated_singleChar() {
        assertEquals("路路路", invokeDedupeRepeated("路路路"))
    }

    @Test
    @DisplayName("double repeat preserved (not 3+)")
    fun dedupeRepeated_keepsDoubleRepeat() {
        assertEquals("长兴路路", invokeDedupeRepeated("长兴路路"))
    }

    @Test
    @DisplayName("empty string returns empty")
    fun dedupeRepeated_empty() {
        assertEquals("", invokeDedupeRepeated(""))
    }

    @Test
    @DisplayName("no repetition returns original")
    fun dedupeRepeated_noRepetition() {
        assertEquals("长兴路北段老李超市旁边",
            invokeDedupeRepeated("长兴路北段老李超市旁边"))
    }

    private fun invokeDedupeRepeated(s: String): String {
        val method = AddressExtractor::class.java.getDeclaredMethod("dedupeRepeated", String::class.java)
        method.isAccessible = true
        return method.invoke(AddressExtractor, s) as String
    }

    // ---------------------------------------------------------------
    // extractAddress — integration tests
    // ---------------------------------------------------------------

    @Test
    @DisplayName("finds address from 到…取件 pattern (S6)")
    fun extractAddress_afterToPattern() {
        val text = "凭281849到长兴路北段老李超市旁边2号柜取您的快递"
        val addr = extract(text)
        assertTrue(addr.contains("长兴路"), "S6 should find address, got: $addr")
    }

    @Test
    @DisplayName("finds address from 已放至 pattern (S5)")
    fun extractAddress_placedPhrase() {
        val text = "已放至长兴路北段菜鸟驿站，凭码取件"
        val addr = extract(text)
        assertTrue(addr.contains("长兴路"), "S5 should find address, got: $addr")
    }

    @Test
    @DisplayName("finds address from explicit label (S0)")
    fun extractAddress_explicitLabel() {
        val text = "取件地址: 长兴路北段老李超市旁边"
        val addr = extract(text)
        assertTrue(addr.contains("长兴路"), "S0 should find address, got: $addr")
    }

    @Test
    @DisplayName("fallback for line with road indicator (S10)")
    fun extractAddress_s10fallback() {
        val text = "长兴路北段老李超市旁边"
        val addr = extract(text)
        assertTrue(addr.contains("长兴路"), "S10 fallback should find, got: $addr")
    }

    @Test
    @DisplayName("returns empty for text with no address indicators")
    fun extractAddress_noAddressIndicators() {
        val text = "请取件"
        val addr = extract(text)
        assertTrue(addr.isEmpty() || !addr.any { it in '\u4e00'..'\u9fff' },
            "Should return empty or non-CJK for non-address, got: $addr")
    }

    private fun extract(text: String): String {
        val lines = text.lines()
            .mapIndexed { i, line ->
                com.pickupcode.app.ocr.OCREngine.TextLine(
                    text = line.trim(),
                    boundingBox = com.pickupcode.app.ocr.OCREngine.LineBox(0, i * 30, 500, (i + 1) * 30),
                    confidence = 1.0f
                )
            }
            .filter { it.text.isNotBlank() }
        if (lines.isEmpty()) return ""
        val allText = lines.joinToString(" ") { it.text }
        return AddressExtractor.extractAddress(lines, allText)
    }

    // ---------------------------------------------------------------
    // resolveAddress — 竞争仲裁（单码误伤回归：2026-08-21 模拟器实测 DB 落空复现）
    // ---------------------------------------------------------------

    /** 复现输入：整行含「包裹」被窗口 P3 排除（perCodeAddr 为空），全屏仅 S9 括号命中「建设南路」。 */
    private fun parenReproLines(): List<com.pickupcode.app.ocr.OCREngine.TextLine> = listOf(
        com.pickupcode.app.ocr.OCREngine.TextLine(
            text = "取件码281849，您的包裹已暂存（建设南路），请及时领取",
            boundingBox = null,
            confidence = 1.0f
        )
    )

    @Test
    @DisplayName("S9 括号地址属低置信来源（仲裁 gate 的事实前提）")
    fun resolveAddress_parenSourceIsLowConfidence() {
        val ls = parenReproLines()
        val allText = ls.joinToString(" ") { it.text }
        assertEquals("建设南路", AddressExtractor.extractAddress(ls, allText), "全屏应提取到地址")
        assertFalse(AddressExtractor.isHighConfidenceFullAddress(ls, allText), "S9-paren 不在高置信来源集")
        assertTrue(AddressExtractor.extractAddressForCode(ls, "281849").isBlank(), "窗口不应命中（整行含排除词）")
    }

    @Test
    @DisplayName("single-code screen keeps low-confidence full address (regression)")
    fun resolveAddress_singleCodeKeepsFallback() {
        val ls = parenReproLines()
        val allText = ls.joinToString(" ") { it.text }
        val addr = AddressExtractor.resolveAddress(ls, allText, "", "建设南路", multiCodeOnScreen = false)
        assertEquals("建设南路", addr, "单码同屏不得丢弃全屏兜底地址")
    }

    @Test
    @DisplayName("multi-code screen still suppresses low-confidence full address")
    fun resolveAddress_multiCodeSuppressesLowConfidence() {
        val ls = parenReproLines()
        val allText = ls.joinToString(" ") { it.text }
        val addr = AddressExtractor.resolveAddress(ls, allText, "", "建设南路", multiCodeOnScreen = true)
        assertEquals("", addr, "多码同屏低置信来源宁缺毋滥")
    }

    @Test
    @DisplayName("multi-code screen keeps high-confidence full address")
    fun resolveAddress_multiCodeKeepsHighConfidence() {
        val ls = listOf(
            com.pickupcode.app.ocr.OCREngine.TextLine("取件码281849", null, 1.0f),
            com.pickupcode.app.ocr.OCREngine.TextLine("已放至建设南路菜鸟驿站", null, 1.0f)
        )
        val allText = ls.joinToString(" ") { it.text }
        val full = AddressExtractor.extractAddress(ls, allText)
        assertTrue(full.contains("建设南路"), "S5 应提取到地址, got: $full")
        val addr = AddressExtractor.resolveAddress(ls, allText, "", full, multiCodeOnScreen = true)
        assertEquals(full, addr, "多码同屏高置信来源照常采信")
    }

    @Test
    @DisplayName("window address always wins over full-screen fallback")
    fun resolveAddress_windowAlwaysWins() {
        val ls = parenReproLines()
        val allText = ls.joinToString(" ") { it.text }
        val addr = AddressExtractor.resolveAddress(ls, allText, "长兴路25号", "建设南路", multiCodeOnScreen = false)
        assertEquals("长兴路25号", addr)
    }
}
