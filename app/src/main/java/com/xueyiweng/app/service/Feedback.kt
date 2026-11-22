package com.xueyiweng.app.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.xueyiweng.app.R
import com.xueyiweng.app.logic.Stage
import com.xueyiweng.app.ui.MainActivity

/**
 * 原生能力层。
 *
 * 相比 PWA 的关键差异：
 *  - AlarmManager.setExactAndAllowWhileIdle：息屏/待机也能准点响
 *  - 前台服务：计时不受进程回收影响
 *  - Vibrator/VibrationEffect：精确控制震动波形
 *  - SoundPool：低延迟短音效，不打断心流
 */
object Notifications {

    const val CH_STAGE = "xyw_stage"
    const val CH_ONGOING = "xyw_ongoing"
    const val CH_DAILY = "xyw_daily"

    const val ID_ONGOING = 1001
    const val ID_STAGE = 1002
    const val ID_DAILY = 1003

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return

        // 阶段到点：默认优先级，不发声（声音自己用 SoundPool 放，避免被系统音量策略压制）
        nm.createNotificationChannel(
            NotificationChannel(CH_STAGE, "阶段到点提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "学习阶段结束时轻轻提醒一次，不弹窗阻断"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 40, 90, 40)
                setSound(null, null)
                enableLights(false)
            }
        )

        // 计时常驻：最低优先级，不可响铃
        nm.createNotificationChannel(
            NotificationChannel(CH_ONGOING, "计时进行中", NotificationManager.IMPORTANCE_MIN).apply {
                description = "学习中显示剩余时间，可随时点开回到当前阶段"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(CH_DAILY, "每日复习", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "今天有几道题到期了"
                setSound(null, null)
            }
        )
    }

    fun contentIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx,
        0,
        Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * 精确闹钟：调度阶段结束提醒。
 * Android 12+ 若无 SCHEDULE_EXACT_ALARM 权限，自动降级为 setAndAllowWhileIdle。
 */
object StageAlarms {

    private const val TAG = "StageAlarms"
    const val ACTION_STAGE_END = "com.xueyiweng.app.STAGE_END"
    private const val REQ = 2001

    fun schedule(ctx: Context, triggerAtMillis: Long, stage: Stage, body: String) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(ctx, stage, body)
        val at = triggerAtMillis.coerceAtLeast(System.currentTimeMillis() + 500)

        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "精确闹钟被拒，降级为非精确", e)
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        }
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pendingIntent(ctx, Stage.STUDY, ""))
    }

    private fun pendingIntent(ctx: Context, stage: Stage, body: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            REQ,
            Intent(ctx, StageAlarmReceiver::class.java).apply {
                action = ACTION_STAGE_END
                putExtra("stage", stage.id)
                putExtra("stageLabel", stage.label)
                putExtra("body", body)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

/** 触觉反馈：阶段到点双轻震；保存单震 */
object Haptics {

    private fun vibrator(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    fun double(ctx: Context) {
        val v = vibrator(ctx) ?: return
        if (!v.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 90, 40), -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(longArrayOf(0, 40, 90, 40), -1)
            }
        }
    }

    fun light(ctx: Context) {
        val v = vibrator(ctx) ?: return
        if (!v.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(18)
            }
        }
    }
}

/**
 * 音效：用 SoundPool + 程序生成的短脉冲。
 * 不打包音频文件，避免版权与体积问题；用 AudioTrack 现场合成木鱼/钵声。
 */
class Chimes(private val ctx: Context) {

    private var trackLoaded = false

    /** 单次短促木鱼声（<0.8s）：低频主音 + 五度泛音，指数衰减 */
    fun playChime() {
        runCatching {
            val sampleRate = 44100
            val duration = 0.72
            val n = (sampleRate * duration).toInt()
            val buf = ShortArray(n)
            val partials = listOf(
                Triple(528.0, 0.55, 0.72),
                Triple(792.0, 0.26, 0.50),
                Triple(1188.0, 0.10, 0.32),
            )
            for (i in 0 until n) {
                val t = i.toDouble() / sampleRate
                var s = 0.0
                for ((f, amp, decay) in partials) {
                    val env = Math.exp(-t / decay)
                    s += amp * env * Math.sin(2 * Math.PI * f * t)
                }
                // 前 8ms 淡入，避免爆音
                val attack = (t / 0.008).coerceAtMost(1.0)
                buf[i] = (s * attack * Short.MAX_VALUE * 0.42).toInt().coerceIn(
                    Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()
                ).toShort()
            }

            val pool = SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()

            // 写到缓存文件再加载（SoundPool 不支持内存 buffer，避免引入额外依赖）
            val f = java.io.File(ctx.cacheDir, "chime.pcm")
            f.outputStream().use { out ->
                // 直接写 16-bit PCM 头 + 数据，作为 WAV
                writeWavHeader(out, n, sampleRate)
                val bytes = ByteArray(n * 2)
                for (i in 0 until n) {
                    val v = buf[i].toInt()
                    bytes[i * 2] = (v and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                out.write(bytes)
            }

            val soundId = pool.load(f.absolutePath, 1)
            pool.setOnLoadCompleteListener { p, _, status ->
                if (status == 0) runCatching { p.play(soundId, 1f, 1f, 0, 0, 1f) }
                trackLoaded = true
                // 3 秒后释放，避免长期占用音频焦点
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    runCatching { p.release() }
                }, 3000)
            }
        }.onFailure { Log.w("Chimes", "播放失败", it) }
    }

    private fun writeWavHeader(out: java.io.OutputStream, samples: Int, rate: Int) {
        val dataLen = samples * 2
        val totalLen = 36 + dataLen
        fun le32(v: Int) = byteArrayOf(
            (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
        )
        fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        out.write("RIFF".toByteArray())
        out.write(le32(totalLen))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(le32(16))
        out.write(le16(1))          // PCM
        out.write(le16(1))          // mono
        out.write(le32(rate))
        out.write(le32(rate * 2))
        out.write(le16(2))
        out.write(le16(16))
        out.write("data".toByteArray())
        out.write(le32(dataLen))
    }

    /** 保存确认音：短促一声 */
    fun playTick() {
        runCatching {
            val tone = android.media.ToneGenerator(AudioManager.STREAM_NOTIFICATION, 40)
            tone.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 60)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching { tone.release() }
            }, 300)
        }
    }
}
