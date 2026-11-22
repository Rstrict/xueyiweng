package com.xueyiweng.app.logic

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * 学一问 · 纯逻辑层（无 Android 依赖，可 JVM 单测）
 *
 * 设计约束：
 *  - 所有时间计算走绝对时间戳，绝不靠 tick 累加（切后台/息屏不会漂移）
 *  - 所有排期函数是纯函数，不修改入参，返回值即结果
 *  - 日期一律用本地时区的 LocalDate，不用 UTC 切片
 */

/* ============================================================
   常量
   ============================================================ */

enum class Stage(
    val id: String,
    val label: String,
    val shortLabel: String,
    val defaultSec: Int,
    val hint: String,
) {
    STUDY("study", "带着问题学", "学", 8 * 60, "只看这一道题，找它的答案"),
    RECALL_1("recall_1", "第一次盲答", "盲", 2 * 60, "合上资料，能写多少写多少"),
    VERIFY("verify", "核对与重练", "核", 10 * 60, "对着盲区重新推导 / 背下来"),
    RECALL_2("recall_2", "闭环再答", "答", 4 * 60, "再收起资料，完整顺一遍");

    val index: Int get() = ordinal
    val number: Int get() = ordinal + 1

    companion object {
        fun fromId(id: String?): Stage = entries.firstOrNull { it.id == id } ?: STUDY
        fun next(id: String?): Stage? {
            val i = entries.indexOfFirst { it.id == id }
            return if (i < 0 || i >= entries.size - 1) null else entries[i + 1]
        }

        fun prev(id: String?): Stage? {
            val i = entries.indexOfFirst { it.id == id }
            return if (i <= 0) null else entries[i - 1]
        }
    }
}

enum class Mastery(val id: String, val label: String, val desc: String) {
    INDEPENDENT("independent", "独立答出", "不靠提示完整答出"),
    NEED_HINT("need_hint", "需要提示", "卡了一下才想起"),
    RELEARN("relearn", "仍没弄懂", "放进待重学区");

    companion object {
        fun fromId(id: String?): Mastery = entries.firstOrNull { it.id == id } ?: NEED_HINT
    }
}

object Schedule {
    /** 新题首次独立答出 → 3 天后 */
    const val FIRST_INTERVAL_DAYS = 3

    /** 之后每次 ×3 */
    const val MULTIPLIER = 3

    /** 需要提示 → 次日 */
    const val HINT_INTERVAL_DAYS = 1

    /** 连续 2 次独立 → 已稳固 */
    const val STABLE_STREAK = 2

    /** 间隔上限，防止爆炸 */
    const val MAX_INTERVAL_DAYS = 180
}

/* ============================================================
   日期工具
   ============================================================ */

object Dates {
    fun today(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

    fun toEpochMillis(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun daysBetween(a: LocalDate, b: LocalDate): Long =
        java.time.temporal.ChronoUnit.DAYS.between(b, a)

    /** 相对日期的人类描述 */
    fun human(date: LocalDate?, today: LocalDate = today()): String {
        if (date == null) return ""
        val d = daysBetween(date, today)
        return when {
            d == 0L -> "今天"
            d == 1L -> "明天"
            d == 2L -> "后天"
            d == -1L -> "昨天"
            d < 0 -> "逾期 ${-d} 天"
            d <= 6L -> "$d 天后"
            else -> "${date.monthValue}月${date.dayOfMonth}日"
        }
    }

    /** 秒 → mm:ss，超过 1 小时显示 h:mm:ss */
    fun clock(sec: Long): String {
        val s = sec.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val ss = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, ss)
        else String.format("%02d:%02d", m, ss)
    }
}

/* ============================================================
   会话快照（断点恢复的载体）
   ============================================================ */

data class BrainDump(
    val text: String,
    val createdAt: Long,
    val resolved: Boolean = false,
)

data class SessionSnapshot(
    val sessionId: String,
    val questionText: String,
    val sourceLocation: String = "",
    val currentStage: String = Stage.STUDY.id,
    val stageStartTimestamp: Long,
    val stageTargetDurationSec: Int,
    val isPaused: Boolean = false,
    val pausedAtTimestamp: Long? = null,
    val accumulatedPausedSec: Long = 0,
    val pauseContextNote: String = "",
    val firstAttemptDraft: String = "",
    val firstAttemptType: String? = null, // text | paper_oral | stuck
    val stuckPointDraft: String = "",
    val finalNoteDraft: String = "",
    val brainDumps: List<BrainDump> = emptyList(),
    val startedAt: Long,
    val overtimeNotified: Boolean = false,
) {
    val stage: Stage get() = Stage.fromId(currentStage)
}

/* ============================================================
   计时：全部基于绝对时间戳
   ============================================================ */

object Timer {
    /**
     * 已流逝秒数。
     * 剩余 = 目标 - (now - 阶段开始 - 累计暂停)
     * 暂停态下冻结在 pausedAt 之前。
     */
    fun elapsedSec(s: SessionSnapshot, now: Long): Long {
        val ref = if (s.isPaused) (s.pausedAtTimestamp ?: now) else now
        val raw = (ref - s.stageStartTimestamp) / 1000.0
        return (raw - s.accumulatedPausedSec).coerceAtLeast(0.0).toLong()
    }

