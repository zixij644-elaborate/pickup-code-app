package com.pickupcode.app.corpus

import com.pickupcode.app.extractor.CodeExtractor
import com.pickupcode.app.extractor.RecognitionDebugStore
import com.pickupcode.app.ocr.OCREngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 采集链路往返测试：识别快照 → 导出语料文本 → 语料解析器。
 * 保证「设置页导出语料」产出的文件真的能被 CorpusRegressionTest 消费，
 * 而不是等人手工发现格式对不上。
 */
class ExportRoundTripTest {

    @Test
    fun exportedFixtureIsParsable() {
        RecognitionDebugStore.clear()
        val lines = listOf(
            OCREngine.TextLine("取件码", OCREngine.LineBox(100, 900, 300, 960), 0.95f),
            OCREngine.TextLine("3-7-4162", OCREngine.LineBox(100, 980, 420, 1068), 0.96f),
            OCREngine.TextLine("凭3-7-4162到长兴路北段店取您的快递", OCREngine.LineBox(100, 1100, 1000, 1148), 0.9f)
        )
        val allText = lines.joinToString(" ") { it.text }
        val results = CodeExtractor.extract(lines, 2400, context = null, source = "screen")

        RecognitionDebugStore.capture(
            lines = lines,
            candidates = emptyList(),
            allText = allText,
            source = "screen",
            screenHeight = 2400,
            finalResults = results.map {
                RecognitionDebugStore.CandidateInfo(
                    code = it.code, score = it.confidence * 100, type = it.type.name,
                    source = it.source, lineIndex = 1, context = "3-7-4162"
                )
            }
        )
        RecognitionDebugStore.captureAddress(
            RecognitionDebugStore.AddressInfo("长兴路北段店", "未知站点", "2号柜", "S6a")
        )

        val text = RecognitionDebugStore.exportFixture()
        assertNotNull(text, "有快照时必须能导出")
        assertTrue(text!!.contains("E code pickup_parcel 3-7-4162"), "导出应含期望码行:\n$text")

        val tmp = File.createTempFile("export-roundtrip", ".txt")
        try {
            tmp.writeText(text)
            val parsed = CorpusFixture.parse(tmp)
            assertEquals("screen", parsed.source)
            assertEquals(2400, parsed.screenHeight)
            assertEquals(3, parsed.lines.size, "OCR 行应完整往返")
            assertEquals(OCREngine.LineBox(100, 900, 300, 960), parsed.lines[0].boundingBox, "坐标应无损往返")
            assertEquals(listOf("3-7-4162"), parsed.expectedCodes.map { it.code })
            assertEquals(CodeExtractor.CodeType.pickup_parcel, parsed.expectedCodes[0].type)
            assertEquals("长兴路北段店", parsed.expectedAddress)
            assertEquals("2号柜", parsed.expectedCabinet)
            assertEquals("S6a", parsed.expectedFrom)
        } finally {
            tmp.delete()
            RecognitionDebugStore.clear()
        }
    }
}
