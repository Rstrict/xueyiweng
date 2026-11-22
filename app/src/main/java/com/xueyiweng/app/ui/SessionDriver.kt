package com.xueyiweng.app.ui

import android.content.Context
import com.xueyiweng.app.XueYiWenApp
import com.xueyiweng.app.logic.BrainDump
import com.xueyiweng.app.logic.Dates
import com.xueyiweng.app.logic.Mastery
import com.xueyiweng.app.logic.Queues
import com.xueyiweng.app.logic.Records
import com.xueyiweng.app.logic.Stage
import com.xueyiweng.app.logic.Timer
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 会话驱动器：用 adb 直接推进状态机，绕开 UI 点击做端到端验证。
 * 验证的是"业务闭环 + 持久化 + 排期"，不替代 UI 测试。
 *
 *   adb shell am start -n <pkg>/.ui.MainActivity --es drive start --es q "问题"
 *   adb shell am start -n <pkg>/.ui.MainActivity --es drive stage --es to recall_1
 *   adb shell am start -n <pkg>/.ui.MainActivity --es drive answer --es a "盲答"
 *   adb shell am start -n <pkg>/.ui.MainActivity --es drive finish --es m need_hint
 *   adb shell am start -n <pkg>/.ui.MainActivity --es drive status
 */
object SessionDriver {

    fun run(ctx: Context, cmd: String, extras: Map<String, String?>): String {
        val app = XueYiWenApp.from(ctx)
        val repo = app.repo
        val out = StringBuilder()

        runBlocking {
            when (cmd) {
                "start" -> {
                    val settings = app.settings.current()
                    val s = Timer.create(
                        questionText = extras["q"] ?: "未命名问题",
                        sourceLocation = extras["src"] ?: "",
                        stageSeconds = settings.stageSeconds,
                    )
                    repo.saveSession(s)
                    out.appendLine("started: ${s.sessionId} stage=${s.currentStage} target=${s.stageTargetDurationSec}s")
                }

                "stage" -> {
                    val cur = repo.loadSession()
                    if (cur == null) {
                        out.appendLine("no session")
                    } else {
                        val to = Stage.fromId(extras["to"])
                        val settings = app.settings.current()
                        val next = Timer.gotoStage(cur, to, settings.stageSeconds, System.currentTimeMillis())
                        repo.saveSession(next)
                        out.appendLine("stage -> ${next.currentStage} target=${next.stageTargetDurationSec}s")
                    }
                }

                "answer" -> {
                    val cur = repo.loadSession()
                    if (cur == null) {
                        out.appendLine("no session")
                    } else {
                        val text = extras["a"] ?: ""
                        val next = if (cur.currentStage == Stage.RECALL_2.id) {
                            cur.copy(finalNoteDraft = text)
                        } else {
                            cur.copy(firstAttemptDraft = text, firstAttemptType = "text")
                        }
                        repo.saveSession(next)
                        out.appendLine("answer saved in ${next.currentStage}: $text")
                    }
                }

                "stuck" -> {
                    val cur = repo.loadSession()
                    if (cur == null) out.appendLine("no session") else {
                        val next = cur.copy(
                            firstAttemptType = "stuck",
                            stuckPointDraft = extras["s"] ?: "",
                        )
                        repo.saveSession(next)
                        out.appendLine("stuck point saved: ${next.stuckPointDraft}")
                    }
                }

                "dump" -> {
                    val cur = repo.loadSession()
                    if (cur == null) out.appendLine("no session") else {
                        val text = extras["t"] ?: "杂念"
                        val next = cur.copy(
                            brainDumps = cur.brainDumps + BrainDump(text, System.currentTimeMillis())
                        )
                        repo.saveSession(next)
                        out.appendLine("brainDump added: $text (共 ${next.brainDumps.size} 条)")
                    }
                }

                "pause" -> {
                    val cur = repo.loadSession()
                    if (cur == null) out.appendLine("no session") else {
                        val next = Timer.pause(cur, System.currentTimeMillis())
                            .copy(pauseContextNote = extras["note"] ?: "")
                        repo.saveSession(next)
                        out.appendLine("paused at ${next.stage.label}, note=${next.pauseContextNote}")
                    }
                }

                "resume" -> {
                    val cur = repo.loadSession()
                    if (cur == null) out.appendLine("no session") else {
                        val next = Timer.resume(cur, System.currentTimeMillis())
                        repo.saveSession(next)
                        out.appendLine("resumed, accumulatedPaused=${next.accumulatedPausedSec}s")
                    }
                }

                "finish" -> {
                    val cur = repo.loadSession()
                    if (cur == null) out.appendLine("no session") else {
                        val m = Mastery.fromId(extras["m"])
                        val rec = Records.fromSession(cur, m)
                        repo.upsert(rec)
                        repo.saveSession(null)
                        out.appendLine("finished: mastery=${rec.masteryStatus} next=${rec.nextReviewDate}")
                        out.appendLine("record id=${rec.id}")
                    }
                }

                "status" -> {
                    val s = repo.loadSession()
                    if (s == null) {
                        out.appendLine("no active session")
                    } else {
                        val now = System.currentTimeMillis()
                        out.appendLine("session=${s.sessionId}")
                        out.appendLine("question=${s.questionText}")
                        out.appendLine("stage=${s.stage.label} (${s.currentStage})")
                        out.appendLine("remaining=${Dates.clock(Timer.remainingSec(s, now))}")
                        out.appendLine("paused=${s.isPaused} note=${s.pauseContextNote}")
                        out.appendLine("firstAttempt=${s.firstAttemptDraft}")
                        out.appendLine("stuck=${s.stuckPointDraft}")
                        out.appendLine("finalNote=${s.finalNoteDraft}")
                        out.appendLine("dumps=${s.brainDumps.map { it.text }}")
                    }
                    val all = repo.allRecords()
                    out.appendLine("---")
                    out.appendLine("records=${all.size}")
                    all.take(5).forEach {
                        out.appendLine("  * ${it.questionText.take(20)} | ${it.mastery.label} | next=${it.nextReviewDate}")
                    }
                    out.appendLine("dueToday=${Queues.dueForReview(all, Dates.today()).size}")
                }

                "queue" -> {
                    val all = repo.allRecords()
                    val today = Dates.today()
                    out.appendLine("total=${all.size}")
                    all.forEach {
                        out.appendLine("  ${it.id} | ${it.mastery.label} | reviews=${it.reviewCount} | next=${it.nextReviewDate} | ${it.questionText.take(16)}")
                    }
                    out.appendLine("dueToday=${Queues.dueForReview(all, today).size}")
                    out.appendLine("relearn=${Queues.relearnPool(all).size}")
                    out.appendLine("stable=${Queues.stablePool(all).size}")
                }

                else -> out.appendLine("unknown command: $cmd")
            }
        }

        val text = out.toString()
        runCatching { File(ctx.filesDir, "driver_last.txt").writeText(text) }
        return text
    }
}
