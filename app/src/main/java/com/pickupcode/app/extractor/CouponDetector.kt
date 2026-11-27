package com.pickupcode.app.extractor

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * 券码检测器：用 ML Kit Barcode（bundled）检测 + 解码图片中的二维码/条码。
 * 返回解码出的结果列表；空列表 = 未检测到。
 * 支持两条路径（无障碍截图 / 分享图片）共用。
 */
object CouponDetector {

    private const val TAG = "CouponDetector"

    // 只认二维码：二维码有三定位角结构，普通数字/文本不会被误识别为二维码，彻底避免"不认数字"的误报
    @Volatile
    private var scanner: BarcodeScanner? = null

    private fun getScanner(): BarcodeScanner =
        scanner ?: synchronized(this) {
            scanner ?: BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build()
            ).also { scanner = it }
        }

    /** 释放 ML Kit 客户端（服务销毁时调用，避免 native 资源累积泄漏）。
     *  与 detect 共用同一把 mutex：确保不会在 process 在途时关闭 scanner（H1）。
     *  由调用方在后台协程调用；不要在主线程 runBlocking 调用（会阻塞等待在途检测，最长 10s，触发 ANR）。 */
    suspend fun close() {
        mutex.withLock {
            try {
                scanner?.close()
            } finally {
                scanner = null
            }
        }
    }

    data class CouponResult(
        val rawValue: String?   // 解码内容（码值）
    )

    // 串行化 BarcodeScanner.process：ML Kit 同一客户端不允许并发 process()，否则抛 "detector busy" 静默丢券码
    private val mutex = Mutex()

    /** 检测并解码 bitmap 中的二维码/条码，返回解码结果；异常或未检测到返回空列表。 */
    suspend fun detect(bitmap: Bitmap): List<CouponResult> = withContext(Dispatchers.Default) {
        try {
            mutex.withLock {
                val image = InputImage.fromBitmap(bitmap, 0)
                val barcodes = kotlinx.coroutines.withTimeoutOrNull(10_000L) { getScanner().process(image).await() }
                    ?: return@withLock emptyList()
                barcodes
                    .filter { !it.rawValue.isNullOrBlank() }
                    .map { CouponResult(rawValue = it.rawValue) }
            }
        } catch (e: CancellationException) {
            throw e   // H2: 协程取消必须重抛，不能吞
        } catch (e: Exception) {
            Log.e(TAG, "条码检测失败", e)
            emptyList()
        }
    }
}
