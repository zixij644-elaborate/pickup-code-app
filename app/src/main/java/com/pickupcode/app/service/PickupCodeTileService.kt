package com.pickupcode.app.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast

/**
 * 快捷设置磁贴：点击 → 触发截图识别。
 *
 * 触发链路（与双音量键完全同路径）：置 [PickupCodeAccessibilityService.triggerRequested] 标记，
 * 常驻无障碍服务的心跳（3 秒）/ 系统事件 / 服务重连消费标记后执行截图 OCR。
 *
 * 修复要点（v1.0.8 起「点击磁贴但没有识别」的根因之一）：
 * 旧版只查 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 字符串。Xiaomi/HyperOS 杀进程、
 * 省电冻结后常常「设置里仍显示开启、服务实际未连接」——标记无处消费，点击静默失效。
 * 因此：
 *  - [onClick] 分三支：真连接 → 置标记并 Toast 确认；假连接 → 明示并引导重开；未开启 → 引导设置。
 *  - [onStartListening] 磁贴灰态以「真实连接」为准，不再"看起来开着其实没反应"。
 */
class PickupCodeTileService : TileService() {

    // 回主线程更新磁贴用（updateTile 必须在主线程）
    private val mainHandler = Handler(Looper.getMainLooper())

    // 磁贴状态检查用单线程执行器（复用，替代每次 onStartListening new 裸 Thread；daemon 不阻止进程退出）
    private val stateExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "tile-state").apply { isDaemon = true }
    }

    override fun onClick() {
        super.onClick()
        val enabledInSettings = isEnabledInSettings()
        // 用同进程的 connected 标志判断真实连接（vivo 等 ROM 上
        // AccessibilityManager.getEnabledAccessibilityServiceList 对已绑定服务会假阴性，误判"未开启"）
        val reallyConnected = PickupCodeAccessibilityService.connected
        when {
            // ① 服务真实连接：武装触发标记，由常驻服务在「控制面板收起后」自动截图识别；
            //    Toast 即时指引，避免"点了没反应"和"截到控制面板"两类困惑
            enabledInSettings && reallyConnected -> {
                PickupCodeAccessibilityService.armManual(fromPanel = true)
                Log.d(TAG_TILE, "触发标记已设置（服务已连接，等待面板收起扫描）")
                toast("已就绪：滑出控制面板，停留在取件码界面后自动识别", long = true)
            }
            // ② 设置里开着但服务实际没连上（国内 ROM 杀进程后常见，或冷启动 binding 尚未建立）：
            //    延迟复查 1.5s——binding 建立好了就直接触发；仍没连上才明示并引导重开
            enabledInSettings && !reallyConnected -> {
                Log.w(TAG_TILE, "无障碍服务设置开启但未连接，延迟复查 1.5s")
                stateExecutor.execute {
                    var reconnected = false
                    for (i in 0 until 3) {
                        try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                        if (PickupCodeAccessibilityService.connected) {
                            reconnected = true
                            break
                        }
                    }
                    mainHandler.post {
                        if (reconnected) {
                            PickupCodeAccessibilityService.armManual(fromPanel = true)
                            Log.d(TAG_TILE, "延迟复查后服务已连接，触发标记已设置")
                            toast("已就绪：滑出控制面板，停留在取件码界面后自动识别", long = true)
                        } else {
                            Log.w(TAG_TILE, "服务仍未连接，跳转设置引导重开")
                            toast("无障碍服务未在运行，请重新开启", long = true)
                            startAccessibilitySettings()
                        }
                    }
                }
            }
            // ③ 未开启：引导设置（M4）
            else -> {
                Log.d(TAG_TILE, "无障碍服务未开启，跳转设置")
                startAccessibilitySettings()
            }
        }
    }

    private fun startAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.e(TAG_TILE, "打开无障碍设置失败: ${e.message}")
        }
    }

    /** 设置字符串检查：仅判断「开关状态」，不能代表服务真实连接。 */
    private fun isEnabledInSettings(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        // split + 精确比对包名+服务类名，避免 contains 模糊匹配误判（M4/MainActivity低危项同源）
        val target = "$packageName/${PickupCodeAccessibilityService::class.java.name}"
        return enabledServices.split(':').any { it.trim() == target }
    }

    private var listenGeneration = 0

    override fun onStartListening() {
        super.onStartListening()
        // 磁贴可用态以「服务真实连接」为准（Low-2 基础上再收紧一层）：
        // 避免设置字符串显示开启但服务实际被杀时磁贴仍亮着 → 点击静默无反应。
        // 冷启动竞态修复：进程被杀后拉开面板，无障碍服务 binding 可能尚未建立（系统异步重连），
        // 立刻查会误判"未连接"→磁贴灰一整天（只在下次开面板才刷新）。因此分 0s/1.5s/3s 三拍复查。
        // 注意：灰态用 STATE_INACTIVE 而非 UNAVAILABLE——vivo 等 ROM 上 UNAVAILABLE 磁贴点击不回调 onClick，
        // 用户会"点了没有任何反应"；INACTIVE 保持可点，onClick 三分支负责引导。
        val gen = ++listenGeneration
        for (delayMs in longArrayOf(0L, 1500L, 3000L)) {
            mainHandler.postDelayed({
                if (gen != listenGeneration) return@postDelayed // 新一次 onStartListening 已接管，丢弃旧结果
                stateExecutor.execute {
                    val ok = isEnabledInSettings() && PickupCodeAccessibilityService.connected
                    mainHandler.post {
                        if (gen != listenGeneration) return@post
                        qsTile?.apply {
                            val target = if (ok) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                            if (state != target) {
                                state = target
                                updateTile()
                            }
                        }
                    }
                }
            }, delayMs)
        }
    }

    private fun toast(msg: String, long: Boolean = false) {
        try {
            val duration = if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            Toast.makeText(applicationContext, msg, duration).show()
        } catch (e: Exception) {
            Log.w(TAG_TILE, "Toast 失败: ${e.message}")
        }
    }

    override fun onDestroy() {
        stateExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG_TILE = "PickupCodeTile"
    }
}