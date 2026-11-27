package com.pickupcode.app.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

object OCREngine {

    @Volatile
    private var recognizer: TextRecognizer? = null

    // H1/H3: 串行化所有 OCR 调用与关闭，避免 ML Kit "detector busy"/并发 close 竞态
    private val mutex = Mutex()

    private fun getRecognizer(): TextRecognizer {
        return recognizer ?: synchronized(this) {
            recognizer ?: TextRecognition.getClient(
                ChineseTextRecognizerOptions.Builder().build()
            ).also { recognizer = it }
        }
    }

    /**
     * OCR 行的包围盒。纯 Kotlin 实现，**刻意不用 android.graphics.Rect**：
     * Rect 在 JVM 单测里被 mock 成全零（构造器不赋值字段），几何分支（S0c 双列 / S7 续行 /
     * S8 邻近窗口 / S10 拼接 / 位置与字号加分）无法被验证。换成此类型后 corpus 语料
     * 可直接构造几何场景，识别层也不再依赖 android.graphics。
     */
    data class LineBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun width(): Int = right - left
        fun height(): Int = bottom - top
        fun centerY(): Int = (top + bottom) / 2
    }

    data class TextLine(
        val text: String,
        val boundingBox: LineBox?,
        val confidence: Float?
    )

    // Unicode dash variants that OCR often produces instead of ASCII "-" (U+002D).
    // Normalizing these ensures CodeExtractor's regex patterns match correctly.
    // U+30FC is the Japanese long-vowel mark (ー), which the Chinese OCR model
    // frequently outputs for a hyphen in courier codes like H-24137.
    private val UNICODE_DASHES = Regex("[\u2010-\u2015\u2212\uFE58\uFE63\uFF0D\u30FC]")

    suspend fun recognize(bitmap: Bitmap): List<TextLine> {
        // H1: 串行化 OCR 调用（lock/unlock 包住挂起体，withLock 的 action 非挂起不能直接 await）
        mutex.lock()
        try {
            return doRecognize(bitmap)
        } finally {
            mutex.unlock()
        }
    }

    /**
     * 将 OCR 行按屏幕视觉阅读顺序重排：
     * 1. 无任何坐标（短信/纯文本）时原样返回，保持天然行序；
     * 2. 有坐标时先按 centerY 升序初排，再将同一水平行带（纵向中心差 ≤ 行高×0.55 且水平不重叠）
     *    聚为同一行带、带内按 left 从左到右排列，消除 ML Kit textBlocks 分块乱序。
     * 采用确定性分带排序而非非传递 Comparator，避免 TimSort 在长列表上抛契约异常。
     */
    fun sortReadingOrder(lines: List<TextLine>): List<TextLine> {
        if (lines.size <= 1 || lines.none { it.boundingBox != null }) return lines
        val withBox = ArrayList<TextLine>(lines.size)
        val withoutBox = ArrayList<TextLine>()
        for (l in lines) {
            if (l.boundingBox != null) withBox.add(l) else withoutBox.add(l)
        }
        if (withBox.size <= 1) return lines

        withBox.sortWith(
            compareBy<TextLine> { it.boundingBox!!.centerY() }
                .thenBy { it.boundingBox!!.left }
                .thenBy { it.boundingBox!!.top }
        )

        class RowBand(first: TextLine) {
            val items = mutableListOf(first)
            var sumCenterY = first.boundingBox!!.centerY().toLong()
            var minHeight = first.boundingBox!!.height().coerceAtLeast(1)
            val avgCenterY: Int get() = (sumCenterY / items.size).toInt()
        }

        val bands = mutableListOf<RowBand>()
        for (line in withBox) {
            val box = line.boundingBox!!
            val cy = box.centerY()
            val h = box.height().coerceAtLeast(1)
            // 在最近的若干行带中查找可归入的水平行带（容忍左右两列轻微 Y 偏移，拒绝水平重叠的上下两行）
            var matched: RowBand? = null
            for (idx in bands.lastIndex downTo maxOf(0, bands.lastIndex - 3)) {
                val b = bands[idx]
                val tol = (minOf(b.minHeight, h) * 0.55f).coerceAtLeast(12f)
                if (kotlin.math.abs(cy - b.avgCenterY) <= tol) {
                    val overlapsX = b.items.any { existing ->
                        val eb = existing.boundingBox!!
                        val overlap = minOf(eb.right, box.right) - maxOf(eb.left, box.left)
                        overlap > minOf(eb.width(), box.width()) * 0.35f
                    }
                    if (!overlapsX) {
                        matched = b
                        break
                    }
                }
            }
            if (matched != null) {
                matched.items.add(line)
                matched.sumCenterY += cy
                if (h < matched.minHeight) matched.minHeight = h
            } else {
                bands.add(RowBand(line))
            }
        }

        bands.sortBy { it.avgCenterY }
        val sorted = ArrayList<TextLine>(lines.size)
        for (b in bands) {
            b.items.sortBy { it.boundingBox!!.left }
            sorted.addAll(b.items)
        }
        sorted.addAll(withoutBox)
        return sorted
    }

    private suspend fun doRecognize(bitmap: Bitmap): List<TextLine> {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val task = getRecognizer().process(image)
            val result = kotlinx.coroutines.withTimeout(30_000L) { task.await() }
            val out = mutableListOf<TextLine>()
            for (block in result.textBlocks) {
                for (line in block.lines) {
                    out.add(
                        TextLine(
                            text = line.text.trim().replace(UNICODE_DASHES, "-"),
                            boundingBox = line.boundingBox?.let { LineBox(it.left, it.top, it.right, it.bottom) },
                            confidence = line.confidence
                        )
                    )
                }
            }
            return sortReadingOrder(out)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // H-识别引擎#5: 内部兜底，避免 OCR 异常上抛导致整轮失败；无权结果时返回空列表
            android.util.Log.e("OCREngine", "识别失败", e)
            return emptyList()
        }
    }

    suspend fun close() {
        // H3: 与 recognize 用同一把 mutex 同步关闭——避免在途 OCR 未完成就关闭客户端。
        // 由调用方在后台协程调用；不要在主线程 runBlocking 调用（会阻塞等待在途 OCR，最长 30s，触发 ANR）。
        mutex.withLock {
            try {
                recognizer?.close()
            } finally {
                recognizer = null
            }
        }
    }
}
