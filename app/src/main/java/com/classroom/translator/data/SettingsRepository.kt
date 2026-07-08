package com.classroom.translator.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val asrApiKey: String = "REDACTED_KEY",
    val asrBaseUrl: String = "https://token-plan-cn.xiaomimimo.com/v1",
    val asrModel: String = "fun-asr-realtime-2026-02-28",
    val translateApiKey: String = "YOUR_TOKEN_PLAN_API_KEY",
    val translateBaseUrl: String = "https://token-plan-cn.xiaomimimo.com/v1",
    val translateModel: String = "mimo-v2.5-pro",
    val chunkSeconds: Int = 8,
    val asrLanguage: String = "en",
    val asrMode: String = "dashscope"
)

class SettingsRepository(private val context: Context) {

    companion object {
        val ASR_API_KEY = stringPreferencesKey("asr_api_key")
        val ASR_BASE_URL = stringPreferencesKey("asr_base_url")
        val ASR_MODEL = stringPreferencesKey("asr_model")
        val TRANSLATE_API_KEY = stringPreferencesKey("translate_api_key")
        val TRANSLATE_BASE_URL = stringPreferencesKey("translate_base_url")
        val TRANSLATE_MODEL = stringPreferencesKey("translate_model")
        val CHUNK_SECONDS = intPreferencesKey("chunk_seconds")
        val ASR_LANGUAGE = stringPreferencesKey("asr_language")
        val ASR_MODE = stringPreferencesKey("asr_mode")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            asrApiKey = prefs[ASR_API_KEY] ?: "YOUR_TOKEN_PLAN_API_KEY",
            asrBaseUrl = prefs[ASR_BASE_URL] ?: "https://token-plan-cn.xiaomimimo.com/v1",
            asrModel = prefs[ASR_MODEL] ?: "mimo-v2.5",
            translateApiKey = prefs[TRANSLATE_API_KEY] ?: "YOUR_TOKEN_PLAN_API_KEY",
            translateBaseUrl = prefs[TRANSLATE_BASE_URL] ?: "https://token-plan-cn.xiaomimimo.com/v1",
            translateModel = prefs[TRANSLATE_MODEL] ?: "mimo-v2.5-pro",
            chunkSeconds = prefs[CHUNK_SECONDS] ?: 8,
            asrLanguage = prefs[ASR_LANGUAGE] ?: "en",
            asrMode = prefs[ASR_MODE] ?: "dashscope"
        )
    }

    suspend fun update(block: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val current = AppSettings(
                asrApiKey = prefs[ASR_API_KEY] ?: "YOUR_TOKEN_PLAN_API_KEY",
                asrBaseUrl = prefs[ASR_BASE_URL] ?: "https://token-plan-cn.xiaomimimo.com/v1",
                asrModel = prefs[ASR_MODEL] ?: "mimo-v2.5",
                translateApiKey = prefs[TRANSLATE_API_KEY] ?: "YOUR_TOKEN_PLAN_API_KEY",
                translateBaseUrl = prefs[TRANSLATE_BASE_URL] ?: "https://token-plan-cn.xiaomimimo.com/v1",
                translateModel = prefs[TRANSLATE_MODEL] ?: "mimo-v2.5-pro",
                chunkSeconds = prefs[CHUNK_SECONDS] ?: 8,
                asrLanguage = prefs[ASR_LANGUAGE] ?: "en",
                asrMode = prefs[ASR_MODE] ?: "dashscope"
            )
            val updated = block(current)
            prefs[ASR_API_KEY] = updated.asrApiKey
            prefs[ASR_BASE_URL] = updated.asrBaseUrl
            prefs[ASR_MODEL] = updated.asrModel
            prefs[TRANSLATE_API_KEY] = updated.translateApiKey
            prefs[TRANSLATE_BASE_URL] = updated.translateBaseUrl
            prefs[TRANSLATE_MODEL] = updated.translateModel
            prefs[CHUNK_SECONDS] = updated.chunkSeconds
            prefs[ASR_LANGUAGE] = updated.asrLanguage
            prefs[ASR_MODE] = updated.asrMode
        }
    }
}
