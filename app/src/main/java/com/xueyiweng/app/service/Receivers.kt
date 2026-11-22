package com.xueyiweng.app.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.xueyiweng.app.R
import com.xueyiweng.app.logic.Stage

/** 阶段到点：发一条高优先级本地通知 + 双轻震 + 木鱼声，不弹窗阻断 */
class StageAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            StageAlarms.ACTION_STAGE_END -> {
                val stage = Stage.fromId(intent.getStringExtra("stage"))
                val body = intent.getStringExtra("body")
                    ?: "准备好了，点一下进入下一步"

                Haptics.double(ctx)
                runCatching { Chimes(ctx).playChime() }
                    .onFailure { Log.w("StageAlarm", "chime failed", it) }

                val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
                Notifications.ensureChannels(ctx)

                val notif = NotificationCompat.Builder(ctx, Notifications.CH_STAGE)
                    .setSmallIcon(R.drawable.ic_stat_timer)
                    .setContentTitle("「${stage.label}」时间到")
                    .setContentText(body)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .setContentIntent(Notifications.contentIntent(ctx))
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .build()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }
                runCatching { nm.notify(Notifications.ID_STAGE, notif) }
            }

            "com.xueyiweng.app.ACTION_FROM_NOTIF_PAUSE" -> {
                // 从通知栏暂停：交给 Activity 处理（服务只负责倒计时展示）
                runCatching {
                    ctx.startActivity(
                        Intent(ctx, com.xueyiweng.app.ui.MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra("action_from_notif", "pause")
                        }
                    )
                }
            }
        }
    }
}

/** 开机 / 更新后重建每日刷新闹钟 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            DailyRefresh.scheduleNext(ctx)
        }
    }
}

/** 每日凌晨刷新：把逾期题目归入今天（这一步是查询时动态算的，这里只做一次通知） */
class DailyRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        DailyRefresh.scheduleNext(ctx)
    }
}

object DailyRefresh {
    private const val REQ = 4001
    private const val ACTION = "com.xueyiweng.app.DAILY_REFRESH"

    fun scheduleNext(ctx: Context) {
        val am = ctx.getSystemService(android.app.AlarmManager::class.java) ?: return
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 7)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        val pi = android.app.PendingIntent.getBroadcast(
            ctx,
            REQ,
            Intent(ctx, DailyRefreshReceiver::class.java).apply { action = ACTION },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            am.setInexactRepeating(
                android.app.AlarmManager.RTC_WAKEUP,
                cal.timeInMillis,
                android.app.AlarmManager.INTERVAL_DAY,
                pi,
            )
        }.onFailure { Log.w("DailyRefresh", "schedule failed", it) }
    }
}
