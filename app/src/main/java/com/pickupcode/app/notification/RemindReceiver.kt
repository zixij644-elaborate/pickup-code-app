package com.pickupcode.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.pickupcode.app.extractor.CodeExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** C3: 稍后提醒/到期提醒 —— 收到 AlarmManager 定时广播后，复查状态并推提醒通知。 */
class RemindReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getStringExtra("remind_code") ?: return
        val typeName = intent.getStringExtra("remind_type") ?: return
        val source = intent.getStringExtra("remind_source").orEmpty()
        val kind = intent.getStringExtra("remind_kind") ?: "later"
        val type = runCatching { CodeExtractor.CodeType.valueOf(typeName) }
            .getOrDefault(CodeExtractor.CodeType.pickup_parcel)
        // 发前复查：码已标记已取（无活跃记录）则静默放弃，防假警报
        // goAsync：onReceive 返回后保持进程存活直到复查完成（异步 DB 查询必需）
        val pendingResult = goAsync()
        // 本地临时 scope：随本次广播生命周期走，避免成员级 scope 永不 cancel（C3 遗留）
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                // 限时完成：冷启动 Room 首次查询若超时（goAsync ~10s 上限），放弃本次提醒而非被系统回收
                withTimeoutOrNull(8000) {
                    val repo = com.pickupcode.app.data.AppDatabase.getInstance(context).repository
                    // directBootAware 场景：开机未解锁时 CE 存储的 DB 不可用 → 无法复查。
                    // 此时宁可在解锁后补弹一次（真取过的码其闹钟已被「已取」cancelRemind 取消，
                    // 残留闹钟罕见），也不让提醒静默丢失。
                    val activeCount = try {
                        repo.countActiveByCodeAndType(code, type.name)
                    } catch (e: Exception) {
                        Log.w("RemindReceiver", "直启引导阶段 DB 不可用，按未知处理", e)
                        -1
                    }
                    if (activeCount != 0) {
                        CodeNotificationManager.showReminder(context, code, type, source, kind)
                    }
                }
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }
}
