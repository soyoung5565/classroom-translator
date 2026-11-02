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
    val asrApiKey: String = "",  // 默认留空：请在设置页填写你的 Key（请勿提交真实密钥）
    val asrBaseUrl: String = "http://127.0.0.1:8001/v1",  // polyglot-lion 默认端口; 切 Qwen3-ASR 时改 8000
    val asrModel: String = "polyglot-lion-1.7b",  // 默认混说最佳; 切 Qwen3-ASR 时改 "Qwen3-ASR"
    // LLM 翻译: 切到 minimax M3
    val translateApiKey: String = "",  // 默认留空：请在设置页填写你的 Key（请勿提交真实密钥）
    val translateBaseUrl: String = "http://127.0.0.1:8002/v1",
    val translateModel: String = "qwen3-1.7b",
    // 云端翻译 (流式后覆盖 / 双端矫正): 默认值由 BuildConfig 注入 (M3 直连 minimaxi.com),
    // 用户可在设置页改 Base URL/Key/Model 切换其他 provider。
    val cloudApiKey: String = com.classroom.translator.BuildConfig.VOLC_API_KEY,
    val cloudBaseUrl: String = com.classroom.translator.BuildConfig.VOLC_BASE_URL,
    val cloudModel: String = com.classroom.translator.BuildConfig.VOLC_MODEL,
    val chunkSeconds: Int = 8,
    val asrLanguage: String = "auto",  // 混说关键: auto 让模型自动检测, 支持马来+英语+中文混说
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
        val CLOUD_API_KEY = stringPreferencesKey("cloud_api_key")
        val CLOUD_BASE_URL = stringPreferencesKey("cloud_base_url")
        val CLOUD_MODEL = stringPreferencesKey("cloud_model")
        val CHUNK_SECONDS = intPreferencesKey("chunk_seconds")
        val ASR_LANGUAGE = stringPreferencesKey("asr_language")
        val ASR_MODE = stringPreferencesKey("asr_mode")

        /**
         * 【2026-11-02】配置的**单一真相源**。
         *
         * 读取路径（settings 流）与写入路径（update）都必须走这里，
         * 否则两边的兜底值一旦不一致，写一次设置就会把某个没碰过的字段悄悄改掉
         * —— 这正是「切翻译引擎 → 401」的根因（asrMode 被写成了 dashscope）。
         */
        fun defaultSettings(
            asrApiKey: String? = null,
            asrBaseUrl: String? = null,
            asrModel: String? = null,
            translateApiKey: String? = null,
            translateBaseUrl: String? = null,
            translateModel: String? = null,
            cloudApiKey: String? = null,
            cloudBaseUrl: String? = null,
            cloudModel: String? = null,
            chunkSeconds: Int? = null,
            asrLanguage: String? = null,
            asrMode: String? = null,
        ): AppSettings {
            // Auto-migrate: 老 MIMO 配置 → 现在的本地引擎
            val rawAsrKey = asrApiKey ?: ""
            val rawTransKey = translateApiKey ?: ""
            val rawAsrUrl = asrBaseUrl ?: ""
            val rawTransUrl = translateBaseUrl ?: ""
            val rawTransModel = translateModel ?: ""
            val rawAsrModel = asrModel ?: ""
            val rawAsrMode = asrMode ?: ""
            val isOldAsr = rawAsrKey.startsWith("tp-") || rawAsrUrl.contains("xiaomimimo") || rawAsrUrl.contains("token-plan")
            val isOldTrans = rawTransKey.startsWith("tp-") || rawTransUrl.contains("xiaomimimo") || rawTransUrl.contains("token-plan") || (rawTransUrl.contains("minimaxi") && rawTransModel.lowercase().contains("mimo"))

            // 【2026-11-02】本地推理是唯一支持的识别路径；不再有"手动切引擎"的开关。
            // 用户明确要求：双引擎（先本地出字 → 云端覆盖）是自动链路，不该有手切按钮。
            // 保留 asrMode 键只为兼容旧数据，值一律收敛到 local_vllm。
            val mode = "local_vllm"

            val base = AppSettings(
                asrApiKey = if (isOldAsr || rawAsrKey.isBlank()) "" else rawAsrKey,
                asrBaseUrl = if (isOldAsr || rawAsrUrl.isBlank()) "http://127.0.0.1:8000/v1" else rawAsrUrl,
                asrModel = if (isOldAsr || rawAsrModel.isBlank()) "Qwen3-ASR" else rawAsrModel,
                translateApiKey = if (isOldTrans || rawTransKey.isBlank()) "" else rawTransKey,
                translateBaseUrl = if (isOldTrans || rawTransUrl.isBlank()) "http://127.0.0.1:8002/v1" else rawTransUrl,
                translateModel = if (isOldTrans || rawTransModel.isBlank()) "qwen3-1.7b" else rawTransModel,
                // 云端覆盖默认从 BuildConfig (编译注入)，用户可改
                cloudApiKey = cloudApiKey?.takeIf { it.isNotBlank() } ?: com.classroom.translator.BuildConfig.VOLC_API_KEY,
                cloudBaseUrl = cloudBaseUrl?.takeIf { it.isNotBlank() } ?: com.classroom.translator.BuildConfig.VOLC_BASE_URL,
                cloudModel = cloudModel?.takeIf { it.isNotBlank() } ?: com.classroom.translator.BuildConfig.VOLC_MODEL,
                chunkSeconds = chunkSeconds ?: 8,
                asrLanguage = asrLanguage ?: "auto",
                asrMode = mode,
            )
            // 本地模式：URL/模型若还指着云端默认，纠正回本地。
            //
            // ⚠️ URL 和模型名必须**一起**纠正 —— 只改 URL 会留下
            // "地址指向本地、模型名却是云端的 fun-asr-realtime" 这种半吊子配置，
            // 本地 llama-server 根本不认那个模型名。
            // （这是单元测试 SettingsDefaultsTest 抓出来的真 bug，不是测试写错。）
            val urlLooksCloud = base.asrBaseUrl.contains("dashscope") ||
                base.asrBaseUrl.contains("xiaomimimo") ||
                base.asrBaseUrl.contains("aliyuncs") ||
                base.asrBaseUrl.isBlank()
            return if (urlLooksCloud) {
                base.copy(
                    asrBaseUrl = "http://127.0.0.1:8000/v1",
                    asrModel = if (base.asrModel.startsWith("Qwen3")) base.asrModel else "Qwen3-ASR",
                )
            } else if (!base.asrModel.startsWith("Qwen3")) {
                // URL 是本地的，但模型名不是本地模型 → 同样纠正
                base.copy(asrModel = "Qwen3-ASR")
            } else base
        }
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        defaultSettings(
            asrApiKey = prefs[ASR_API_KEY],
            asrBaseUrl = prefs[ASR_BASE_URL],
            asrModel = prefs[ASR_MODEL],
            translateApiKey = prefs[TRANSLATE_API_KEY],
            translateBaseUrl = prefs[TRANSLATE_BASE_URL],
            translateModel = prefs[TRANSLATE_MODEL],
            cloudApiKey = prefs[CLOUD_API_KEY],
            cloudBaseUrl = prefs[CLOUD_BASE_URL],
            cloudModel = prefs[CLOUD_MODEL],
            chunkSeconds = prefs[CHUNK_SECONDS],
            asrLanguage = prefs[ASR_LANGUAGE],
            asrMode = prefs[ASR_MODE],
        )
    }

    /**
     * 写回设置。
     *
     * 【2026-11-02 关键修复】读回"当前值"时必须用与 [settings] 流**完全相同**的兜底，
     * 否则 update() 会把没碰过的字段悄悄改掉。
     *
     * 事故链（本项目真实发生）：用户反馈"把翻译引擎从本地切到云端就报 401"。
     * 根因就在这里 —— [settings] 流把 asrMode 兜底成 "local_vllm"，
     * 而 update() 把它兜底成 "dashscope"。用户在设置页改**翻译**引擎（与 ASR 无关）时，
     * update() 读回当前值 → asrMode 变成 "dashscope" → 写回 → 下次录音走 DashScope
     * WebSocket，而密钥从没配过 → `401 Unauthorized`（Expected HTTP 101 response）。
     * 清除应用数据后 ASR_MODE 键不存在，这个坑 100% 复现。
     *
     * 教训：同一份配置，读取路径和写入路径的默认值必须来自同一处，否则写一次就改坏一个字段。
     * 现在统一走 [defaultSettings]（单一真相源）。
     */
    /**
     * 恢复初始形态：把所有设置写回出厂默认值。
     *
     * 【2026-11-02】「恢复初始形态」工具的落点。
     * 注意：只清**设置**，不清课堂记录 —— 用户的课比配置值钱。
     * 清会话记录是 [com.classroom.translator.data.SessionManager.clearAll] 的事，由调用方显式决定。
     */
    suspend fun resetToDefaults() {
        context.dataStore.edit { prefs ->
            val d = defaultSettings()
            prefs[ASR_API_KEY] = d.asrApiKey
            prefs[ASR_BASE_URL] = d.asrBaseUrl
            prefs[ASR_MODEL] = d.asrModel
            prefs[TRANSLATE_API_KEY] = d.translateApiKey
            prefs[TRANSLATE_BASE_URL] = d.translateBaseUrl
            prefs[TRANSLATE_MODEL] = d.translateModel
            prefs[CLOUD_API_KEY] = d.cloudApiKey
            prefs[CLOUD_BASE_URL] = d.cloudBaseUrl
            prefs[CLOUD_MODEL] = d.cloudModel
            prefs[CHUNK_SECONDS] = d.chunkSeconds
            prefs[ASR_LANGUAGE] = d.asrLanguage
            prefs[ASR_MODE] = d.asrMode
        }
    }

    suspend fun update(block: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val current = defaultSettings(
                asrApiKey = prefs[ASR_API_KEY],
                asrBaseUrl = prefs[ASR_BASE_URL],
                asrModel = prefs[ASR_MODEL],
                translateApiKey = prefs[TRANSLATE_API_KEY],
                translateBaseUrl = prefs[TRANSLATE_BASE_URL],
                translateModel = prefs[TRANSLATE_MODEL],
                cloudApiKey = prefs[CLOUD_API_KEY],
                cloudBaseUrl = prefs[CLOUD_BASE_URL],
                cloudModel = prefs[CLOUD_MODEL],
                chunkSeconds = prefs[CHUNK_SECONDS],
                asrLanguage = prefs[ASR_LANGUAGE],
                asrMode = prefs[ASR_MODE],
            )
            val updated = block(current)
            prefs[ASR_API_KEY] = updated.asrApiKey
            prefs[ASR_BASE_URL] = updated.asrBaseUrl
            prefs[ASR_MODEL] = updated.asrModel
            prefs[TRANSLATE_API_KEY] = updated.translateApiKey
            prefs[TRANSLATE_BASE_URL] = updated.translateBaseUrl
            prefs[TRANSLATE_MODEL] = updated.translateModel
            prefs[CLOUD_API_KEY] = updated.cloudApiKey
            prefs[CLOUD_BASE_URL] = updated.cloudBaseUrl
            prefs[CLOUD_MODEL] = updated.cloudModel
            prefs[CHUNK_SECONDS] = updated.chunkSeconds
            prefs[ASR_LANGUAGE] = updated.asrLanguage
            prefs[ASR_MODE] = updated.asrMode
        }
    }
}