    /** 剩余秒数；超时返回负值 */
    fun remainingSec(s: SessionSnapshot, now: Long): Long =
        s.stageTargetDurationSec.toLong() - elapsedSec(s, now)

    /** 进度 0f..1f，超时封顶 1 */
    fun progress(s: SessionSnapshot, now: Long): Float {
        val target = s.stageTargetDurationSec.coerceAtLeast(1)
        return (elapsedSec(s, now).toDouble() / target).coerceIn(0.0, 1.0).toFloat()
    }

    fun isOvertime(s: SessionSnapshot, now: Long): Boolean = remainingSec(s, now) <= 0

    fun pause(s: SessionSnapshot, now: Long): SessionSnapshot =
        if (s.isPaused) s else s.copy(isPaused = true, pausedAtTimestamp = now)

    fun resume(s: SessionSnapshot, now: Long): SessionSnapshot {
        if (!s.isPaused) return s
        val gap = ((now - (s.pausedAtTimestamp ?: now)) / 1000.0).coerceAtLeast(0.0).toLong()
        return s.copy(
            isPaused = false,
            pausedAtTimestamp = null,
            accumulatedPausedSec = s.accumulatedPausedSec + gap,
        )
    }

    /** 切换阶段：重置起点与暂停累计，按设置写入新时长 */
    fun gotoStage(s: SessionSnapshot, stage: Stage, stageSeconds: Map<String, Int>, now: Long): SessionSnapshot =
        s.copy(
            currentStage = stage.id,
            stageStartTimestamp = now,
            accumulatedPausedSec = 0,
            stageTargetDurationSec = stageSeconds[stage.id] ?: stage.defaultSec,
            isPaused = false,
            pausedAtTimestamp = null,
            overtimeNotified = false,
        )

    fun create(
        questionText: String,
        sourceLocation: String,
        stageSeconds: Map<String, Int>,
        now: Long = System.currentTimeMillis(),
    ): SessionSnapshot = SessionSnapshot(
        sessionId = Ids.session(now),
        questionText = questionText.trim(),
        sourceLocation = sourceLocation.trim(),
        currentStage = Stage.STUDY.id,
        stageStartTimestamp = now,
        stageTargetDurationSec = stageSeconds[Stage.STUDY.id] ?: Stage.STUDY.defaultSec,
        startedAt = now,
    )
}

/* ============================================================
   ID 生成
   ============================================================ */

object Ids {
    private val seq = AtomicInteger(0)

    private fun stamp(now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val d = Instant.ofEpochMilli(now).atZone(zone)
        return "%04d%02d%02d".format(d.year, d.monthValue, d.dayOfMonth)
    }

    /** 前缀_日期_毫秒尾_序号，保证同毫秒不碰撞 */
    fun make(prefix: String, now: Long): String {
        val n = seq.updateAndGet { if (it >= 9999) 1 else it + 1 }
        return "${prefix}_${stamp(now)}_%05d%03d".format(now % 100000, n)
    }

