package com.classroom.translator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.classroom.translator.ui.MainNavHost
import com.classroom.translator.ui.theme.TranslatorTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 主界面。
 * 2026-09-24 清理：移除了屏幕录制/内录（MediaProjection）相关的测试通道 ——
 * 现在只用麦克风 + 本地 llama-server（App 自足，不需要电脑）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 【手机自足】一进 App 就后台预热本地推理服务（模型加载 ~20s），用户点「开始」时已就绪。
        // 不依赖电脑/adb；退到后台且无会话时自动释放。
        lifecycleScope.launch {
            runCatching {
                val s = com.classroom.translator.data.SettingsRepository(this@MainActivity)
                    .settings.first()
                com.classroom.translator.local.LocalLlmServerManager.get(application)
                    .prewarm(s.asrBaseUrl, s.translateBaseUrl)
            }
        }

        setContent {
            TranslatorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainNavHost()
                }
            }
        }
    }
}
