package com.pickupcode.app.corpus

import com.pickupcode.app.extractor.CodeExtractor
import com.pickupcode.app.ocr.OCREngine
import java.io.File

/** 期望的码（码值 + 类型）。 */
data class ExpectedCode(val code: String, val type: CodeExtractor.CodeType)

/** 每码期望的窗口地址断言（多码同屏场景）。 */
data class ExpectedCodeAddress(val code: String, val address: String)

/** 每码期望的品牌/来源断言（多码同屏的品牌归属场景）。 */
data class ExpectedCodeSource(val code: String, val source: String)

/**
 * 一条 corpus 回归语料。文本格式见 [RecognitionDebugStore.exportFixture] 的 KDoc。
 *
 * 格式要点：
 * - `# id/source/screen/note/allow-extra` 元数据
 * - `L <x|-> <y|-> <w|-> <h|-> <conf|-> <行文本...>`：OCR 行（坐标为 `-` 表示无 boundingBox）
 * - `E code <type> <码值>`：期望识别到的码（type = pickup_food/pickup_parcel/coupon）
 * - `E forbid <串>`：绝不应被当成码的串
 * - `E address/station/cabinet/from <值>`：全屏地址管线断言
 * - `E codeaddr <码值> <地址片段>`：按码窗口地址断言
 * - `E codesource <码值> <品牌>`：按码品牌/来源断言（多码同框的品牌归属）
 */
data class CorpusFixture(
    val id: String,
    val source: String,
    val screenHeight: Int,
    val note: String,
    val allowExtraCodes: Boolean,
    val lines: List<OCREngine.TextLine>,
    val expectedCodes: List<ExpectedCode>,
    val forbiddenCodes: List<String>,
    val expectedAddress: String?,
    val expectedStation: String?,
    val expectedCabinet: String?,
    val expectedFrom: String?,
    val expectedCodeAddresses: List<ExpectedCodeAddress>,
    val expectedCodeSources: List<ExpectedCodeSource>,
    val file: String
) {
    val allText: String get() = lines.joinToString(" ") { it.text }

    companion object {

        fun parse(file: File): CorpusFixture {
            var id = file.nameWithoutExtension
            var source = "screen"
            var screenHeight = 0
            var note = ""
            var allowExtra = false
            val lines = mutableListOf<OCREngine.TextLine>()
            val codes = mutableListOf<ExpectedCode>()
            val forbid = mutableListOf<String>()
            val codeAddrs = mutableListOf<ExpectedCodeAddress>()
            val codeSources = mutableListOf<ExpectedCodeSource>()
            var address: String? = null
            var station: String? = null
            var cabinet: String? = null
            var from: String? = null

            file.readLines().forEachIndexed { idx, raw ->
                val line = raw.trimEnd('\r')
                val n = idx + 1
                if (line.isBlank()) return@forEachIndexed
                if (line.startsWith("#")) {
                    val body = line.removePrefix("#").trim()
                    val key = body.substringBefore(':', "").trim()
                    val value = body.substringAfter(':', "").trim()
                    when (key) {
                        "id" -> id = value
                        "source" -> source = value
                        "screen" -> screenHeight = value.toIntOrNull() ?: 0
                        "note" -> note = value
                        "allow-extra" -> allowExtra = value.equals("true", true)
                    }
                    return@forEachIndexed
                }
                when {
                    line.startsWith("L ") -> {
                        val p = line.split(" ", limit = 7)
                        require(p.size >= 7) { "${file.name}:$n L 行缺字段（需 7 段：L x y w h conf text）: $line" }
                        val box = if (p[1] == "-") null else {
                            val x = p[1].toIntOrNull(); val y = p[2].toIntOrNull()
                            val w = p[3].toIntOrNull(); val h = p[4].toIntOrNull()
                            require(x != null && y != null && w != null && h != null) {
                                "${file.name}:$n L 行坐标非法: $line"
                            }
                            OCREngine.LineBox(x, y, x + w, y + h)
                        }
                        val conf = p[5].toFloatOrNull()
                        lines.add(OCREngine.TextLine(p[6], box, conf))
                    }
                    line.startsWith("E ") -> {
                        val p = line.split(" ", limit = 3)
                        require(p.size >= 2) { "${file.name}:$n E 行缺字段: $line" }
                        when (p[1]) {
                            "code" -> {
                                val q = line.split(" ", limit = 4)
                                require(q.size == 4) { "${file.name}:$n E code 需 4 段: $line" }
                                codes.add(ExpectedCode(q[3], parseType(q[2], file.name, n)))
                            }
                            "forbid" -> { require(p.size == 3); forbid.add(p[2]) }
                            "address" -> { require(p.size == 3); address = p[2] }
                            "station" -> { require(p.size == 3); station = p[2] }
                            "cabinet" -> { require(p.size == 3); cabinet = p[2] }
                            "from" -> { require(p.size == 3); from = p[2] }
                            "codeaddr" -> {
                                val q = line.split(" ", limit = 4)
                                require(q.size == 4) { "${file.name}:$n E codeaddr 需 4 段: $line" }
                                codeAddrs.add(ExpectedCodeAddress(q[2], q[3]))
                            }
                            "codesource" -> {
                                val q = line.split(" ", limit = 4)
                                require(q.size == 4) { "${file.name}:$n E codesource 需 4 段: $line" }
                                codeSources.add(ExpectedCodeSource(q[2], q[3]))
                            }
                            else -> error("${file.name}:$n 未知 E 指令: ${p[1]}")
                        }
                    }
                    else -> error("${file.name}:$n 无法解析的行（需以 # / L / E 开头）: $line")
                }
            }
            require(lines.isNotEmpty()) { "${file.name}: 无 OCR 行" }
            return CorpusFixture(
                id = id, source = source, screenHeight = screenHeight, note = note,
                allowExtraCodes = allowExtra, lines = lines, expectedCodes = codes,
                forbiddenCodes = forbid, expectedAddress = address, expectedStation = station,
                expectedCabinet = cabinet, expectedFrom = from, expectedCodeAddresses = codeAddrs,
                expectedCodeSources = codeSources,
                file = file.name
            )
        }

        private fun parseType(s: String, fileName: String, lineNo: Int): CodeExtractor.CodeType =
            runCatching { CodeExtractor.CodeType.valueOf(s) }.getOrElse {
                error("$fileName:$lineNo 未知码类型 '$s'（应为 pickup_food/pickup_parcel/coupon）")
            }

        /** 载入目录下全部 .txt 语料，按 id 排序保证测试顺序稳定。 */
        fun loadAll(dir: File): List<CorpusFixture> {
            require(dir.isDirectory) { "corpus 目录不存在: ${dir.absolutePath}" }
            // known-*.txt 是清单文件（known-failures.txt），不是语料
            return dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") && !f.name.startsWith("known-") }
                ?.sortedBy { it.name }
                ?.map { parse(it) }
                ?: emptyList()
        }
    }
}