    fun session(now: Long) = make("s", now)
    fun question(now: Long) = make("q", now)
}

/* ============================================================
   排期引擎
   ============================================================ */

data class ScheduleResult(
    val masteryStatus: String,
    val intervalDays: Int,
    val consecutiveIndependent: Int,
    val isStable: Boolean,
    val nextReviewDate: LocalDate?,
    val lastReviewedAt: Long,
)

object Scheduler {
    /**
     * 依据一次自评计算下一次复习安排。纯函数。
     */
    fun next(
        prevIntervalDays: Int,
        prevStreak: Int,
        result: Mastery,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): ScheduleResult {
        val today = Dates.today(now, zone)
        return when (result) {
            Mastery.INDEPENDENT -> {
                val interval = if (prevIntervalDays > 0) {
                    (prevIntervalDays * Schedule.MULTIPLIER).coerceAtMost(Schedule.MAX_INTERVAL_DAYS)
                } else Schedule.FIRST_INTERVAL_DAYS
                val streak = prevStreak + 1
                ScheduleResult(
                    masteryStatus = Mastery.INDEPENDENT.id,
                    intervalDays = interval,
                    consecutiveIndependent = streak,
                    isStable = streak >= Schedule.STABLE_STREAK,
                    nextReviewDate = today.plusDays(interval.toLong()),
                    lastReviewedAt = now,
                )
            }

            Mastery.NEED_HINT -> ScheduleResult(
                masteryStatus = Mastery.NEED_HINT.id,
                intervalDays = Schedule.HINT_INTERVAL_DAYS,
                consecutiveIndependent = 0,
                isStable = false,
                nextReviewDate = today.plusDays(Schedule.HINT_INTERVAL_DAYS.toLong()),
                lastReviewedAt = now,
            )

            Mastery.RELEARN -> ScheduleResult(
                masteryStatus = Mastery.RELEARN.id,
                intervalDays = 0,
                consecutiveIndependent = 0,
                isStable = false,
                nextReviewDate = null,
                lastReviewedAt = now,
            )
        }
    }

    /** 按钮下方直接印的人类预告 */
    fun label(
        result: Mastery,
        prevIntervalDays: Int = 0,
        prevStreak: Int = 0,
        now: Long = System.currentTimeMillis(),
    ): String {
        if (result == Mastery.RELEARN) return "移入待重学"
        val r = next(prevIntervalDays, prevStreak, result, now)
        val d = r.intervalDays
        return when {
            d == 1 -> "明天"
            d == 3 -> "3 天后"
            d % 7 == 0 -> "${d / 7} 周后"
            else -> "$d 天后"
        }
    }
}

/* ============================================================
   问题记录
   ============================================================ */

data class ReviewEntry(
    val reviewedAt: Long,
    val result: String,
    val note: String = "",
)

data class QuestionRecord(
    val id: String,
    val questionText: String,
    val sourceLocation: String = "",
    val subjectTag: String = "",
    val createdAt: Long,
    val firstAttemptType: String = "text",
    val firstAttemptContent: String = "",
    val stuckPoint: String = "",
    val finalNote: String = "",
    val masteryStatus: String = Mastery.NEED_HINT.id,
    val intervalDays: Int = 0,
    val consecutiveIndependent: Int = 0,
    val isStable: Boolean = false,
    val nextReviewDate: LocalDate? = null,
    val lastReviewedAt: Long? = null,
    val reviewCount: Int = 0,
    val reviewHistory: List<ReviewEntry> = emptyList(),
    val brainDumpsOnCreate: List<String> = emptyList(),
    val sessionDurationSec: Int = 0,
) {
    val mastery: Mastery get() = Mastery.fromId(masteryStatus)
}

object Records {
    /** 由一次完整学习轮生成记录 */
    fun fromSession(
        s: SessionSnapshot,
        result: Mastery,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): QuestionRecord {
        val sched = Scheduler.next(0, 0, result, now, zone)
        val duration = ((now - s.startedAt) / 1000 - s.accumulatedPausedSec).coerceAtLeast(0).toInt()
        return QuestionRecord(
            id = Ids.question(now),
            questionText = s.questionText,
            sourceLocation = s.sourceLocation,
            createdAt = s.startedAt,
            firstAttemptType = s.firstAttemptType
                ?: if (s.firstAttemptDraft.isNotBlank()) "text" else "paper_oral",
            firstAttemptContent = s.firstAttemptDraft.trim(),
            stuckPoint = s.stuckPointDraft.trim(),
            finalNote = s.finalNoteDraft.trim(),
            masteryStatus = sched.masteryStatus,
            intervalDays = sched.intervalDays,
            consecutiveIndependent = sched.consecutiveIndependent,
            isStable = sched.isStable,
            nextReviewDate = sched.nextReviewDate,
            lastReviewedAt = now,
            reviewCount = 0,
            reviewHistory = emptyList(),
            brainDumpsOnCreate = s.brainDumps.map { it.text },
            sessionDurationSec = duration,
        )
    }

