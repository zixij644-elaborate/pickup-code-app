package com.pickupcode.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pickupcode.app.data.AppDatabase
import com.pickupcode.app.extractor.CodeExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
                val repo = AppDatabase.getInstance(context).repository
                if (historyId > 0) {
                    // 与 App 内「标记已取」一致：归档该 code+type 的全部活跃记录（对齐 markDoneByCodeAndType）
                    val rec = repo.getByIdSuspend(historyId)
                    if (rec != null) {
                        val type = try { CodeExtractor.CodeType.valueOf(rec.type) } catch (_: Exception) { CodeExtractor.CodeType.pickup_parcel }
                        repo.markDoneByCodeAndType(rec.code, rec.type)
                        // 取消该码的稍后提醒（用户提前取了，不再需要闹钟）
                        CodeNotificationManager.cancelRemind(context, rec.code, type)
                        // 取消该码的全部相关通知：登记表内主通知 + 去重提示 + 提醒。
                        // 此前只 dismiss 被点击的那一条，同码的重复提示/其它通知会残留指向已归档数据。
                        CodeNotificationManager.dismissByCodeAndType(context, type, rec.code)
                    } else {
                        repo.markDone(historyId)
                    }
                }
                if (notificationId != -1) {
                    CodeNotificationManager.dismissById(context, notificationId)
                }
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }
}
