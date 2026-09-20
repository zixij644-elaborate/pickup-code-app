package com.pickupcode.app.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.FileProvider
import com.pickupcode.app.learner.PatternLearner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * C4: 成绩卡分享 —— 用手动 android.graphics.Canvas 生成一张统计海报 PNG，
 * 存到 cacheDir 后走 ACTION_SEND 分享（含图片附件）。
 *
 * 用手动 Canvas 而非 Compose GraphicsLayer 捕获，更稳、不依赖 Compose 帧时序。
 */
object ShareStatsCard {

    private const val W = 1080
    private const val H = 1560

    /** 生成海报并触发系统分享。重活（大位图创建 + PNG 压缩）由内部 withContext(IO) 承载，调用方无需关心线程。 */
    suspend fun share(context: Context, stats: PatternLearner.PatternStats) {
        try {
            withContext(Dispatchers.IO) { doShare(context, stats) }
        } catch (e: Exception) {
            // 分享失败（FileProvider/系统分享面板/内存不足）优雅降级，不崩溃
            android.util.Log.w("ShareStatsCard", "分享成绩卡失败: ${e.message}")
        }
    }

    private fun doShare(context: Context, stats: PatternLearner.PatternStats) {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bmp)
            drawPoster(canvas, stats)

            // 放专属子目录并建 .nomedia：避免部分 ROM 媒体扫描把成绩卡收进相册
            val dir = File(context.cacheDir, "share_card")
            dir.mkdirs()
            File(dir, ".nomedia").createNewFile()
            val file = File(dir, "stats_card.png")
            FileOutputStream(file).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }

            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)   // 允许从非 Activity context / 后台线程安全启动
                putExtra(Intent.EXTRA_TEXT, "我的取件/取餐码识别成绩单")
            }
            val chooser = Intent.createChooser(send, "分享成绩卡").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } finally {
            // 创建→绘制→压缩 任意一步抛异常都回收位图（约 6.7MB），避免泄漏
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    private fun drawPoster(c: Canvas, s: PatternLearner.PatternStats) {
        // 背景（渐变感：上下两条色带）
        c.drawColor(Color.parseColor("#F5F8FC"))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 顶部标题区卡片
        val head = RectF(60f, 60f, W - 60f, 300f)
        paint.color = Color.parseColor("#8DC0E0")
        c.drawRoundRect(head, 24f, 24f, paint)
        paint.color = Color.WHITE
        paint.textSize = 64f
        paint.isFakeBoldText = true
        c.drawText("码上闪记 · 识别成绩单", 110f, 150f, paint)
        paint.textSize = 36f
        paint.isFakeBoldText = false
        c.drawText("取件码 / 取餐码 · 认得准、越用越懂", 110f, 220f, paint)

        val hitRate = if (s.totalScans > 0) s.attempts * 100f / s.totalScans else 0f
        drawStatCard(c, 60f, 360f, "累计识别", "${s.totalScans}", "次")
        drawStatCard(c, 390f, 360f, "命中率", "${hitRate.toInt()}", "%")
        drawStatCard(c, 720f, 360f, "已用模式", "${s.perPattern.size}", "类")

        // 底部说明
        paint.color = Color.parseColor("#6B7280")
        paint.textSize = 30f
        c.drawText("· 帮助我自动识别屏幕上的取件码/取餐码", 80f, H - 200f, paint)
        c.drawText("· 越用越懂，自动学习新格式", 80f, H - 150f, paint)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        c.drawText(today, 80f, H - 90f, paint)
    }

    private fun drawStatCard(c: Canvas, x: Float, y: Float, label: String, value: String, unit: String) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val card = RectF(x, y, x + 300f, y + 300f)
        paint.color = Color.WHITE
        c.drawRoundRect(card, 20f, 20f, paint)
        paint.color = Color.parseColor("#9AA4B2")
        paint.textSize = 28f
        c.drawText(label, x + 30f, y + 90f, paint)
        paint.color = Color.parseColor("#111827")
        paint.textSize = 70f
        paint.isFakeBoldText = true
        c.drawText(value, x + 30f, y + 210f, paint)
        paint.textSize = 30f
        paint.isFakeBoldText = false
        c.drawText(unit, x + 250f, y + 210f, paint)
    }
}