    /** 一次复习结果写回（不可变） */
    fun applyReview(
        r: QuestionRecord,
        result: Mastery,
        newStickingPoints: String = "",
        note: String = "",
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): QuestionRecord {
        val sched = Scheduler.next(r.intervalDays, r.consecutiveIndependent, result, now, zone)
        val mergedStuck = when {
            newStickingPoints.isBlank() -> r.stuckPoint
            r.stuckPoint.isBlank() -> newStickingPoints.trim()
            else -> r.stuckPoint + "\n" + newStickingPoints.trim()
        }
        return r.copy(
            masteryStatus = sched.masteryStatus,
            intervalDays = sched.intervalDays,
            consecutiveIndependent = sched.consecutiveIndependent,
            isStable = sched.isStable,
            nextReviewDate = sched.nextReviewDate,
            lastReviewedAt = now,
            reviewCount = r.reviewCount + 1,
            reviewHistory = r.reviewHistory + ReviewEntry(now, result.id, note.trim()),
            stuckPoint = mergedStuck,
        )
    }
}

/* ============================================================
   队列筛选
   ============================================================ */

object Queues {
    /** 今日待复习：到期且未稳固。逾期自动顺延进来，不惩罚。 */
    fun dueForReview(
        records: List<QuestionRecord>,
        today: LocalDate = Dates.today(),
    ): List<QuestionRecord> = records
        .filter { it.nextReviewDate != null }
        .filter { it.masteryStatus != Mastery.RELEARN.id }
        .filter { !it.isStable }
        .filter { Dates.daysBetween(it.nextReviewDate!!, today) <= 0 }
        .sortedWith(
            compareByDescending<QuestionRecord> { Dates.daysBetween(today, it.nextReviewDate!!) }
                .thenBy { it.lastReviewedAt ?: 0L }
        )

    fun relearnPool(records: List<QuestionRecord>): List<QuestionRecord> =
        records.filter { it.masteryStatus == Mastery.RELEARN.id }
            .sortedBy { it.lastReviewedAt ?: 0L }

    fun stablePool(records: List<QuestionRecord>): List<QuestionRecord> =
        records.filter { it.isStable }.sortedByDescending { it.lastReviewedAt ?: 0L }

    /** 高频卡点：最近两次都不是独立答出 */
    fun highFreqStuck(records: List<QuestionRecord>): List<QuestionRecord> =
        records.filter { r ->
            val h = r.reviewHistory
            h.size >= 2 && h.takeLast(2).all {
                it.result == Mastery.NEED_HINT.id || it.result == Mastery.RELEARN.id
            }
        }

    /** 独立答出率（指标 3 的分母口径） */
    fun retentionRate(records: List<QuestionRecord>): Double? {
        var indep = 0
        var total = 0
        for (r in records) for (h in r.reviewHistory) {
            if (h.result == Mastery.INDEPENDENT.id) indep++
            total++
        }
        return if (total == 0) null else indep.toDouble() / total
    }
}

data class WeeklyStats(
    val learned: Int = 0,
    val reviewed: Int = 0,
    val stable: Int = 0,
    val stuck: Int = 0,
    val relearn: Int = 0,
    val due: Int = 0,
    val total: Int = 0,
)

object Stats {
    /** 7 天滚动窗口（含今天） */
    fun weekly(
        records: List<QuestionRecord>,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): WeeklyStats {
        val today = Dates.today(now, zone)
        val from = today.minusDays(6)
        val fromTs = Dates.toEpochMillis(from, zone)
        return WeeklyStats(
            learned = records.count { it.createdAt >= fromTs },
            reviewed = records.sumOf { r -> r.reviewHistory.count { it.reviewedAt >= fromTs } },
            stable = Queues.stablePool(records).size,
            stuck = Queues.highFreqStuck(records).size,
            relearn = Queues.relearnPool(records).size,
            due = Queues.dueForReview(records, today).size,
            total = records.size,
        )
    }
}

/* ============================================================
   导入导出（JSON）
   ============================================================ */

object Transfer {
    const val VERSION = 1
    const val APP_ID = "xueyiweng"

