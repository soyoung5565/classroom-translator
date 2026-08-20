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
    val asrBaseUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1",
    val asrModel: String = "fun-asr-realtime-2026-02-28",
    // LLM 翻译: 切到 minimax M3
    val translateApiKey: String = "sk-cp-zibpVL97JE9eNAoKwwizTx7lm15Pq4I792nUDkVeJ_PtPKZ_XbuD2tJkXIcZJ9RW_kEuXEuhVlhrkR2i8A1YS-gZ_BMKrLOXOSvMKL40s615FRqqXTWT_4g-gZ_BMKrLOXOSvMKL40s615FRqqXTWT_4g",
    val translateBaseUrl: String = "https://api.minimaxi.com/v1",
    val translateModel: String = "MiniMax-M3",
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
        // Auto-migrate: old MIMO settings -> new DashScope + MiniMax
        val rawAsrKey = prefs[ASR_API_KEY] ?: ""
        val rawTransKey = prefs[TRANSLATE_API_KEY] ?: ""
        val rawAsrUrl = prefs[ASR_BASE_URL] ?: ""
        val rawTransUrl = prefs[TRANSLATE_BASE_URL] ?: ""
        val rawTransModel = prefs[TRANSLATE_MODEL] ?: ""
        val rawAsrModel = prefs[ASR_MODEL] ?: ""
        val rawAsrMode = prefs[ASR_MODE] ?: ""
        val isOldAsr = rawAsrKey.startsWith("tp-") || rawAsrUrl.contains("xiaomimimo") || rawAsrUrl.contains("token-plan")
        val isOldTrans = rawTransKey.startsWith("tp-") || rawTransUrl.contains("xiaomimimo") || rawTransUrl.contains("token-plan") || rawTransModel.lowercase().contains("mimo")

        AppSettings(
            asrApiKey = if (isOldAsr || rawAsrKey.isBlank()) "REDACTED_KEY" else rawAsrKey,
            asrBaseUrl = if (isOldAsr || rawAsrUrl.isBlank()) "https://dashscope.aliyuncs.com" else rawAsrUrl,
            asrModel = if (isOldAsr || rawAsrModel.isBlank()) "fun-asr-realtime-2026-02-28" else rawAsrModel,
            translateApiKey = if (isOldTrans || rawTransKey.isBlank()) "sk-cp-zibpVL97JE9eNAoKwwizTx7lm15Pq4I792nUDkVeJ_PtPKZ_XbuD2tJkXIcZJ9RW_kEuXEuhVlhrkR2i8A1YS-gZ_BMKrLOXOSvMKL40s615FRqqXTWT_4g" else rawTransKey,
            translateBaseUrl = if (isOldTrans || rawTransUrl.isBlank()) "https://api.minimaxi.com/v1" else rawTransUrl,
            translateModel = if (isOldTrans || rawTransModel.isBlank()) "MiniMax-M3" else rawTransModel,
            chunkSeconds = prefs[CHUNK_SECONDS] ?: 8,
            asrLanguage = prefs[ASR_LANGUAGE] ?: "en",
            asrMode = if (rawAsrMode.isBlank() || rawAsrMode == "mimo") "dashscope" else rawAsrMode
        ).let { s ->
            // 本地 vLLM 模式：若 URL/模型还是云端默认，给本地默认值
            if (s.asrMode == "local_vllm" && (s.asrBaseUrl.contains("dashscope") || s.asrBaseUrl.contains("xiaomimimo") || s.asrBaseUrl.isBlank())) {
                s.copy(
                    asrBaseUrl = "http://192.168.1.249:8000/v1",
                    asrModel = if (s.asrModel.startsWith("Qwen3")) s.asrModel else "Qwen3-ASR-1.7B"
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
                translateApiKey = prefs[TRANSLATE_API_KEY] ?: "sk-cp-zibpVL97JE9eNAoKwwizTx7lm15Pq4I792nUDkVeJ_PtPKZ_XbuD2tJkXIcZJ9RW_kEuXEuhVlhrkR2i8A1YS-gZ_BMKrLOXOSvMKL40s615FRqqXTWT_4g-gZ_BMKrLOXOSvMKL40s615FRqqXTWT_4g",
                translateBaseUrl = prefs[TRANSLATE_BASE_URL] ?: "https://api.minimaxi.com/v1",
                translateModel = prefs[TRANSLATE_MODEL] ?: "MiniMax-M3",
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
