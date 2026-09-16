package com.pickupcode.app.extractor

import com.pickupcode.app.ocr.OCREngine
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 卡片窗口判定（[AddressExtractor.isLineInCodeWindow]）。
 *
 * 用途：多码同屏时，预存地址只作用于"命中关键词那一行所属的码" ——
 * 这条判断错了，要么把一条地址套到所有码上（串台），要么预存地址永不生效。
 * 与 [AddressExtractor.extractAddressForCode] 共用同一套窗口规则。
 */
class CodeCardWindowTest {

    /** y 越大越靠下；height 固定 40，便于算 y 间距。 */
    private fun line(text: String, top: Int?) =
        OCREngine.TextLine(
            text = text,
            boundingBox = top?.let { OCREngine.LineBox(left = 0, top = it, right = 600, bottom = it + 40) },
            confidence = null
        )

    @Test
    @DisplayName("码行 ±3 行内的行：算同一张卡片")
    fun linesWithinThreeRows() {
        val lines = listOf(
            line("其他通知", 100),
            line("取件码 1-2-3456", 200),
            line("长兴路北段菜鸟驿站", 240),
            line("请及时取件", 280)
        )
        assertTrue(AddressExtractor.isLineInCodeWindow(lines, "1-2-3456", 2), "下方紧邻行应属本卡")
        assertTrue(AddressExtractor.isLineInCodeWindow(lines, "1-2-3456", 3))
        assertTrue(AddressExtractor.isLineInCodeWindow(lines, "1-2-3456", 0), "±3 行内也算")
    }

    @Test
    @DisplayName("超出 ±3 行：不算同一张卡片")
    fun linesBeyondThreeRows() {
        val lines = (0..8).map { line("行$it", 100 + it * 40) }.toMutableList()
        lines[4] = line("取件码 9-9-9001", 260)
        assertFalse(AddressExtractor.isLineInCodeWindow(lines, "9-9-9001", 0), "4 行之外")
        assertFalse(AddressExtractor.isLineInCodeWindow(lines, "9-9-9001", 8))
    }

    @Test
    @DisplayName("行号虽近但 y 距离 >400px：不算同一张卡片（多驿站通知的典型串台场景）")
    fun yGapTooLarge() {
        val lines = listOf(
            line("取件码 1-1-1111", 100),
            line("另一个驿站的地址", 700)   // 行差 1，y 差 600
        )
        assertFalse(AddressExtractor.isLineInCodeWindow(lines, "1-1-1111", 1))
    }

    @Test
    @DisplayName("码行自身不算窗口内；无 boundingBox 时按行号判断")
    fun edgeCases() {
        val lines = listOf(
            line("取件码 2-2-2222", 100),
            line("无坐标地址行", null)
        )
        assertFalse(AddressExtractor.isLineInCodeWindow(lines, "2-2-2222", 0), "码行自身不算")
        assertTrue(AddressExtractor.isLineInCodeWindow(lines, "2-2-2222", 1), "缺坐标时退回行号判断")
    }

    @Test
    @DisplayName("码不在文本里 / 行号越界：返回 false（不误伤）")
    fun notFoundOrOutOfRange() {
        val lines = listOf(line("取件码 3-3-3333", 100), line("地址", 140))
        assertFalse(AddressExtractor.isLineInCodeWindow(lines, "9-9-9999", 1), "码不存在")
        assertFalse(AddressExtractor.isLineInCodeWindow(lines, "3-3-3333", 99), "行号越界")
        assertFalse(AddressExtractor.isLineInCodeWindow(emptyList(), "3-3-3333", 0))
    }
}
