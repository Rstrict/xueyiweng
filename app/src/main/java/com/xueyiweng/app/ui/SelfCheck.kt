package com.xueyiweng.app.ui

import android.content.Context
import com.xueyiweng.app.logic.Dates
import com.xueyiweng.app.logic.Mastery
import com.xueyiweng.app.logic.Queues
import com.xueyiweng.app.logic.Records
import com.xueyiweng.app.logic.SessionSnapshot
import com.xueyiweng.app.logic.Timer
import com.xueyiweng.app.logic.Transfer
import java.io.File

/**
 * 自检工具：在设备内直接跑一遍核心业务闭环，
 * 把结果写到 filesDir/selfcheck.txt，便于用 adb 取回核对。
 *
 * 这样不依赖 UI 自动化就能验证：
 * 建会话 → 停表 → 判分 → 排期 → 入队 → 导出 → 解析
 */
object SelfCheck {

    fun run(ctx: Context): String {
        val sb = StringBuilder()
        fun line(s: String) { sb.appendLine(s) }

        val now = System.currentTimeMillis()
        line("=== 学一问 自检 @ ${Dates.today(now)} ===")
        line("")

        // 1. 建会话
        val session = Timer.create(
            questionText = "为什么样本方差要除以 n-1？",
            sourceLocation = "概率论第2章 · 网课 14:20",
            stageSeconds = Mastery.entries.associate { "study" to 480 } +
                mapOf("recall_1" to 120, "verify" to 600, "recall_2" to 240),
            now = now,
        )
        line("1) 会话创建：stage=${session.currentStage} target=${session.stageTargetDurationSec}s")
        line("   问题=${session.questionText}")
        line("   sessionId=${session.sessionId}")

        // 2. 计时：模拟过了 90 秒
        val t90 = now + 90_000
        line("2) 计时（+90s）：elapsed=${Timer.elapsedSec(session, t90)}s " +
            "remaining=${Timer.remainingSec(session, t90)}s " +
            "progress=${"%.2f".format(Timer.progress(session, t90))}")

        // 3. 暂停 10 分钟再恢复
        val paused = Timer.pause(session, t90)
        val tPaused = t90 + 600_000
        line("3) 暂停：冻结在 ${Timer.elapsedSec(paused, tPaused)}s（应仍为 90）")
        val resumed = Timer.resume(paused, tPaused)
        line("   恢复：accumulatedPaused=${resumed.accumulatedPausedSec}s（应为 600）")
        line("   恢复后 elapsed=${Timer.elapsedSec(resumed, tPaused)}s（应仍为 90）")

        // 4. 阶段推进
        val s2 = Timer.gotoStage(resumed, com.xueyiweng.app.logic.Stage.RECALL_1, mapOf("recall_1" to 120), tPaused)
        line("4) 切阶段：${s2.currentStage} target=${s2.stageTargetDurationSec}s " +
            "累计暂停已重置=${s2.accumulatedPausedSec == 0L}")

        // 5. 生成记录（需要作答内容）
        val answered = s2.copy(
            firstAttemptDraft = "还是 1/2，但说不清为什么",
            firstAttemptType = "text",
            stuckPointDraft = "说不清独立性的严格定义",
            finalNoteDraft = "独立事件无记忆性，注意与不放回抽球区分",
        )
        val rec = Records.fromSession(answered, Mastery.NEED_HINT, tPaused)
        line("5) 落成记录：id=${rec.id}")
        line("   mastery=${rec.masteryStatus} 下次复习=${rec.nextReviewDate} " +
            "（需要提示应为明天）")

        // 6. 排期阶梯
        val r1 = com.xueyiweng.app.logic.Scheduler.next(0, 0, Mastery.INDEPENDENT, now)
        val r2 = com.xueyiweng.app.logic.Scheduler.next(r1.intervalDays, r1.consecutiveIndependent, Mastery.INDEPENDENT, now)
        val r3 = com.xueyiweng.app.logic.Scheduler.next(r2.intervalDays, r2.consecutiveIndependent, Mastery.INDEPENDENT, now)
        line("6) 排期阶梯（连续独立答出）：${r1.intervalDays} → ${r2.intervalDays} → ${r3.intervalDays} 天")
        line("   第二次后已稳固=${r2.isStable}（应为 true，连续 2 次）")
        val rh = com.xueyiweng.app.logic.Scheduler.next(9, 1, Mastery.NEED_HINT, now)
        line("   需要提示：间隔重置为 ${rh.intervalDays} 天，连续计数归零=${rh.consecutiveIndependent == 0}")
        val rl = com.xueyiweng.app.logic.Scheduler.next(3, 1, Mastery.RELEARN, now)
        line("   仍没弄懂：nextReviewDate=${rl.nextReviewDate}（应为 null，进待重学）")

        // 7. 队列筛选
        val dueList = Queues.dueForReview(
            listOf(
                rec.copy(id = "today", nextReviewDate = Dates.today(now), masteryStatus = "need_hint", isStable = false),
                rec.copy(id = "overdue3", nextReviewDate = Dates.today(now).minusDays(3), masteryStatus = "need_hint", isStable = false),
                rec.copy(id = "future", nextReviewDate = Dates.today(now).plusDays(5), masteryStatus = "need_hint", isStable = false),
                rec.copy(id = "stable", nextReviewDate = Dates.today(now), masteryStatus = "independent", isStable = true),
                rec.copy(id = "relearn", nextReviewDate = null, masteryStatus = "relearn", isStable = false),
            ),
            Dates.today(now),
        )
        line("7) 今日队列：${dueList.map { it.id }}")
        line("   逾期更久的排前面=${dueList.firstOrNull()?.id == "overdue3"}")
        line("   稳固/待重学被排除=${dueList.none { it.id == "stable" || it.id == "relearn" }}")

        // 8. 统计
        val stats = com.xueyiweng.app.logic.Stats.weekly(listOf(rec), now)
        line("8) 周统计：total=${stats.total} due=${stats.due} relearn=${stats.relearn}")

        // 9. 导出 / 解析往返
        val md = Transfer.toMarkdown(listOf(rec), now)
        line("9) Markdown 导出：${md.lines().size} 行，含标题=${md.contains("# 学一问")}")

        val json = com.xueyiweng.app.logic.JsonLite.stringify(
            mapOf("records" to listOf(mapOf(
                "id" to rec.id,
                "questionText" to rec.questionText,
                "stuckPoint" to rec.stuckPoint,
                "masteryStatus" to rec.masteryStatus,
                "reviewHistory" to emptyList<Any>(),
            )))
        )
        val parsed = Transfer.parseImport(json)
        line("10) JSON 往返：ok=${parsed.ok} 条数=${parsed.records.size} " +
            "题干一致=${parsed.records.firstOrNull()?.questionText == rec.questionText}")

        // 11. 坏数据处理
        val bad = Transfer.parseImport("""{"records":[{"questionText":"好题"},{"nope":1},null]}""")
        line("11) 坏数据容错：ok=${bad.ok} 保留=${bad.records.size} 跳过=${bad.skipped}（应为 1/2）")

        line("")
        line("=== 自检结束 ===")

        val out = sb.toString()
        runCatching { File(ctx.filesDir, "selfcheck.txt").writeText(out) }
        return out
    }
}
