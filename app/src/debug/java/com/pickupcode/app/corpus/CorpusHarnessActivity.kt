package com.pickupcode.app.corpus

import android.app.Activity
import android.content.ContentUris
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import com.pickupcode.app.extractor.AddressExtractor
import com.pickupcode.app.extractor.CodeExtractor
import com.pickupcode.app.extractor.CouponDetector
import com.pickupcode.app.extractor.RecognitionDebugStore
import com.pickupcode.app.extractor.SavedAddressMatcher
import com.pickupcode.app.learner.SavedAddressStore
import com.pickupcode.app.ocr.OCREngine
import com.pickupcode.app.util.ImageUtils
import com.pickupcode.app.util.SensitivePageGuard
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 真机语料采集器 —— **仅存在于 debug 源集**（release 包不含本类）。
 *
 * 从相册某个分组（默认「取件码」）读全部图片，逐张过一遍**与线上一致的识别链路**：
 * [ImageUtils.decodeSampledBitmap]（同分享路径的降采样 + EXIF 旋转，输入是 MediaStore content URI，
 * 与用户从相册分享图片的路径完全相同）→ [OCREngine.recognize] → [CouponDetector.detect]
 * → [CodeExtractor.extract] → [AddressExtractor.extractLocation] /
 * [AddressExtractor.extractAddressFromStores] / [AddressExtractor.extractCabinetNumber]
 * → [SensitivePageGuard.isIdentityCodePage]，最后按 [RecognitionDebugStore.exportFixture] 的格式导出语料。
 *
 * **副作用为零**：不落库、不发通知、不保存截图、不写 PatternLearner（`context = null`，
 * 与既有 corpus 单测口径一致）；只读用户的「预存地址」用于复现地址优先级。
 *
 * 用法（本机 adb 不在 PATH，需用完整路径）：
 * ```
 * adb shell pm grant com.pickupcode.app android.permission.READ_MEDIA_IMAGES
 * adb shell am start -n com.pickupcode.app/com.pickupcode.app.corpus.CorpusHarnessActivity \
 *     --es bucket 取件码
 * adb pull /sdcard/Android/data/com.pickupcode.app/files/corpus-out <本地目录>
 * ```
 * 日志标记：`CORPUS START n=..` / 每张 `CORPUS i/N <文件> lines=.. codes=.. addr=.. from=..` / `CORPUS ALLDONE n=..`
 */
class CorpusHarnessActivity : Activity() {

    companion object {
        private const val TAG = "CorpusHarness"
        private const val DEFAULT_BUCKET = "取件码"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bucket = intent?.getStringExtra("bucket")?.takeIf { it.isNotBlank() } ?: DEFAULT_BUCKET
        Thread {
            var done = 0
            try {
                done = runBlocking { harvest(bucket) }
            } catch (t: Throwable) {
                Log.e(TAG, "CORPUS FAILED", t)
            } finally {
                // 固定收尾标记：脚本据此判断跑完（不要依赖进程退出）
                Log.i(TAG, "CORPUS ALLDONE n=$done")
                runOnUiThread { finish() }
            }
        }.start()
    }

