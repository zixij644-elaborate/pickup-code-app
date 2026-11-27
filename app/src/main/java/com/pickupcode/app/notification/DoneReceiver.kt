package com.pickupcode.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.pickupcode.app.data.AppDatabase
import com.pickupcode.app.service.CodeCompletion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 通知栏「已取」按钮：标记 DB 记录为已完成并消除通知（后台线程，不阻塞主线程） */
class DoneReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val historyId = intent.getLongExtra("history_id", -1)
        val notificationId = intent.getIntExtra("notification_id", -1)

        val pending = goAsync()
        // 本地临时 scope：随本次 onReceive 生命周期走，既不泄漏也不污染实例/复用（避免 cancel 实例字段导致下次广播静默失败）
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                withTimeoutOrNull(8000) {
                    val repo = AppDatabase.getInstance(context).repository
                    if (historyId > 0) {
                        CodeCompletion.markDone(context, repo, historyId)
                    }
                    if (notificationId != -1) {
                        CodeNotificationManager.dismissById(context, notificationId)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("DoneReceiver", "标记已取失败: ${e.message}", e)
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }
}
