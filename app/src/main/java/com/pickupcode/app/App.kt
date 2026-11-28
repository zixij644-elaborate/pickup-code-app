package com.pickupcode.app

import android.app.Application
import android.util.Log
import com.pickupcode.app.data.AppDatabase
import com.pickupcode.app.learner.CommonStationStore
import com.pickupcode.app.learner.PatternLearner
import com.pickupcode.app.notification.CodeNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // 渠道注册走后台线程，避免冷启动主线程同步执行 3 次 NotificationManager Binder IPC
        appScope.launch(Dispatchers.IO) {
            CodeNotificationManager.createChannels(this@App)
        }
        cleanScreenshots()
    }

    /**
     * 截图治理（代码检查 3-3：截图明文落盘且无 TTL）：
     * 延后到首帧之后在 IO 线程跑「孤儿清扫 + 30 天硬保留期 + 50MB 总量上限」，
     * 避免冷启动期与首页列表抢占 Room 数据库锁及 Keystore/磁盘 IO。
     */
    private fun cleanScreenshots() {
        appScope.launch(Dispatchers.IO) {
            delay(2500L)
            try {
                CommonStationStore.migrateLegacyAddresses(this@App)
                PatternLearner.migrateLegacySamples(this@App)
                AppDatabase.getInstance(this@App).repository.cleanScreenshots(this@App)
            } catch (e: Exception) {
                Log.w("App", "截图治理失败", e)
            }
        }
    }

    companion object {
        lateinit var instance: App
            private set

        // 全局协程作用域：供分享识别等「不依赖 Activity 生命周期」的后台任务使用，
        // 避免 Activity 销毁中断正在处理的识别流程（截图/OCR/DB写入）。
        val appScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    }
}