    /** 宽容解析：坏条目跳过并计数，缺字段补默认值 */
    fun parseImport(json: String, zone: ZoneId = ZoneId.systemDefault()): ImportResult {
        val root = runCatching { JsonLite.parse(json) }.getOrNull()
            ?: return ImportResult(false, emptyList(), 0, "不是合法的 JSON 文件")

        val arr = when (root) {
            is List<*> -> root
            is Map<*, *> -> root["records"] as? List<*>
            else -> null
        } ?: return ImportResult(false, emptyList(), 0, "找不到 records 数组")

        val out = mutableListOf<QuestionRecord>()
        var skipped = 0
        for (item in arr) {
            val m = item as? Map<*, *>
            val text = (m?.get("questionText") as? String)?.trim()
            if (m == null || text.isNullOrEmpty()) { skipped++; continue }
            out += recordFromMap(m, text, zone)
        }
        return ImportResult(true, out, skipped, null)
    }

    private fun recordFromMap(m: Map<*, *>, text: String, zone: ZoneId): QuestionRecord {
        val now = System.currentTimeMillis()
        fun s(k: String, d: String = "") = (m[k] as? String) ?: d
        fun i(k: String, d: Int = 0) = (m[k] as? Number)?.toInt() ?: d
        fun b(k: String) = (m[k] as? Boolean) ?: false
        fun l(k: String): Long? = (m[k] as? Number)?.toLong()

        val dateStr = s("nextReviewDate")
        val parsedDate = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: Dates.today(now, zone)

        val history = (m["reviewHistory"] as? List<*>)?.mapNotNull { e ->
            val em = e as? Map<*, *> ?: return@mapNotNull null
            val res = em["result"] as? String ?: return@mapNotNull null
            ReviewEntry((em["reviewedAt"] as? Number)?.toLong() ?: now, res, em["note"] as? String ?: "")
        } ?: emptyList()

        val first = m["firstAttempt"] as? Map<*, *>

        return QuestionRecord(
            id = s("id").ifEmpty { Ids.question(now) },
            questionText = text,
            sourceLocation = s("sourceLocation"),
            subjectTag = s("subjectTag"),
            createdAt = l("createdAt") ?: now,
            firstAttemptType = (first?.get("type") as? String) ?: "text",
            firstAttemptContent = (first?.get("content") as? String) ?: "",
            stuckPoint = s("stuckPoint"),
            finalNote = s("finalNote"),
            masteryStatus = Mastery.fromId(s("masteryStatus")).id,
            intervalDays = i("intervalDays"),
            consecutiveIndependent = i("consecutiveIndependent"),
            isStable = b("isStable"),
            nextReviewDate = parsedDate,
            lastReviewedAt = l("lastReviewedAt"),
            reviewCount = i("reviewCount"),
            reviewHistory = history,
        )
    }

    /** 合并：同 id 以复习进度更靠前者为准 */
    fun merge(existing: List<QuestionRecord>, incoming: List<QuestionRecord>): MergeResult {
        val byId = existing.associateBy { it.id }.toMutableMap()
        var added = 0
        var updated = 0
        for (inc in incoming) {
            val cur = byId[inc.id]
            if (cur == null) { byId[inc.id] = inc; added++ }
            else if (inc.reviewCount >= cur.reviewCount) { byId[inc.id] = inc; updated++ }
        }
        return MergeResult(byId.values.toList(), added, updated)
    }

