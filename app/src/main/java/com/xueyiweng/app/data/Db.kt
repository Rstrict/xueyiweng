package com.xueyiweng.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.xueyiweng.app.logic.BrainDump
import com.xueyiweng.app.logic.JsonLite
import com.xueyiweng.app.logic.Mastery
import com.xueyiweng.app.logic.QuestionRecord
import com.xueyiweng.app.logic.ReviewEntry
import com.xueyiweng.app.logic.SessionSnapshot
import java.time.LocalDate

/**
 * 手写 SQLite 持久层。
 * 不用 Room —— 避免注解处理器带来的编译链不确定性；
 * 记录量在个人量级（几千条），裸 SQL 完全够用且启动更快。
 */
class DbHelper(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    companion object {
        const val NAME = "xueyiweng.db"
        const val VERSION = 1

        private const val T_RECORDS = "records"
        private const val T_SESSION = "session_snapshot"
        private const val T_META = "meta"

        /** 供 DAO 使用（同文件内 private 不可见，故公开） */
        const val T_RECORDS_PUBLIC = T_RECORDS
        const val T_SESSION_PUBLIC = T_SESSION
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $T_RECORDS (
                id TEXT PRIMARY KEY NOT NULL,
                questionText TEXT NOT NULL,
                sourceLocation TEXT NOT NULL DEFAULT '',
                subjectTag TEXT NOT NULL DEFAULT '',
                createdAt INTEGER NOT NULL,
                firstAttemptType TEXT NOT NULL DEFAULT 'text',
                firstAttemptContent TEXT NOT NULL DEFAULT '',
                stuckPoint TEXT NOT NULL DEFAULT '',
                finalNote TEXT NOT NULL DEFAULT '',
                masteryStatus TEXT NOT NULL DEFAULT 'need_hint',
                intervalDays INTEGER NOT NULL DEFAULT 0,
                consecutiveIndependent INTEGER NOT NULL DEFAULT 0,
                isStable INTEGER NOT NULL DEFAULT 0,
                nextReviewDate TEXT,
                lastReviewedAt INTEGER,
                reviewCount INTEGER NOT NULL DEFAULT 0,
                reviewHistoryJson TEXT NOT NULL DEFAULT '[]',
                brainDumpsJson TEXT NOT NULL DEFAULT '[]',
                sessionDurationSec INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_records_next ON $T_RECORDS(nextReviewDate)")

        db.execSQL(
            """
            CREATE TABLE $T_SESSION (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                sessionId TEXT NOT NULL,
                questionText TEXT NOT NULL,
                sourceLocation TEXT NOT NULL DEFAULT '',
                currentStage TEXT NOT NULL,
                stageStartTimestamp INTEGER NOT NULL,
                stageTargetDurationSec INTEGER NOT NULL,
                isPaused INTEGER NOT NULL DEFAULT 0,
                pausedAtTimestamp INTEGER,
                accumulatedPausedSec INTEGER NOT NULL DEFAULT 0,
                pauseContextNote TEXT NOT NULL DEFAULT '',
                firstAttemptDraft TEXT NOT NULL DEFAULT '',
                firstAttemptType TEXT,
                stuckPointDraft TEXT NOT NULL DEFAULT '',
                finalNoteDraft TEXT NOT NULL DEFAULT '',
                brainDumpsJson TEXT NOT NULL DEFAULT '[]',
                startedAt INTEGER NOT NULL,
                overtimeNotified INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL("CREATE TABLE $T_META (k TEXT PRIMARY KEY NOT NULL, v TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 首版：升级即重建（个人数据可由导出备份恢复）
        db.execSQL("DROP TABLE IF EXISTS $T_RECORDS")
        db.execSQL("DROP TABLE IF EXISTS $T_SESSION")
        db.execSQL("DROP TABLE IF EXISTS $T_META")
        onCreate(db)
    }
}

class QuestionDao(private val helper: DbHelper) {

    private fun cv(r: QuestionRecord): ContentValues = ContentValues().apply {
        put("id", r.id)
        put("questionText", r.questionText)
        put("sourceLocation", r.sourceLocation)
        put("subjectTag", r.subjectTag)
        put("createdAt", r.createdAt)
        put("firstAttemptType", r.firstAttemptType)
        put("firstAttemptContent", r.firstAttemptContent)
        put("stuckPoint", r.stuckPoint)
        put("finalNote", r.finalNote)
        put("masteryStatus", r.masteryStatus)
        put("intervalDays", r.intervalDays)
        put("consecutiveIndependent", r.consecutiveIndependent)
        put("isStable", if (r.isStable) 1 else 0)
        put("nextReviewDate", r.nextReviewDate?.toString())
        put("lastReviewedAt", r.lastReviewedAt)
        put("reviewCount", r.reviewCount)
        put(
            "reviewHistoryJson",
            JsonLite.stringify(r.reviewHistory.map {
                mapOf("reviewedAt" to it.reviewedAt, "result" to it.result, "note" to it.note)
            })
        )
        put("brainDumpsJson", JsonLite.stringify(r.brainDumpsOnCreate))
        put("sessionDurationSec", r.sessionDurationSec)
    }

    fun upsert(r: QuestionRecord) {
        helper.writableDatabase.insertWithOnConflict(
            DbHelper.T_RECORDS_PUBLIC, null, cv(r), SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun upsertAll(list: List<QuestionRecord>) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for (r in list) {
                db.insertWithOnConflict(
                    DbHelper.T_RECORDS_PUBLIC, null, cv(r), SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun all(): List<QuestionRecord> {
        val out = mutableListOf<QuestionRecord>()
        helper.readableDatabase.rawQuery(
            "SELECT * FROM ${DbHelper.T_RECORDS_PUBLIC} ORDER BY createdAt DESC", null
        ).use { c ->
            while (c.moveToNext()) out += read(c)
        }
        return out
    }

    fun delete(id: String) {
        helper.writableDatabase.delete(DbHelper.T_RECORDS_PUBLIC, "id = ?", arrayOf(id))
    }

    fun clear() {
        helper.writableDatabase.delete(DbHelper.T_RECORDS_PUBLIC, null, null)
    }

    fun count(): Int {
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM ${DbHelper.T_RECORDS_PUBLIC}", null
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    private fun read(c: android.database.Cursor): QuestionRecord {
        fun str(k: String) = c.getColumnIndexOrThrow(k).let { c.getString(it) ?: "" }
        fun strOrNull(k: String): String? = c.getColumnIndexOrThrow(k).let {
            if (c.isNull(it)) null else c.getString(it)
        }
        fun lng(k: String) = c.getColumnIndexOrThrow(k).let { c.getLong(it) }
        fun lngOrNull(k: String): Long? = c.getColumnIndexOrThrow(k).let {
            if (c.isNull(it)) null else c.getLong(it)
        }
        fun int(k: String) = c.getColumnIndexOrThrow(k).let { c.getInt(it) }

        val history = runCatching {
            (JsonLite.parse(str("reviewHistoryJson")) as? List<*>)?.mapNotNull { item ->
                val m = item as? Map<*, *> ?: return@mapNotNull null
                ReviewEntry(
                    reviewedAt = (m["reviewedAt"] as? Number)?.toLong() ?: 0L,
                    result = m["result"] as? String ?: Mastery.NEED_HINT.id,
                    note = m["note"] as? String ?: "",
                )
            } ?: emptyList()
        }.getOrDefault(emptyList())

        val dumps = runCatching {
            (JsonLite.parse(str("brainDumpsJson")) as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        }.getOrDefault(emptyList())

        return QuestionRecord(
            id = str("id"),
            questionText = str("questionText"),
            sourceLocation = str("sourceLocation"),
            subjectTag = str("subjectTag"),
            createdAt = lng("createdAt"),
            firstAttemptType = str("firstAttemptType"),
            firstAttemptContent = str("firstAttemptContent"),
            stuckPoint = str("stuckPoint"),
            finalNote = str("finalNote"),
            masteryStatus = Mastery.fromId(str("masteryStatus")).id,
            intervalDays = int("intervalDays"),
            consecutiveIndependent = int("consecutiveIndependent"),
            isStable = int("isStable") == 1,
            nextReviewDate = strOrNull("nextReviewDate")?.let {
                runCatching { LocalDate.parse(it) }.getOrNull()
            },
            lastReviewedAt = lngOrNull("lastReviewedAt"),
            reviewCount = int("reviewCount"),
            reviewHistory = history,
            brainDumpsOnCreate = dumps,
            sessionDurationSec = int("sessionDurationSec"),
        )
    }
}

class SessionDao(private val helper: DbHelper) {

    fun save(s: SessionSnapshot) {
        val cv = ContentValues().apply {
            put("id", 1)
            put("sessionId", s.sessionId)
            put("questionText", s.questionText)
            put("sourceLocation", s.sourceLocation)
            put("currentStage", s.currentStage)
            put("stageStartTimestamp", s.stageStartTimestamp)
            put("stageTargetDurationSec", s.stageTargetDurationSec)
            put("isPaused", if (s.isPaused) 1 else 0)
            put("pausedAtTimestamp", s.pausedAtTimestamp)
            put("accumulatedPausedSec", s.accumulatedPausedSec)
            put("pauseContextNote", s.pauseContextNote)
            put("firstAttemptDraft", s.firstAttemptDraft)
            put("firstAttemptType", s.firstAttemptType)
            put("stuckPointDraft", s.stuckPointDraft)
            put("finalNoteDraft", s.finalNoteDraft)
            put(
                "brainDumpsJson",
                JsonLite.stringify(s.brainDumps.map {
                    mapOf("text" to it.text, "createdAt" to it.createdAt, "resolved" to it.resolved)
                })
            )
            put("startedAt", s.startedAt)
            put("overtimeNotified", if (s.overtimeNotified) 1 else 0)
        }
        helper.writableDatabase.insertWithOnConflict(
            DbHelper.T_SESSION_PUBLIC, null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun load(): SessionSnapshot? {
        helper.readableDatabase.rawQuery(
            "SELECT * FROM ${DbHelper.T_SESSION_PUBLIC} WHERE id = 1", null
        ).use { c ->
            if (!c.moveToFirst()) return null
            fun str(k: String) = c.getColumnIndexOrThrow(k).let { c.getString(it) ?: "" }
            fun strOrNull(k: String): String? = c.getColumnIndexOrThrow(k).let {
                if (c.isNull(it)) null else c.getString(it)
            }
            fun lng(k: String) = c.getColumnIndexOrThrow(k).let { c.getLong(it) }
            fun lngOrNull(k: String): Long? = c.getColumnIndexOrThrow(k).let {
                if (c.isNull(it)) null else c.getLong(it)
            }
            fun int(k: String) = c.getColumnIndexOrThrow(k).let { c.getInt(it) }

            val dumps = runCatching {
                (JsonLite.parse(str("brainDumpsJson")) as? List<*>)?.mapNotNull { item ->
                    val m = item as? Map<*, *> ?: return@mapNotNull null
                    BrainDump(
                        text = m["text"] as? String ?: return@mapNotNull null,
                        createdAt = (m["createdAt"] as? Number)?.toLong() ?: 0L,
                        resolved = m["resolved"] as? Boolean ?: false,
                    )
                } ?: emptyList()
            }.getOrDefault(emptyList())

            return SessionSnapshot(
                sessionId = str("sessionId"),
                questionText = str("questionText"),
                sourceLocation = str("sourceLocation"),
                currentStage = str("currentStage"),
                stageStartTimestamp = lng("stageStartTimestamp"),
                stageTargetDurationSec = int("stageTargetDurationSec"),
                isPaused = int("isPaused") == 1,
                pausedAtTimestamp = lngOrNull("pausedAtTimestamp"),
                accumulatedPausedSec = lng("accumulatedPausedSec"),
                pauseContextNote = str("pauseContextNote"),
                firstAttemptDraft = str("firstAttemptDraft"),
                firstAttemptType = strOrNull("firstAttemptType"),
                stuckPointDraft = str("stuckPointDraft"),
                finalNoteDraft = str("finalNoteDraft"),
                brainDumps = dumps,
                startedAt = lng("startedAt"),
                overtimeNotified = int("overtimeNotified") == 1,
            )
        }
    }

    fun clear() {
        helper.writableDatabase.delete(DbHelper.T_SESSION_PUBLIC, null, null)
    }
}
