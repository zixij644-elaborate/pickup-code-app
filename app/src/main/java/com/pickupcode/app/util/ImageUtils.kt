package com.pickupcode.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * 图片工具：降采样解码 + EXIF 旋转 + JPEG 落盘。
 * 从 ShareReceiver 抽出（识别编排与图片处理分离）。
 */
object ImageUtils {

    /**
     * 降采样解码分享图片：先读尺寸按 inSampleSize 缩放，避免 4000×3000 全尺寸解码 OOM。
     * minSdk 26 < 28：ImageDecoder（自动应用 EXIF 旋转）不可用，保留 BitmapFactory + 手动读 EXIF 旋转。
     */
    fun decodeSampledBitmap(context: Context, uri: Uri): Bitmap? {
        // 第一遍：只读边界拿尺寸（不分配像素）
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // 计算采样倍数：目标最长边 ~1600px（OCR 分辨率足够，兼顾内存）。
        var sample = 1
        var dim = maxOf(bounds.outWidth, bounds.outHeight)
        while (dim >= 1600) { sample *= 2; dim /= 2 }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
        }
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null

        // 手动应用 EXIF 旋转（相册直出的竖拍图带 Orientation，不旋转会歪 90°/左右颠倒）
        return try {
            val rotation = context.contentResolver.openInputStream(uri)?.use {
                androidx.exifinterface.media.ExifInterface(it).rotationDegrees
            } ?: 0
            if (rotation == 0) {
                bmp
            } else {
                val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                if (rotated !== bmp) bmp.recycle()
                rotated
            }
        } catch (_: Exception) {
            bmp
        }
    }

    /**
     * AI 图片通道用：把 bitmap 压成 JPEG（长边 ≤ [maxSide]，质量 [quality]）再 base64。
     * 只压缩副本，**不动调用方的 bitmap**（调用方自己负责 recycle）。
     * DeepSeek 会把图片按总像素缩到 ~1300×1300 等量，所以本地压到 1600 长边足够，
     * 既能保住取件码那种小字的可读性，又把上传体积压到几百 KB。
     */
    fun toBase64JpegForAi(bitmap: Bitmap, maxSide: Int = 1600, quality: Int = 80): String? {
        return try {
            val long = maxOf(bitmap.width, bitmap.height)
            val scaled = if (long > maxSide) {
                val ratio = maxSide.toFloat() / long
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bitmap
            }
            val out = java.io.ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (scaled !== bitmap) scaled.recycle()
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            android.util.Log.e("ImageUtils", "AI 图片压缩失败", e)
            null
        }
    }

    /**
     * 生成一张自检小图（白底黑字 "CP-1234"）。
     * 设置页「测试 AI 连接」用它验证**图片通道**是否可用：模型回复里应能读到这串字。
     */
    fun makeProbeImageBase64(): String? {
        return try {
            val w = 480; val h = 200
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            canvas.drawColor(android.graphics.Color.WHITE)
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = 64f
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
            }
            canvas.drawText("CP-1234", w / 2f, h / 2f + 22f, paint)
            toBase64JpegForAi(bmp, maxSide = 480, quality = 85).also { bmp.recycle() }
        } catch (e: Exception) {
            android.util.Log.e("ImageUtils", "自检图生成失败", e)
            null
        }
    }

    /** 保存 bitmap 为 JPEG（85 压缩）到 cacheDir 子目录；失败返回空串。 */
    fun saveJpeg(context: Context, dirName: String, prefix: String, bitmap: Bitmap): String {
        return try {
            val dir = File(context.cacheDir, dirName)
            dir.mkdirs()
            val file = File(dir, "${prefix}_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            android.util.Log.e("ImageUtils", "保存图片失败", e)
            ""
        }
    }
}
