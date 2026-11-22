package com.xueyiweng.app

import android.app.Application
import com.xueyiweng.app.data.Repository
import com.xueyiweng.app.data.SettingsRepository
import com.xueyiweng.app.service.DailyRefresh
import com.xueyiweng.app.service.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class XueYiWenApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var repo: Repository
        private set
    lateinit var settings: SettingsRepository
        private set

    /** 全局主题偏好，Activity 观察它做 recreate */
    private val _theme = MutableStateFlow("auto")
    val theme: StateFlow<String> = _theme.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        repo = Repository(this)
        settings = SettingsRepository(this)

        Notifications.ensureChannels(this)
        DailyRefresh.scheduleNext(this)

        appScope.launch {
            settings.flow.collect { _theme.value = it.theme }
        }
    }

    companion object {
        fun from(ctx: android.content.Context): XueYiWenApp =
            ctx.applicationContext as XueYiWenApp
    }
}