    /** 相册分组 → 图片列表（按加入时间升序，保证可复现）。 */
    private fun queryAlbum(bucket: String): List<Pair<Uri, String>> {
        val out = mutableListOf<Pair<Uri, String>>()
        val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
        try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                proj,
                "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?",
                arrayOf(bucket),
                "${MediaStore.Images.Media.DATE_ADDED} ASC, ${MediaStore.Images.Media._ID} ASC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol)
                    )
                    out.add(uri to (c.getString(nameCol) ?: "unknown"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "query album '$bucket' failed", e)
        }
        return out
    }

    private suspend fun harvest(bucket: String): Int {
        val root = getExternalFilesDir(null) ?: filesDir
        val outDir = File(root, "corpus-out").apply { mkdirs() }
        val corpusDir = File(outDir, "corpus").apply { mkdirs() }

        val images = queryAlbum(bucket)
        val savedViews = SavedAddressStore.matcherViews(this)
        Log.i(TAG, "CORPUS START n=${images.size} bucket=$bucket savedViews=${savedViews.size}")

        val tsv = StringBuilder(TsvHeader).append('\n')
        var done = 0

        images.forEachIndexed { idx, (uri, name) ->
            val i = idx + 1
            val bitmap = try {
                ImageUtils.decodeSampledBitmap(this, uri)
            } catch (e: Exception) {
                Log.e(TAG, "decode failed $name", e); null
            }
            if (bitmap == null) {
                Log.w(TAG, "CORPUS $i/${images.size} $name DECODE_FAIL")
                tsv.append(name).append("\tDECODE_FAIL\n")
                return@forEachIndexed
            }

            val lines = OCREngine.recognize(bitmap)
            val coupons = try { CouponDetector.detect(bitmap) } catch (e: Exception) { emptyList() }
            val screenHeight = bitmap.height
            if (!bitmap.isRecycled) bitmap.recycle()

            if (lines.isEmpty() && coupons.isEmpty()) {
                Log.w(TAG, "CORPUS $i/${images.size} $name NO_OCR")
                tsv.append(name).append("\tNO_OCR\n")
                return@forEachIndexed
            }

            val allText = lines.joinToString(" ") { it.text }
            // context = null：与既有 corpus 单测同口径（不读也不写 PatternLearner 学习态）
            val results = CodeExtractor.extract(lines, screenHeight, null, "corpus")
            val loc = AddressExtractor.extractLocation(lines, allText)
            val globalAddr = AddressExtractor.extractAddressFromStores(this, lines, allText)
            val cabinet = AddressExtractor.extractCabinetNumber(lines, allText)
            val sensitive = SensitivePageGuard.isIdentityCodePage(allText)
            val financial = CodeExtractor.isFinancialNoise(allText)
            val multiCode = results.distinctBy { "${it.code}|${it.type}" }.size > 1
            val savedMatch = SavedAddressMatcher.match(lines, savedViews)
            val fallback = AddressExtractor.resolveAddress(lines, allText, "", globalAddr, multiCode)

            // 语料正文：复用正式导出实现，保证与 RecognitionDebugStore 的格式一致
            RecognitionDebugStore.capture(
                lines = lines,
                candidates = emptyList(),
                allText = allText,
                source = "corpus",
                screenHeight = screenHeight,
                finalResults = results.map { r ->
                    RecognitionDebugStore.CandidateInfo(r.code, r.confidence, r.type.name, r.source, -1, "")
                }
            )
            RecognitionDebugStore.captureAddress(
                RecognitionDebugStore.AddressInfo(
                    fullAddress = globalAddr,
                    station = loc.stationName,
                    cabinet = cabinet.ifBlank { null },
                    from = loc.addrFrom
                )
            )
            val body = (RecognitionDebugStore.exportFixture() ?: "")
                .lineSequence().joinToString("\n") { sanitizeEValue(it) }

            val couponStr = coupons.joinToString(";") { it.rawValue ?: "" }
            val extra = StringBuilder()
            if (results.isEmpty()) {
                tsv.append(
                    (listOf(name, lines.size.toString(), sensitive.toString(), financial.toString()) +
                        List(6) { "" } +
                        listOf(globalAddr.tsv(), cabinet.tsv(), loc.addrFrom, couponStr.tsv()))
                        .joinToString("\t")
                ).append('\n')
            }
            for (r in results) {
                val win = AddressExtractor.extractAddressForCode(lines, r.code)
                val eff = if (savedMatch != null &&
                    (!multiCode || AddressExtractor.isLineInCodeWindow(lines, r.code, savedMatch.lineIndex))
                ) {
                    savedMatch.fullName
                } else {
                    win.ifBlank { fallback }
                }
                tsv.append(
                    listOf(
                        name, lines.size.toString(), sensitive.toString(), financial.toString(),
                        r.code, r.type.name, r.source, r.confidence.toString(),
                        win.tsv(), eff.tsv(), globalAddr.tsv(), cabinet.tsv(), loc.addrFrom, couponStr.tsv()
                    ).joinToString("\t")
                ).append('\n')
                if (win.isNotBlank()) extra.append("E codeaddr ").append(r.code).append(' ').append(win.noSpace()).append('\n')
            }

            val id = "real-" + name.substringBeforeLast('.').lowercase()
                .replace(Regex("[^a-z0-9]+"), "-").trim('-')
            val text = body
                .replaceFirst(Regex("(?m)^# id: .*$"), "# id: $id")
                .replaceFirst(
                    Regex("(?m)^# source: .*$"),
                    "# source: corpus\n# note: 真机相册截图自动导出；E 段=导出时行为快照，入回归前需人工核对"
                ) + extra
            File(corpusDir, "$id.txt").writeText(text)

            done++
            Log.i(
                TAG,
                "CORPUS $i/${images.size} $name lines=${lines.size} " +
                    "codes=${results.joinToString(",") { it.code }} addr=$globalAddr from=${loc.addrFrom} " +
                    "sensitive=$sensitive financial=$financial"
            )
        }

        File(outDir, "summary.tsv").writeText(tsv.toString())
        return done
    }

    /**
     * corpus 的 E 行只允许 3 段（`CorpusFixture` 解析约束），地址里的空格会让解析崩，
     * 所以空格去掉；若去空格后为空则整行改成注释，避免产生假断言。
     */
    private fun sanitizeEValue(line: String): String {
        val (prefix, raw) = when {
            line.startsWith("E address ") -> "E address " to line.removePrefix("E address ")
            line.startsWith("E cabinet ") -> "E cabinet " to line.removePrefix("E cabinet ")
            else -> return line
        }
        val v = raw.noSpace()
        return if (v.isBlank()) "# (E 段为空，已省略)" else prefix + v
    }

    private fun String.noSpace(): String = replace(" ", "").replace("\t", "").trim()

    private fun String.tsv(): String = replace('\t', ' ').replace('\n', ' ').trim()

    private val TsvHeader =
        "file\tlines\tsensitive\tfinancial\tcode\ttype\tsource\tconf\twindowAddr\teffAddr\tglobalAddr\tcabinet\tfrom\tcoupon"
}