    /** Markdown 导出：可直接贴进 Notion / Obsidian / 打印 */
    fun toMarkdown(records: List<QuestionRecord>, now: Long = System.currentTimeMillis()): String {
        val sb = StringBuilder()
        fun fmt(ts: Long): String =
            Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
                .toLocalDate().toString()

        sb.appendLine("# 学一问 · 学习记录导出")
        sb.appendLine()
        sb.appendLine("> 导出时间：${Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDateTime()} · 共 ${records.size} 题")
        sb.appendLine()

        val groups = linkedMapOf(
            Mastery.RELEARN.id to "待重学",
            Mastery.NEED_HINT.id to "待巩固",
            Mastery.INDEPENDENT.id to "已答出",
        )
        for ((key, title) in groups) {
            val arr = records.filter { it.masteryStatus == key }.sortedByDescending { it.createdAt }
            if (arr.isEmpty()) continue
            sb.appendLine("## $title（${arr.size}）")
            sb.appendLine()
            for (r in arr) {
                sb.appendLine("### ${r.questionText}")
                sb.appendLine()
                if (r.sourceLocation.isNotBlank()) sb.appendLine("- **资料位置**：${r.sourceLocation}")
                sb.appendLine("- **创建**：${fmt(r.createdAt)}")
                r.nextReviewDate?.let { sb.appendLine("- **下次复习**：$it") }
                sb.appendLine("- **复习次数**：${r.reviewCount}")
                if (r.firstAttemptContent.isNotBlank()) {
                    sb.appendLine()
                    sb.appendLine("**首次作答**")
                    sb.appendLine()
                    r.firstAttemptContent.lineSequence().forEach { sb.appendLine("> $it") }
                }
                if (r.stuckPoint.isNotBlank()) {
                    sb.appendLine()
                    sb.appendLine("**卡点**：${r.stuckPoint}")
                }
                if (r.finalNote.isNotBlank()) {
                    sb.appendLine()
                    sb.appendLine("**修正 / 结论**")
                    sb.appendLine()
                    r.finalNote.lineSequence().forEach { sb.appendLine("> $it") }
                }
                sb.appendLine()
            }
        }
        if (records.isEmpty()) sb.appendLine("_还没有任何记录。_")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("由「学一问」生成 · 以问题为锚点的学习闭环")
        return sb.toString()
    }
}

data class ImportResult(
    val ok: Boolean,
    val records: List<QuestionRecord>,
    val skipped: Int,
    val error: String?,
)

data class MergeResult(
    val records: List<QuestionRecord>,
    val added: Int,
    val updated: Int,
)

/* ============================================================
   极简 JSON（不引第三方库，保持依赖最小）
   ============================================================ */

object JsonLite {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        if (!p.atEnd()) throw IllegalArgumentException("JSON 尾部有多余内容")
        return v
    }

    fun stringify(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v)
            is Int, is Long, is Short -> sb.append(v)
            is Float -> sb.append(if (v % 1f == 0f) v.toLong().toString() else v.toString())
            is Double -> sb.append(if (v % 1.0 == 0.0) v.toLong().toString() else v.toString())
            is Number -> sb.append(v)
            is String -> writeString(sb, v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    write(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, item)
                }
                sb.append(']')
            }
            else -> writeString(sb, v.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd() = i >= s.length

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun readValue(): Any? {
            skipWs()
            if (atEnd()) throw IllegalArgumentException("JSON 意外结束")
            return when (val c = s[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> if (c == '-' || c.isDigit()) readNumber()
                else throw IllegalArgumentException("JSON 非法字符 '$c'")
            }
        }

        private fun expect(word: String) {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("期望 $word")
            i += word.length
        }

        private fun readObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            i++ // {
            skipWs()
            if (!atEnd() && s[i] == '}') { i++; return map }
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                if (atEnd() || s[i] != ':') throw IllegalArgumentException("缺少 ':'")
                i++
                map[key] = readValue()
                skipWs()
                if (atEnd()) throw IllegalArgumentException("对象未闭合")
                when (s[i]) {
                    ',' -> { i++ }
                    '}' -> { i++; return map }
                    else -> throw IllegalArgumentException("对象内非法字符 '${s[i]}'")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val list = mutableListOf<Any?>()
            i++ // [
            skipWs()
            if (!atEnd() && s[i] == ']') { i++; return list }
            while (true) {
                list += readValue()
                skipWs()
                if (atEnd()) throw IllegalArgumentException("数组未闭合")
                when (s[i]) {
                    ',' -> { i++ }
                    ']' -> { i++; return list }
                    else -> throw IllegalArgumentException("数组内非法字符 '${s[i]}'")
                }
            }
        }

        private fun readString(): String {
            if (s[i] != '"') throw IllegalArgumentException("缺少字符串引号")
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                when (val c = s[i]) {
                    '"' -> { i++; return sb.toString() }
                    '\\' -> {
                        i++
                        when (val e = s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                val hex = s.substring(i + 1, i + 5)
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("非法转义 \\$e")
                        }
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
            throw IllegalArgumentException("字符串未闭合")
        }

        private fun readNumber(): Number {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            var isDouble = false
            if (i < s.length && s[i] == '.') {
                isDouble = true
                i++
                while (i < s.length && s[i].isDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isDouble = true
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i].isDigit()) i++
            }
            val raw = s.substring(start, i)
            return if (isDouble) raw.toDouble() else raw.toLongOrNull() ?: raw.toDouble()
        }
    }
}
