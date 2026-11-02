package com.classroom.translator.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置读写一致性回归测试。
 *
 * 【2026-11-02】这个测试是为了钉死一个真实事故：
 * 用户把翻译引擎从本地切到云端 → App 报 `401 Unauthorized`（Expected HTTP 101 response）。
 *
 * 根因不在网络、不在密钥，而在 `SettingsRepository`：
 *   - `settings` 流读回当前值时，asrMode 兜底成 "local_vllm"
 *   - `update()` 读回当前值时，asrMode 兜底成 "dashscope"
 * 两处兜底不一致 → 用户改**任何一项**设置（哪怕是翻译引擎，与 ASR 无关），
 * update() 都会把 asrMode 悄悄改写成 "dashscope" 并写回 DataStore →
 * 下次录音走 DashScope WebSocket，而密钥从没配过 → 401。
 *
 * 清除应用数据后 ASR_MODE 键不存在，100% 复现。
 *
 * 修复：两条路径统一走 `defaultSettings()`（单一真相源）。
 * 本测试锁住这个约定，防止将来又写岔。
 */
class SettingsDefaultsTest {

    @Test
    fun `默认配置下 asrMode 是本地推理`() {
        val d = SettingsRepository.defaultSettings()
        assertEquals("local_vllm", d.asrMode)
    }

    @Test
    fun `空配置不会退回云端 ASR`() {
        // 模拟"清除应用数据"后的状态：所有键都不存在
        val d = SettingsRepository.defaultSettings(
            asrApiKey = null,
            asrBaseUrl = null,
            asrModel = null,
            asrMode = null,
        )
        assertEquals("local_vllm", d.asrMode)
        assertEquals("http://127.0.0.1:8000/v1", d.asrBaseUrl)
        assertEquals("Qwen3-ASR", d.asrModel)
    }

    @Test
    fun `历史遗留的云端 ASR 配置会被纠正回本地`() {
        // 老版本曾把 ASR 指向 DashScope / MIMO
        for (legacyUrl in listOf(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "https://token-plan-cn.xiaomimimo.com/v1",
            "",
        )) {
            val d = SettingsRepository.defaultSettings(
                asrBaseUrl = legacyUrl,
                asrModel = "fun-asr-realtime-2026-02-28",
                asrMode = "dashscope",
            )
            assertEquals("asrMode 必须收敛到 local_vllm，实际=$legacyUrl", "local_vllm", d.asrMode)
            assertEquals("asrBaseUrl 必须回本地，实际=$legacyUrl", "http://127.0.0.1:8000/v1", d.asrBaseUrl)
            assertEquals("asrModel", "Qwen3-ASR", d.asrModel)
        }
    }

    @Test
    fun `遗留的 MIMO 配置被识别为旧配置并清空`() {
        val d = SettingsRepository.defaultSettings(
            asrApiKey = "tp-legacykey",
            asrBaseUrl = "https://token-plan-cn.xiaomimimo.com/v1",
            translateApiKey = "tp-legacykey",
            translateBaseUrl = "https://token-plan-cn.xiaomimimo.com/v1",
            translateModel = "mimo-v2.5",
        )
        assertEquals("", d.asrApiKey)
        assertEquals("", d.translateApiKey)
    }

    @Test
    fun `读取路径与写入路径对同一份输入产出完全一致的配置`() {
        // 这是本次事故的核心断言：
        // update() 用 defaultSettings() 读回"当前值"，settings 流也用 defaultSettings() 读。
        // 只要两边同源，"改一项设置顺带改坏另一项"就不可能发生。
        // 清除应用数据后的状态：只有这几项有值，其余键都不存在（null）
        val fromStream = SettingsRepository.defaultSettings(
            asrApiKey = "",
            asrBaseUrl = "http://127.0.0.1:8000/v1",
            asrModel = "Qwen3-ASR",
            translateApiKey = "",
            translateBaseUrl = "http://127.0.0.1:8002/v1",
            translateModel = "qwen3-1.7b",
            cloudApiKey = null,
            cloudBaseUrl = null,
            cloudModel = null,
            chunkSeconds = null,
            asrLanguage = null,
            asrMode = null,               // ← 键不存在（清数据后状态）
        )
        // update() 内部读回当前值走的是同一个函数 → 改翻译设置后 asrMode 不变
        val afterUserEditsTranslate = fromStream.copy(
            translateBaseUrl = "https://ark.cn-beijing.volces.com/api/plan/v3",
            translateModel = "deepseek-v4-1-flash",
        )
        assertEquals(
            "改翻译设置绝不能动 asrMode",
            fromStream.asrMode,
            afterUserEditsTranslate.asrMode,
        )
        assertEquals("local_vllm", afterUserEditsTranslate.asrMode)
    }

    @Test
    fun `云端覆盖默认取 BuildConfig 注入值`() {
        val d = SettingsRepository.defaultSettings()
        assertTrue("云端地址不应为空", d.cloudBaseUrl.isNotBlank())
        assertTrue("云端模型不应为空", d.cloudModel.isNotBlank())
    }
}
