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
    // ASR: 保留百炼 Fun-ASR Realtime (用户指定, minimax 套餐不含 ASR)
    val asrApiKey: String = "REDACTED_KEY",
    val asrBaseUrl: String = "http://127.0.0.1:8000/v1",
    val asrModel: String = "Qwen3-ASR",
    // LLM 翻译: 切到 minimax M3
    val translateApiKey: String = "REDACTED_KEY",
    val translateBaseUrl: String = "http://127.0.0.1:8002/v1",
    val translateModel: String = "qwen3.5-2b",
    val chunkSeconds: Int = 8,
    val asrLanguage: String = "en",
    val asrMode: String = "local_vllm"
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
        // Auto-migrate: old MIMO settings -> new DashScope + MiniMax
        val rawAsrKey = prefs[ASR_API_KEY] ?: ""
        val rawTransKey = prefs[TRANSLATE_API_KEY] ?: ""
        val rawAsrUrl = prefs[ASR_BASE_URL] ?: ""
        val rawTransUrl = prefs[TRANSLATE_BASE_URL] ?: ""
        val rawTransModel = prefs[TRANSLATE_MODEL] ?: ""
        val rawAsrModel = prefs[ASR_MODEL] ?: ""
        val rawAsrMode = prefs[ASR_MODE] ?: ""
        val isOldAsr = rawAsrKey.startsWith("tp-") || rawAsrUrl.contains("xiaomimimo") || rawAsrUrl.contains("token-plan")
        val isOldTrans = rawTransKey.startsWith("tp-") || rawTransUrl.contains("xiaomimimo") || rawTransUrl.contains("token-plan") || (rawTransUrl.contains("minimaxi") && rawTransModel.lowercase().contains("mimo"))

        AppSettings(
            asrApiKey = if (isOldAsr || rawAsrKey.isBlank()) "REDACTED_KEY" else rawAsrKey,
            asrBaseUrl = if (isOldAsr || rawAsrUrl.isBlank()) "http://127.0.0.1:8000/v1" else rawAsrUrl,
            asrModel = if (isOldAsr || rawAsrModel.isBlank()) "Qwen3-ASR" else rawAsrModel,
            translateApiKey = if (isOldTrans || rawTransKey.isBlank()) "REDACTED_KEY" else rawTransKey,
            translateBaseUrl = if (isOldTrans || rawTransUrl.isBlank()) "http://127.0.0.1:8002/v1" else rawTransUrl,
            translateModel = if (isOldTrans || rawTransModel.isBlank()) "qwen3.5-2b" else rawTransModel,
            chunkSeconds = prefs[CHUNK_SECONDS] ?: 8,
            asrLanguage = prefs[ASR_LANGUAGE] ?: "en",
            asrMode = if (rawAsrMode.isBlank() || rawAsrMode == "mimo") "local_vllm" else rawAsrMode
        ).let { s ->
            // 本地 vLLM 模式：若 URL/模型还是云端默认，给本地默认值
            if (s.asrMode == "local_vllm" && (s.asrBaseUrl.contains("dashscope") || s.asrBaseUrl.contains("xiaomimimo") || s.asrBaseUrl.isBlank())) {
                s.copy(
                    asrBaseUrl = "http://127.0.0.1:8000/v1",  // 127.0.0.1 兼容 USB reverse；局域网时在设置页改
                    asrModel = if (s.asrModel.startsWith("Qwen3")) s.asrModel else "Qwen3-ASR"
                )
            } else s
        }
    }

    suspend fun update(block: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val current = AppSettings(
                asrApiKey = prefs[ASR_API_KEY] ?: "REDACTED_KEY",
                asrBaseUrl = prefs[ASR_BASE_URL] ?: "https://dashscope.aliyuncs.com/compatible-mode/v1",
                asrModel = prefs[ASR_MODEL] ?: "fun-asr-realtime-2026-02-28",
                translateApiKey = prefs[TRANSLATE_API_KEY] ?: "REDACTED_KEY",
                translateBaseUrl = prefs[TRANSLATE_BASE_URL] ?: "http://127.0.0.1:8002/v1",
                translateModel = prefs[TRANSLATE_MODEL] ?: "qwen3.5-2b",
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
