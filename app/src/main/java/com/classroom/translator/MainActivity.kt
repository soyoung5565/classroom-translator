package com.classroom.translator

import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.classroom.translator.ui.MainNavHost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.classroom.translator.ui.theme.TranslatorTheme

class MainActivity : ComponentActivity() {
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            // Android 14+ 硬性要求：先启动 mediaProjection 类型前台服务，才能 getMediaProjection
            // 且必须等服务 startForeground 完成后再 getMediaProjection（否则 RemoteException）
            val resultCode = result.resultCode
            val data = result.data
            ProjectionForegroundService.start(this) {
                try {
                    @Suppress("DEPRECATION")
                    val proj = mediaProjectionManager.getMediaProjection(resultCode, data!!)
                    mediaProjection = proj
                    val vm = androidx.lifecycle.ViewModelProvider(this)[com.classroom.translator.ui.TranslatorViewModel::class.java]
                    vm.setMediaProjection(proj)
                    (application as TranslatorApp).viewModel = vm
                    android.util.Log.i("MainActivity", "projection granted & handed to VM")
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "getMediaProjection failed", e)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

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

    /** 请求屏幕录制/内录授权（测试模式用） */
    fun requestProjectionPermission() {
        projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    override fun onDestroy() {
        super.onDestroy()
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        // 同步停掉内录前台服务（否则从最近任务划掉 App 后服务仍挂着）
        ProjectionForegroundService.stop(this)
    }
}
