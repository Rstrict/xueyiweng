package com.xueyiweng.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 逻辑层单元测试（JVM，不依赖 Android）。
 * 运行： gradlew testDebugUnitTest
 */
class LogicTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now = LocalDate.of(2026, 11, 22)
        .atTime(10, 0).atZone(zone).toInstant().toEpochMilli()

    private val stageSec = mapOf(
        "study" to 480, "recall_1" to 120, "verify" to 600, "recall_2" to 240
    )

    /* ---------- 日期 ---------- */

    @Test
    fun `today uses local zone not utc slice`() {
        // 本地 23:30 仍是当天，UTC 可能已跨日
        val t = LocalDate.of(2026, 11, 22).atTime(23, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 11, 22), Dates.today(t, zone))
    }

    @Test
    fun `human date relative labels`() {
        assertEquals("今天", Dates.human(LocalDate.of(2026, 11, 22), LocalDate.of(2026, 11, 22)))
        assertEquals("明天", Dates.human(LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 22)))
        assertEquals("3 天后", Dates.human(LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 22)))
        assertEquals("逾期 2 天", Dates.human(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 22)))
    }

    @Test
    fun `clock formatting`() {
        assertEquals("00:00", Dates.clock(0))
        assertEquals("01:05", Dates.clock(65))
        assertEquals("10:00", Dates.clock(600))
        assertEquals("1:02:05", Dates.clock(3725))
        assertEquals("00:00", Dates.clock(-5))
    }

    /* ---------- 计时 ---------- */

    private fun session() = Timer.create("测试问题", "P42", stageSec, now)

    @Test
    fun `elapsed and remaining from absolute timestamps`() {
        val s = session()
        assertEquals(60L, Timer.elapsedSec(s, now + 60_000))
        assertEquals(420L, Timer.remainingSec(s, now + 60_000))
        assertEquals(0.5f, Timer.progress(s, now + 240_000), 0.01f)
    }

    @Test
    fun `overtime gives negative remaining and clamps progress`() {
        val s = session()
        assertEquals(-20L, Timer.remainingSec(s, now + 500_000))
        assertEquals(1.0f, Timer.progress(s, now + 500_000), 0.001f)
    }

    @Test
    fun `pause freezes then resume deducts paused time`() {
        var s = session()
        s = Timer.pause(s, now + 60_000)
        // 暂停 10 分钟，仍冻结在 60
        assertEquals(60L, Timer.elapsedSec(s, now + 660_000))

        s = Timer.resume(s, now + 660_000)
        assertEquals(600L, s.accumulatedPausedSec)
        assertEquals(60L, Timer.elapsedSec(s, now + 660_000))
        // 再走 30 秒
        assertEquals(90L, Timer.elapsedSec(s, now + 690_000))
        assertEquals(390L, Timer.remainingSec(s, now + 690_000))
    }

    @Test
    fun `screen lock for five minutes realigns on return`() {
        val s = session()
        // 线程被挂起整段，回来按当前时间戳直接对齐
        assertEquals(300L, Timer.elapsedSec(s, now + 300_000))
        assertEquals(180L, Timer.remainingSec(s, now + 300_000))
    }

    @Test
    fun `goto stage resets pause accumulator`() {
        var s = session()
        s = Timer.pause(s, now + 10_000)
        s = Timer.gotoStage(s, Stage.RECALL_1, stageSec, now + 20_000)
        assertEquals("recall_1", s.currentStage)
        assertEquals(120, s.stageTargetDurationSec)
        assertEquals(0L, s.accumulatedPausedSec)
        assertFalse(s.isPaused)
        assertNull(s.pausedAtTimestamp)
    }

    @Test
    fun `stage navigation boundaries`() {
        assertEquals(Stage.RECALL_1, Stage.next("study"))
        assertEquals(Stage.VERIFY, Stage.next("recall_1"))
        assertEquals(Stage.RECALL_2, Stage.next("verify"))
        assertNull(Stage.next("recall_2"))
        assertNull(Stage.prev("study"))
        assertEquals(Stage.RECALL_1, Stage.prev("verify"))
    }

    /* ---------- 排期 ---------- */

    @Test
    fun `first independent is three days`() {
        val r = Scheduler.next(0, 0, Mastery.INDEPENDENT, now, zone)
        assertEquals(3, r.intervalDays)
        assertEquals(LocalDate.of(2026, 11, 25), r.nextReviewDate)
        assertEquals(1, r.consecutiveIndependent)
        assertFalse(r.isStable)
    }

    @Test
    fun `two consecutive independent becomes stable`() {
        val first = Scheduler.next(0, 0, Mastery.INDEPENDENT, now, zone)
        val second = Scheduler.next(first.intervalDays, first.consecutiveIndependent, Mastery.INDEPENDENT, now, zone)
        assertEquals(2, second.consecutiveIndependent)
        assertTrue(second.isStable)
    }

    @Test
    fun `interval multiplies by three and caps`() {
        var r = Scheduler.next(0, 0, Mastery.INDEPENDENT, now, zone)
        r = Scheduler.next(r.intervalDays, r.consecutiveIndependent, Mastery.INDEPENDENT, now, zone)
        assertEquals(9, r.intervalDays)
        r = Scheduler.next(r.intervalDays, r.consecutiveIndependent, Mastery.INDEPENDENT, now, zone)
        assertEquals(27, r.intervalDays)

        val capped = Scheduler.next(Schedule.MAX_INTERVAL_DAYS, 1, Mastery.INDEPENDENT, now, zone)
        assertEquals(Schedule.MAX_INTERVAL_DAYS, capped.intervalDays)
    }

    @Test
    fun `need hint resets to tomorrow`() {
        val r = Scheduler.next(9, 1, Mastery.NEED_HINT, now, zone)
        assertEquals(1, r.intervalDays)
        assertEquals(LocalDate.of(2026, 11, 23), r.nextReviewDate)
        assertEquals(0, r.consecutiveIndependent)
        assertFalse(r.isStable)
    }

    @Test
    fun `relearn has no next date`() {
        val r = Scheduler.next(3, 0, Mastery.RELEARN, now, zone)
        assertEquals(Mastery.RELEARN.id, r.masteryStatus)
        assertNull(r.nextReviewDate)
    }

    /* ---------- 记录 ---------- */

    @Test
    fun `record from session captures both attempts`() {
        val s = Timer.create(
            questionText = "为什么样本方差要除以 n-1？",
            sourceLocation = "概率论第2章",
            stageSeconds = stageSec,
            now = now,
        ).copy(
            firstAttemptDraft = "还是 1/2，说不清为什么",
            firstAttemptType = "text",
            stuckPointDraft = "说不清独立性定义",
            finalNoteDraft = "独立事件无记忆性",
            brainDumps = listOf(BrainDump("回微信", now)),
        )
        val rec = Records.fromSession(s, Mastery.NEED_HINT, now, zone)
        assertEquals("为什么样本方差要除以 n-1？", rec.questionText)
        assertEquals("还是 1/2，说不清为什么", rec.firstAttemptContent)
        assertEquals("说不清独立性定义", rec.stuckPoint)
        assertEquals("独立事件无记忆性", rec.finalNote)
        assertEquals(LocalDate.of(2026, 11, 23), rec.nextReviewDate)
        assertEquals(1, rec.brainDumpsOnCreate.size)
    }

    @Test
    fun `apply review appends history and merges stuck points`() {
        val rec = QuestionRecord(
            id = "q1", questionText = "x", createdAt = now,
            stuckPoint = "原始卡点", intervalDays = 0, consecutiveIndependent = 0,
        )
        val out = Records.applyReview(rec, Mastery.INDEPENDENT, "新卡点", now = now, zone = zone)
        assertEquals(1, out.reviewCount)
        assertEquals(1, out.reviewHistory.size)
        assertEquals("原始卡点\n新卡点", out.stuckPoint)
        assertEquals(3, out.intervalDays)
        // 入参不可变
        assertEquals(0, rec.reviewCount)
        assertTrue(rec.reviewHistory.isEmpty())
    }

    @Test
    fun `blank stuck point does not add empty line`() {
        val rec = QuestionRecord(id = "q", questionText = "x", createdAt = now, stuckPoint = "旧")
        val out = Records.applyReview(rec, Mastery.NEED_HINT, "   ", now = now, zone = zone)
        assertEquals("旧", out.stuckPoint)
    }

    /* ---------- 队列 ---------- */

    private fun rec(
        id: String,
        due: LocalDate? = LocalDate.of(2026, 11, 22),
        mastery: String = Mastery.NEED_HINT.id,
        stable: Boolean = false,
        history: List<ReviewEntry> = emptyList(),
    ) = QuestionRecord(
        id = id, questionText = "q-$id", createdAt = now,
        masteryStatus = mastery, isStable = stable,
        nextReviewDate = due, reviewHistory = history,
        intervalDays = 1, lastReviewedAt = now,
    )

    @Test
    fun `due queue includes overdue and excludes stable and relearn`() {
        val list = listOf(
            rec("today"),
            rec("overdue3", LocalDate.of(2026, 11, 19)),
            rec("future", LocalDate.of(2026, 11, 27)),
            rec("stable", stable = true, mastery = Mastery.INDEPENDENT.id),
            rec("relearn", due = null, mastery = Mastery.RELEARN.id),
        )
        val due = Queues.dueForReview(list, LocalDate.of(2026, 11, 22))
        assertEquals(listOf("overdue3", "today"), due.map { it.id })
    }

    @Test
    fun `high frequency stuck needs two consecutive non independent`() {
        val two = listOf(
            ReviewEntry(1, Mastery.NEED_HINT.id),
            ReviewEntry(2, Mastery.NEED_HINT.id),
        )
        val mixed = listOf(
            ReviewEntry(1, Mastery.INDEPENDENT.id),
            ReviewEntry(2, Mastery.NEED_HINT.id),
        )
        assertEquals(listOf("a"), Queues.highFreqStuck(listOf(
            rec("a", history = two), rec("b", history = mixed),
        )).map { it.id })
    }

    @Test
    fun `retention rate is null without history`() {
        assertNull(Queues.retentionRate(emptyList()))
        assertEquals(0.75, Queues.retentionRate(listOf(
            rec("a", history = listOf(
                ReviewEntry(1, Mastery.INDEPENDENT.id),
                ReviewEntry(2, Mastery.NEED_HINT.id),
                ReviewEntry(3, Mastery.INDEPENDENT.id),
                ReviewEntry(4, Mastery.INDEPENDENT.id),
            ))
        ))!!, 0.001)
    }

    /* ---------- JSON ---------- */

    @Test
    fun `json round trip lite parser`() {
        val obj = mapOf(
            "s" to "含\"引号\"与\n换行",
            "n" to 42L,
            "d" to 1.5,
            "b" to true,
            "nil" to null,
            "arr" to listOf(1L, 2L, 3L),
        )
        val text = JsonLite.stringify(obj)
        @Suppress("UNCHECKED_CAST")
        val back = JsonLite.parse(text) as Map<String, Any?>
        assertEquals("含\"引号\"与\n换行", back["s"])
        assertEquals(42L, back["n"])
        assertEquals(1.5, back["d"] as Double, 0.0001)
        assertEquals(true, back["b"])
        assertNull(back["nil"])
        assertEquals(listOf(1L, 2L, 3L), back["arr"])
    }

    @Test
    fun `parse import skips bad items`() {
        val r = Transfer.parseImport("""{"records":[{"questionText":"好题"},{"nope":1},null,{"questionText":""}]}""")
        assertTrue(r.ok)
        assertEquals(1, r.records.size)
        assertEquals(3, r.skipped)
    }

    @Test
    fun `parse import rejects bad json`() {
        val r = Transfer.parseImport("{不是json")
        assertFalse(r.ok)
        assertNotNull(r.error)
    }

    @Test
    fun `merge keeps advanced progress`() {
        val a = QuestionRecord(id = "1", questionText = "题一", createdAt = now, reviewCount = 2)
        val b = QuestionRecord(id = "2", questionText = "题二", createdAt = now, reviewCount = 0)
        val out = Transfer.merge(
            listOf(a, b),
            listOf(
                QuestionRecord(id = "1", questionText = "题一", createdAt = now, reviewCount = 1),
                QuestionRecord(id = "3", questionText = "题三", createdAt = now),
            ),
        )
        assertEquals(1, out.added)
        assertEquals(3, out.records.size)
        assertEquals(2, out.records.first { it.id == "1" }.reviewCount)
    }

    @Test
    fun `markdown export has sections`() {
        val md = Transfer.toMarkdown(
            listOf(
                rec("a", mastery = Mastery.NEED_HINT.id).copy(finalNote = "结论A"),
                rec("b", due = null, mastery = Mastery.RELEARN.id),
            ),
            now,
        )
        assertTrue(md.contains("# 学一问 · 学习记录导出"))
        assertTrue(md.contains("## 待重学（1）"))
        assertTrue(md.contains("## 待巩固（1）"))
        assertTrue(md.contains("结论A"))
    }

    @Test
    fun `stage defaults sum to 25 minutes`() {
        val total = Stage.entries.sumOf { it.defaultSec }
        assertEquals(8 * 60 + 2 * 60 + 10 * 60 + 4 * 60, total)
        assertEquals(4, Stage.entries.size)
    }
}
