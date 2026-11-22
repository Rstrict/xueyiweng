package com.xueyiweng.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xueyiweng.app.logic.Stage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "xyw_settings")

data class Settings(
    val stageSeconds: Map<String, Int> = Stage.entries.associate { it.id to it.defaultSec },
    val hideTimerDigits: Boolean = false,
    val autoFocusInput: Boolean = true,
    val haptic: Boolean = true,
    val sound: Boolean = true,
    /** auto | light | dark */
    val theme: String = "auto",
    val notifyEnabled: Boolean = true,
    /** 首页句式模板的最近使用（未来可做排序） */
    val lastPhrase: String = "",
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val HIDE_TIMER = booleanPreferencesKey("hide_timer_digits")
        val AUTO_FOCUS = booleanPreferencesKey("auto_focus_input")
        val HAPTIC = booleanPreferencesKey("haptic")
        val SOUND = booleanPreferencesKey("sound")
        val NOTIFY = booleanPreferencesKey("notify_enabled")
        val LAST_PHRASE = stringPreferencesKey("last_phrase")
        fun stage(id: String) = intPreferencesKey("stage_sec_$id")
    }

    val flow: Flow<Settings> = context.dataStore.data.map { p -> read(p) }

    suspend fun current(): Settings = read(context.dataStore.data.first())

    private fun read(p: Preferences): Settings = Settings(
        stageSeconds = Stage.entries.associate { s ->
            s.id to (p[Keys.stage(s.id)] ?: s.defaultSec)
        },
        hideTimerDigits = p[Keys.HIDE_TIMER] ?: false,
        autoFocusInput = p[Keys.AUTO_FOCUS] ?: true,
        haptic = p[Keys.HAPTIC] ?: true,
        sound = p[Keys.SOUND] ?: true,
        theme = p[Keys.THEME] ?: "auto",
        notifyEnabled = p[Keys.NOTIFY] ?: true,
        lastPhrase = p[Keys.LAST_PHRASE] ?: "",
    )

    suspend fun setTheme(v: String) = context.dataStore.edit { it[Keys.THEME] = v }
    suspend fun setHideTimerDigits(v: Boolean) = context.dataStore.edit { it[Keys.HIDE_TIMER] = v }
    suspend fun setAutoFocus(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_FOCUS] = v }
    suspend fun setHaptic(v: Boolean) = context.dataStore.edit { it[Keys.HAPTIC] = v }
    suspend fun setSound(v: Boolean) = context.dataStore.edit { it[Keys.SOUND] = v }
    suspend fun setNotify(v: Boolean) = context.dataStore.edit { it[Keys.NOTIFY] = v }
    suspend fun setLastPhrase(v: String) = context.dataStore.edit { it[Keys.LAST_PHRASE] = v }

    suspend fun setStageSeconds(stageId: String, seconds: Int) =
        context.dataStore.edit { it[Keys.stage(stageId)] = seconds }

    suspend fun resetStageSeconds() = context.dataStore.edit { p ->
        Stage.entries.forEach { p.remove(Keys.stage(it.id)) }
    }

    suspend fun clearAll() = context.dataStore.edit { it.clear() }
}
