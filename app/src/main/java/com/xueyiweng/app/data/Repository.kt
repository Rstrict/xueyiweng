package com.xueyiweng.app.data

import android.content.Context
import com.xueyiweng.app.logic.QuestionRecord
import com.xueyiweng.app.logic.SessionSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 唯一数据入口。UI 只跟这里打交道。
 * 记录列表用 StateFlow 广播，任何写入都会立刻通知所有页面。
 */
class Repository(context: Context) {

    private val helper = DbHelper(context.applicationContext)
    private val qDao = QuestionDao(helper)
    private val sDao = SessionDao(helper)

    private val _records = MutableStateFlow<List<QuestionRecord>>(emptyList())
    val records: StateFlow<List<QuestionRecord>> = _records.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _records.value = qDao.all()
    }

    suspend fun allRecords(): List<QuestionRecord> = withContext(Dispatchers.IO) {
        qDao.all().also { _records.value = it }
    }

    suspend fun recordCount(): Int = withContext(Dispatchers.IO) { qDao.count() }

    suspend fun upsert(record: QuestionRecord) = withContext(Dispatchers.IO) {
        qDao.upsert(record)
        _records.value = qDao.all()
    }

    suspend fun upsertAll(records: List<QuestionRecord>) = withContext(Dispatchers.IO) {
        qDao.upsertAll(records)
        _records.value = qDao.all()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        qDao.delete(id)
        _records.value = qDao.all()
    }

    suspend fun clearRecords() = withContext(Dispatchers.IO) {
        qDao.clear()
        _records.value = emptyList()
    }

    suspend fun saveSession(snapshot: SessionSnapshot?) = withContext(Dispatchers.IO) {
        if (snapshot == null) sDao.clear() else sDao.save(snapshot)
    }

    suspend fun loadSession(): SessionSnapshot? = withContext(Dispatchers.IO) { sDao.load() }
}
