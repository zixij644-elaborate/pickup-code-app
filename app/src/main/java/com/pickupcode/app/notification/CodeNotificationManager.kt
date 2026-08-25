package com.pickupcode.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pickupcode.app.extractor.CodeExtractor

object CodeNotificationManager {

    private const val CHANNEL_FOOD = "pickup_food"
    private const val CHANNEL_PARCEL = "pickup_parcel"
    private const val CHANNEL_COUPON = "pickup_coupon"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_FOOD, "取餐码", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "取餐码提醒"
                setShowBadge(true)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PARCEL, "取件码", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "快递取件码提醒"
                setShowBadge(true)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_COUPON, "券码", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "二维码/条码券码提醒"
                setShowBadge(true)
            }
        )
    }

    /**
     * 通知 id 空间划分（互不相交，杜绝各通知族互相覆盖）：
     *  主通知池  1 .. 0x0FFFFFFF    （递增分配，持久化计数器；上限让位给「已取」号段 0x10000000..0x1FFFFFFF，互不重叠）
     *  提醒      0x40000000 .. 0x5FFFFEFF （code+type 哈希）
     *  去重提示  0x60000000 .. 0x7FFFFEFF （code+type 哈希）
     *  结果提示  0x7FFFFFFF         （PickupCodeAccessibilityService.RESULT_NOTIFY_ID，独占最高位）
     * 提醒/去重两段统一用 % SEGMENT_MODULUS 截取，天然避开 0x7FFFFFFF。
     */
    private const val MAIN_NOTIFY_LIMIT = 0x10000000      // 主池上界（不含），池内 1..0x0FFFFFFF；与「已取」号段 0x10000000+ 完全隔离
    private const val REMIND_SEGMENT_BASE = 0x40000000    // 提醒段基址
    private const val DUP_SEGMENT_BASE = 0x60000000       // 去重段基址
    private const val SEGMENT_MODULUS = 0x1FFFFFFF        // 段内取值模（避开 0x7FFFFFFF 结果通知位）

    /** 主通知池 id 登记表：code+type → 展示中的通知 id 集合。
     *  供「已取」按 code+type 取消全部相关通知（主通知 + 去重提示 + 提醒）。 */
    private val activeNotifyIds = java.util.concurrent.ConcurrentHashMap<String, MutableSet<Int>>()

    /**
     * 进程内原子计数器；首次 nextNotifyId 时从 SP 恢复，避免三路径并发读改写同 id。
     * 持久化仍写 SP（进程重启后继续递增，防止盖掉仍在栏里的旧通知）。
     */
    private val notifyIdCounter = java.util.concurrent.atomic.AtomicInteger(-1)

    private fun trackNotifyId(type: CodeExtractor.CodeType, code: String, id: Int) {
        activeNotifyIds.getOrPut("$type:$code") { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(id)
    }

    /**
     * 持久化递增通知 id：进程重启后计数器继续递增，避免从 1 重新计数后新通知
     * 静默覆盖仍在通知栏的旧常驻通知（用户丢失未处理的取件提醒展示）。
     * 三路径并发安全：内存 AtomicInteger + synchronized 初始化/落盘。
     */
    private fun nextNotifyId(context: Context): Int {
        // 懒加载：-1 表示尚未从 SP 恢复
        if (notifyIdCounter.get() < 0) {
            synchronized(notifyIdCounter) {
                if (notifyIdCounter.get() < 0) {
                    val sp = context.getSharedPreferences("notif_state", Context.MODE_PRIVATE)
                    val stored = sp.getInt("notify_id_counter", 1).coerceAtLeast(1)
                    notifyIdCounter.set(stored)
                }
            }
        }
        val counter = notifyIdCounter.getAndIncrement()
        // 主池 1..0x0FFFFFFF（不含 0）
        val id = ((counter and 0x7fffffff) % (MAIN_NOTIFY_LIMIT - 1)) + 1
        // 异步落盘下一起点（失败不致命，最坏进程重启后可能复用一段 id）
        try {
            context.getSharedPreferences("notif_state", Context.MODE_PRIVATE)
                .edit().putInt("notify_id_counter", (counter + 1) and 0x7fffffff).apply()
        } catch (_: Exception) { /* ignore */ }
        return id
    }

    /**
     * 「已取」按钮 PendingIntent 请求码：与主通知 id / 忽略按钮号段隔离。
     * 旧实现用 nid+1，会与下一条主通知的「忽略」(requestCode=nid) 撞车并 FLAG_UPDATE_CURRENT 改写。
     * 主池上限 0x0FFFFFFF，故 nid < 0x10000000；本号段 0x10000000..0x1FFFFFFF 与主池/提醒/去重段均不重叠。
     */
    private fun doneActionRequestCode(nid: Int): Int =
        (nid and 0x0fffffff) or 0x10000000

    /** 稳定请求码/提醒 id：基于 code+type 复合，减少短码 hashCode 碰撞，并校正非负（保留给 PendingIntent 请求码与提醒通知 ID 空间）。 */
    private fun safeId(type: CodeExtractor.CodeType, code: String): Int =
        ("$type:$code".hashCode() and 0x7fffffff)

    /** 提醒通知 id：提醒段内哈希（0x40000000..0x5FFFFEFF）。 */
    private fun remindNotifyId(type: CodeExtractor.CodeType, code: String): Int =
        ((safeId(type, code) and 0x7fffffff) % SEGMENT_MODULUS) or REMIND_SEGMENT_BASE

    /** 去重提示通知 id：去重段内哈希（0x60000000..0x7FFFFEFF）。 */
    private fun dupNotifyId(type: CodeExtractor.CodeType, code: String): Int =
        ((safeId(type, code) and 0x7fffffff) % SEGMENT_MODULUS) or DUP_SEGMENT_BASE

    /** 提醒闹钟请求码：按 kind 分到不同段位，避免同码「稍后提醒」与「到期提醒」共用请求码互相覆盖。
     *  later → [0x20000000, 0x5fffffff]，expiry → [0x40000000, 0x7fffffff]，两段互斥。 */
    private fun remindRequestCode(type: CodeExtractor.CodeType, code: String, kind: String): Int =
        (safeId(type, code) and 0x3fffffff) or (if (kind == KIND_EXPIRY) 0x40000000 else 0x20000000)

    private data class TypeStyle(val channelId: String, val iconLabel: String, val title: String)

    private fun typeStyle(type: CodeExtractor.CodeType): TypeStyle = when (type) {
        CodeExtractor.CodeType.pickup_parcel -> TypeStyle(CHANNEL_PARCEL, "\uD83D\uDCE6", "取件码")
        CodeExtractor.CodeType.pickup_food -> TypeStyle(CHANNEL_FOOD, "\uD83E\uDD64", "取餐码")
        CodeExtractor.CodeType.coupon -> TypeStyle(CHANNEL_COUPON, "\uD83C\uDF9F\uFE0F", "券码")
    }

    fun show(context: Context, code: String, type: CodeExtractor.CodeType, source: String, historyId: Long? = null) {
        // Android 13+ 需运行时通知权限，未授予则不发送（静默忽略，避免异常）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val style = typeStyle(type)
        val channelId = style.channelId
        val iconLabel = style.iconLabel
        val title = style.title

        val pendingIntent = launchPendingIntent(context)

        val nid = nextNotifyId(context)
        trackNotifyId(type, code, nid)
        // X/滑动删除走 DeleteIntent → NotificationDismissReceiver（与「忽略」按钮一致：仅收起通知，DB 记录保留）
        val deleteIntent = PendingIntent.getBroadcast(context, nid,
            Intent(context, NotificationDismissReceiver::class.java).apply {
                putExtra("notification_id", nid)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$iconLabel $title")
            .setContentText("$source  —  $code")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("$iconLabel $source\n$title: $code"))
            .setContentIntent(pendingIntent)
            // 用户反馈：常驻(ongoing)通知按 X/滑动删不掉。改为可删除——
            // 码已入库，App 首页随时可查，「已取」按钮负责归档，误删不影响数据。
            .setDeleteIntent(deleteIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, "已取",
                PendingIntent.getBroadcast(context, doneActionRequestCode(nid),
                    Intent(context, DoneReceiver::class.java).apply {
                        putExtra("history_id", historyId ?: -1)
                        putExtra("notification_id", nid)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "忽略",
                PendingIntent.getBroadcast(context, nid,
                    Intent(context, NotificationDismissReceiver::class.java).apply {
                        putExtra("notification_id", nid)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()

        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.notify(nid, notification)
    }

    /**
     * 按 code+type 取消该码的全部相关通知：登记表内主通知 + 去重提示 + 提醒。
     * 修复 DoneReceiver 场景：同码同时存在主通知与重复提示（不同 id）时，「已取」
     * 不再只取消被点击的那一条，另一条残留指向已归档数据的问题。
     */
    fun dismissByCodeAndType(context: Context, type: CodeExtractor.CodeType, code: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        activeNotifyIds.remove("$type:$code")?.forEach { nm.cancel(it) }
        nm.cancel(dupNotifyId(type, code))
        nm.cancel(remindNotifyId(type, code))
    }

    fun dismissById(context: Context, id: Int) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(id)
        activeNotifyIds.values.forEach { it.remove(id) }
    }

    // ---------------------------------------------------------------
    // C3: 稍后提醒 —— 用 AlarmManager 定时再弹一条取件/取餐提醒
    // ---------------------------------------------------------------

    /** 触发用的 extra key（与 RemindReceiver 共用）。 */
    private const val EXTRA_REMIND_CODE = "remind_code"
    private const val EXTRA_REMIND_TYPE = "remind_type"
    private const val EXTRA_REMIND_SOURCE = "remind_source"
    private const val EXTRA_REMIND_KIND = "remind_kind"

    /** 提醒类型：later=用户手动稍后提醒；expiry=自动到期提醒（文案不同）。 */
    private const val KIND_LATER = "later"
    private const val KIND_EXPIRY = "expiry"

    /** 稍后提醒：delayMs 毫秒后（默认 1 小时）重新推一条提醒通知。 */
    fun remindLater(context: Context, code: String, type: CodeExtractor.CodeType,
                    source: String, delayMs: Long = 60L * 60 * 1000) {
        scheduleRemind(context, code, type, source, delayMs, KIND_LATER)
    }

    /** 到期提醒：expiryAt 时刻（或立即，若已过）推一条"可能快过期"提醒（DB v6）。 */
    fun scheduleExpiryReminder(context: Context, code: String, type: CodeExtractor.CodeType,
                               source: String, expiryAt: Long) {
        val delayMs = (expiryAt - System.currentTimeMillis()).coerceAtLeast(1_000L)
        scheduleRemind(context, code, type, source, delayMs, KIND_EXPIRY)
    }

    private fun scheduleRemind(context: Context, code: String, type: CodeExtractor.CodeType,
                               source: String, delayMs: Long, kind: String) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, RemindReceiver::class.java).apply {
            putExtra(EXTRA_REMIND_CODE, code)
            putExtra(EXTRA_REMIND_TYPE, type.name)
            putExtra(EXTRA_REMIND_SOURCE, source)
            putExtra(EXTRA_REMIND_KIND, kind)
        }
        val pi = PendingIntent.getBroadcast(context, remindRequestCode(type, code, kind), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val triggerAt = System.currentTimeMillis() + delayMs
        try {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } catch (_: SecurityException) {
            // 无 SCHEDULE_EXACT_ALARM 权限时退化为普通 set（有延迟但可用）
            alarm.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    /** RemindReceiver 在 onReceive 里调用：真正弹出提醒通知。kind: later/expiry（文案区分）。 */
    fun showReminder(context: Context, code: String, type: CodeExtractor.CodeType, source: String,
                     kind: String = KIND_LATER) {
        if (code.isBlank()) return
        // Android 13+ 无通知权限时静默跳过（与 show/showDuplicate 一致），避免无效提醒
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            val style = typeStyle(type)
            val pendingIntent = launchPendingIntent(context)
            val (title, text) = if (kind == KIND_EXPIRY) {
                "⏳ 取件码可能快到期：$code" to "存放已久，记得及时去取：$code（$source）"
            } else {
                "⏰ 稍后提醒：${style.title} $code" to "记得去取：$code（$source）"
            }
            val notification = NotificationCompat.Builder(context, style.channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .build()
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            // 提醒段独立 id 空间，不覆盖主通知/去重提示/结果提示
            manager.notify(remindNotifyId(type, code), notification)
        } catch (e: Exception) { Log.w("CodeNotification", "提醒通知构建失败", e) }
    }

    /** 通知点击跳转：通过 launch intent 打开主界面（不直接引用 Activity 类，避免 notification→ui 环依赖）。 */
    private fun launchPendingIntent(context: Context, requestCode: Int = 0, extra: Pair<String, Boolean>? = null): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER); setPackage(context.packageName) }
        launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        if (extra != null) launch.putExtra(extra.first, extra.second)
        return PendingIntent.getActivity(
            context, requestCode, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 取消已设置的提醒闹钟（用户提前取件时调用）：later 与 expiry 两类一并取消。 */
    fun cancelRemind(context: Context, code: String, type: CodeExtractor.CodeType) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, RemindReceiver::class.java)
        for (kind in listOf(KIND_LATER, KIND_EXPIRY)) {
            val pi = PendingIntent.getBroadcast(context, remindRequestCode(type, code, kind), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarm.cancel(pi)
            pi.cancel()
        }
    }

    /** Show notification for a duplicate code — informs user there are now ≥2 records for this code. */
    fun showDuplicate(context: Context, code: String, type: CodeExtractor.CodeType, source: String, historyId: Long, dupGroupCount: Int) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val style = typeStyle(type)
        val channelId = style.channelId
        val iconLabel = style.iconLabel

        val pendingIntent = launchPendingIntent(context, requestCode = safeId(type, code), extra = "show_dedup" to true)

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$iconLabel $code 再次出现")
            .setContentText("$source · 点击整理去重（共 ${dupGroupCount} 组重复）")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // 去重提示用独立 id 段（code+type 复合），避免与主通知/提醒/结果提示冲突
        nm.notify(dupNotifyId(type, code), notification)
    }
}
