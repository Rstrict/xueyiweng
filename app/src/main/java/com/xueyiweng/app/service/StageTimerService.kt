package com.xueyiweng.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.xueyiweng.app.R
import com.xueyiweng.app.logic.Dates
import com.xueyiweng.app.logic.SessionSnapshot
import com.xueyiweng.app.logic.Stage
import com.xueyiweng.app.logic.Timer

/**
 * 计时前台服务。
 *
 * 这是原生相对 PWA 的核心优势：JS 在后台会被系统挂起，JS 计时器直接停走；
 * 前台服务持有进程优先级，息屏、切到网课 App、锁屏都照样按时间戳推进。
 *
 * 注意：服务本身不做 tick 累加，只按 startForeground(时间戳计算) 更新通知文案，
 * 即使服务被系统短暂延迟，下一次刷新也能立刻对齐真实剩余时间。
 */
class StageTimerService : Service() {

    companion object {
        const val ACTION_START = "com.xueyiweng.app.TIMER_START"
        const val ACTION_STOP = "com.xueyiweng.app.TIMER_STOP"
        const val ACTION_SYNC = "com.xueyiweng.app.TIMER_SYNC"
        const val EXTRA_STAGE = "stage"
        const val EXTRA_QUESTION = "question"
        const val EXTRA_TARGET_SEC = "target_sec"
        const val EXTRA_START_TS = "start_ts"
        const val EXTRA_PAUSED_ACC = "paused_acc"
        const val EXTRA_IS_PAUSED = "is_paused"

        fun start(ctx: Context, s: SessionSnapshot) {
            val i = Intent(ctx, StageTimerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_STAGE, s.currentStage)
                putExtra(EXTRA_QUESTION, s.questionText)
                putExtra(EXTRA_TARGET_SEC, s.stageTargetDurationSec)
                putExtra(EXTRA_START_TS, s.stageStartTimestamp)
                putExtra(EXTRA_PAUSED_ACC, s.accumulatedPausedSec)
                putExtra(EXTRA_IS_PAUSED, s.isPaused)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun sync(ctx: Context, s: SessionSnapshot) {
            ctx.startService(Intent(ctx, StageTimerService::class.java).apply {
                action = ACTION_SYNC
                putExtra(EXTRA_STAGE, s.currentStage)
                putExtra(EXTRA_QUESTION, s.questionText)
                putExtra(EXTRA_TARGET_SEC, s.stageTargetDurationSec)
                putExtra(EXTRA_START_TS, s.stageStartTimestamp)
                putExtra(EXTRA_PAUSED_ACC, s.accumulatedPausedSec)
                putExtra(EXTRA_IS_PAUSED, s.isPaused)
            })
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, StageTimerService::class.java))
        }
    }

    private var stage: Stage = Stage.STUDY
    private var question: String = ""
    private var targetSec: Int = 480
    private var startTs: Long = 0
    private var pausedAcc: Long = 0
    private var isPaused: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START, ACTION_SYNC -> {
                stage = Stage.fromId(intent.getStringExtra(EXTRA_STAGE))
                question = intent.getStringExtra(EXTRA_QUESTION) ?: ""
                targetSec = intent.getIntExtra(EXTRA_TARGET_SEC, stage.defaultSec)
                startTs = intent.getLongExtra(EXTRA_START_TS, System.currentTimeMillis())
                pausedAcc = intent.getLongExtra(EXTRA_PAUSED_ACC, 0L)
                isPaused = intent.getBooleanExtra(EXTRA_IS_PAUSED, false)

                val notif = buildNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        Notifications.ID_ONGOING,
                        notif,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                    )
                } else {
                    startForeground(Notifications.ID_ONGOING, notif)
                }
            }
        }
        return START_STICKY
    }

    private fun currentRemaining(): Long {
        val ref = if (isPaused) System.currentTimeMillis() else System.currentTimeMillis()
        val raw = (ref - startTs) / 1000.0
        val used = (raw - pausedAcc).coerceAtLeast(0.0)
        return (targetSec - used).toLong()
    }

    private fun buildNotification(): Notification {
        val remain = currentRemaining()
        val timeText = when {
            isPaused -> "已暂停"
            remain > 0 -> "剩 ${Dates.clock(remain)}"
            else -> "已超时 ${Dates.clock(-remain)}"
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, Notifications.CH_ONGOING)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("阶段 ${stage.number}/4 · ${stage.label}")
            .setContentText(if (question.isBlank()) timeText else "$timeText · ${question.take(30)}")
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(Notifications.contentIntent(this))
            .addAction(
                0,
                "暂停",
                PendingIntent.getBroadcast(
                    this, 3001,
                    Intent(this, StageAlarmReceiver::class.java).apply { action = "com.xueyiweng.app.ACTION_FROM_NOTIF_PAUSE" },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
    }
}
