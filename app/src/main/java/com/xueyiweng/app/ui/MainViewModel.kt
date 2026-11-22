package com.xueyiweng.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xueyiweng.app.XueYiWenApp
import com.xueyiweng.app.logic.Mastery
import com.xueyiweng.app.logic.QuestionRecord
import com.xueyiweng.app.logic.Records
import com.xueyiweng.app.logic.SessionSnapshot
import com.xueyiweng.app.logic.Timer
import com.xueyiweng.app.service.StageTimerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 全局共享：会话快照 + 记录列表 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val xyw = app as XueYiWenApp
    val repository = xyw.repo

    private val _session = MutableStateFlow<SessionSnapshot?>(null)
    val session: StateFlow<SessionSnapshot?> = _session.asStateFlow()

    val records: StateFlow<List<QuestionRecord>> = repository.records
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            repository.refresh()
            _session.value = repository.loadSession()
        }
    }

    /** 每次会话变更都立即落库，并同步前台服务 */
    suspend fun saveSession(s: SessionSnapshot?) {
        _session.value = s
        repository.saveSession(s)
        val ctx = getApplication<Application>()
        if (s == null) StageTimerService.stop(ctx) else StageTimerService.sync(ctx, s)
    }

    fun updateSession(transform: (SessionSnapshot) -> SessionSnapshot) {
        val cur = _session.value ?: return
        viewModelScope.launch { saveSession(transform(cur)) }
    }

    fun pauseSession() {
        val cur = _session.value ?: return
        viewModelScope.launch { saveSession(Timer.pause(cur, System.currentTimeMillis())) }
    }

    /** 完成本轮，落成记录 */
    suspend fun finishRound(result: Mastery): QuestionRecord? {
        val cur = _session.value ?: return null
        val rec = Records.fromSession(cur, result)
        repository.upsert(rec)
        saveSession(null)
        return rec
    }

    suspend fun deleteRecord(id: String) = repository.delete(id)
}
