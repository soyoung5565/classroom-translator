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

                // 【2026-11-02】模型体检：把老目录里的模型搬到共享存储（抗"清除应用数据"），
                // 并把缺失情况暴露出来 —— 以前只有用户点「开始录音」才看到一句
                // 「本地模型或可执行文件缺失」，用户完全不知道该干什么。
                val store = com.classroom.translator.local.ModelStore(application)
                val moved = store.migrateFromLegacy()
                if (moved > 0) {
                    android.util.Log.i("MainActivity", "migrated $moved bytes of models to durable storage")
                }
                val broken = store.brokenModels()
                if (broken.isNotEmpty()) {
                    android.util.Log.w(
                        "MainActivity",
                        "模型缺失/损坏：${broken.joinToString { it.problem() ?: "" }} " +
                            "｜目录=${store.modelDir().absolutePath} ｜抗清除数据=${store.isDurable()}"
                    )
                }

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
