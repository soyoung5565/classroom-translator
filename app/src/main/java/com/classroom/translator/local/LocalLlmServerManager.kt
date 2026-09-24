package com.classroom.translator.local

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 本地推理服务管理器 —— 让 App 自己拉起 llama-server（ASR + 翻译），并在服务挂掉时自动重启。
 *
 * 设计要点：
 *  1. 可执行文件为什么放在 nativeLibraryDir？
 *     Android 10+ 禁止 App 执行自己可写目录里的文件（W^X 限制），而 nativeLibraryDir 只读、
 *     且 SELinux 标记为可执行。因此把「静态链接的 llama-server」命名成 lib*.so 放进 jniLibs，
 *     安装后落在 nativeLibraryDir，App 可用 ProcessBuilder 直接启动 —— 全程不需要电脑/adb。
 *  2. 模型放在 App 私有外部目录（getExternalFilesDir/models），App 进程可直接读。
 *  3. 存活策略：App 退到后台 N 秒后自动释放（省内存/降温）；前台时 10 秒巡检一次，掉线自愈。
 */
class LocalLlmServerManager private constructor(private val app: Application) {

    companion object {
        private const val TAG = "LocalLlmServer"
        private const val BIN_NAME = "libllamasrv.so"      // jniLibs 中的名字
        private const val ASR_MODEL = "Qwen3-ASR-0.6B-Q8_0.gguf"
        private const val ASR_MMPROJ = "mmproj-Qwen3-ASR-0.6B-Q8_0.gguf"
        private const val TRANS_MODEL = "Qwen3-0.6B-Q4_K_M.gguf"
        private const val WATCH_MS = 10_000L
        private const val KILL_BG_DELAY_MS = 8_000L

        @Volatile private var instance: LocalLlmServerManager? = null
        fun get(app: Application): LocalLlmServerManager =
            instance ?: synchronized(this) {
                instance ?: LocalLlmServerManager(app).also { instance = it }
            }
    }

    // ---- 进程状态 ----
    @Volatile private var asrProc: Process? = null
    @Volatile private var transProc: Process? = null
    @Volatile private var asrPort = 8080
    @Volatile private var transPort = 8002
    @Volatile private var wantRunning = false
    /** 翻译会话进行中：此时即使 App 退到后台（如切去微信）也**不释放**服务 */
    @Volatile private var sessionActive = false
    @Volatile private var lastError: String? = null
    private var watchThread: Thread? = null
    private var killHandler: android.os.Handler? = null
    private var activitiesStarted = 0

    private val binFile: File get() = File(app.applicationInfo.nativeLibraryDir, BIN_NAME)
    private val modelDir: File get() = File(app.getExternalFilesDir(null), "models")
    private val logDir: File get() = File(app.getExternalFilesDir(null), "logs").apply { mkdirs() }

    /** 模型/二进制是否齐备（供 UI 提示） */
    fun assetsReady(): Boolean =
        binFile.exists() &&
        File(modelDir, ASR_MODEL).exists() &&
        File(modelDir, ASR_MMPROJ).exists() &&
        File(modelDir, TRANS_MODEL).exists()

    fun statusText(): String = buildString {
        append(if (wantRunning) "运行中" else "已停止")
        append(" | ASR:"); append(if (healthy(asrPort)) "OK" else "DOWN")
        append(" 翻译:"); append(if (healthy(transPort)) "OK" else "DOWN")
        lastError?.let { append(" | 最后错误: ").append(it) }
    }

    /** 由 UI 调用：解析设置里的端口（默认 8080 / 8002）后拉起 */
    fun start(asrUrl: String?, transUrl: String?, blockUntilReady: Boolean): Boolean {
        asrPort = portOf(asrUrl, 8080)
        transPort = portOf(transUrl, 8002)
        wantRunning = true
        sessionActive = true
        lastError = null
        if (!assetsReady()) {
            lastError = "本地模型或可执行文件缺失"
            Log.e(TAG, "$lastError: bin=${binFile.exists()} dir=$modelDir")
            return false
        }
        if (!healthy(transPort)) transProc = spawn(transArgs(), "trans.log")
        if (!healthy(asrPort)) asrProc = spawn(asrArgs(), "asr.log")
        startWatchdog()
        if (!blockUntilReady) return true
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            if (healthy(asrPort) && healthy(transPort)) return true
            try { Thread.sleep(1000) } catch (e: InterruptedException) { return false }
        }
        lastError = "本地服务启动超时"
        return false
    }

    /** 翻译会话结束：若无前台界面则释放服务（会话中切微信不会被掐断） */
    fun onSessionEnd() {
        sessionActive = false
        scheduleStopIfIdle()
    }

    private fun scheduleStopIfIdle() {
        if (sessionActive) return                       // 会话中：保活
        if (activitiesStarted > 0) return               // 前台：保活（下次点击即时可用）
        killHandler?.removeCallbacksAndMessages(null)
        killHandler?.postDelayed({ if (!sessionActive && activitiesStarted == 0) stop() }, KILL_BG_DELAY_MS)
    }

    /** 释放两个服务（无会话且退到后台时调用） */
    fun stop() {
        wantRunning = false
        runCatching { asrProc?.destroy() }
        runCatching { transProc?.destroy() }
        asrProc = null; transProc = null
        // 兜底：清除本 App uid 下残留的 llama-server（进程名即二进制名）
        runCatching {
            ProcessBuilder("sh", "-c", "pkill -f $BIN_NAME").start()
        }
        Log.i(TAG, "本地服务已释放")
    }

    // ---------- 内部实现 ----------

    private fun spawn(args: List<String>, logName: String): Process? = try {
        val pb = ProcessBuilder(args)
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(File(logDir, logName)))
        pb.environment().apply {
            // 二进制依赖 libOpenCL.so（系统 /vendor/lib64），LD_LIBRARY_PATH 兜底
            this["LD_LIBRARY_PATH"] = app.applicationInfo.nativeLibraryDir + ":/vendor/lib64:/system/lib64"
            this["MALLOC_ARENA_MAX"] = "2"   // P0 内存修复：抑制 malloc 多 arena 膨胀
        }
        pb.start().also { Log.i(TAG, "已启动 ${args.first()} ${args.drop(1).take(4).joinToString(" ")}...") }
    } catch (t: Throwable) {
        lastError = "启动失败: ${t.message}"
        Log.e(TAG, "spawn failed", t)
        null
    }

    /** ASR：Qwen3-ASR 多模态（--mmproj 音频编码器）+ GPU 全层 offload */
    private fun asrArgs(): List<String> = listOf(
        binFile.absolutePath,
        "-m", File(modelDir, ASR_MODEL).absolutePath,
        "--mmproj", File(modelDir, ASR_MMPROJ).absolutePath,
        "--host", "127.0.0.1", "--port", asrPort.toString(),
        "-ngl", "99", "-t", "4",
        "--parallel", "1", "-c", "2048", "--temp", "0",
        "--cache-ram", "0", "--no-cache-prompt"     // P0：禁 KV/prompt 缓存膨胀
    )

    /** 翻译：Qwen3-0.6B 纯文本 */
    private fun transArgs(): List<String> = listOf(
        binFile.absolutePath,
        "-m", File(modelDir, TRANS_MODEL).absolutePath,
        "--host", "127.0.0.1", "--port", transPort.toString(),
        "-ngl", "99", "-t", "2",
        "--parallel", "1", "-c", "2048",
        "--cache-ram", "0", "--no-cache-prompt"
    )

    private fun healthy(port: Int): Boolean = try {
        val c = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
        c.connectTimeout = 1500
        c.readTimeout = 1500
        val ok = c.responseCode == 200
        c.disconnect()
        ok
    } catch (t: Throwable) { false }

    /** 前台巡检：掉线即拉起（替代电脑侧 watchdog.sh） */
    private fun startWatchdog() {
        if (watchThread?.isAlive == true) return
        watchThread = Thread {
            Log.i(TAG, "本地服务巡检已启动")
            while (wantRunning) {
                try { Thread.sleep(WATCH_MS) } catch (e: InterruptedException) { break }
                if (!wantRunning) break
                if (!healthy(transPort)) {
                    Log.w(TAG, "翻译服务掉线 -> 重启")
                    transProc = spawn(transArgs(), "trans.log")
                }
                if (!healthy(asrPort)) {
                    Log.w(TAG, "ASR 服务掉线 -> 重启")
                    asrProc = spawn(asrArgs(), "asr.log")
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun portOf(url: String?, def: Int): Int =
        url?.let { runCatching { URL(it).port }.getOrNull() }?.takeIf { it > 0 } ?: def

    /** 绑定 Activity 生命周期：进前台自动预热、退后台延时释放（省内存、降温） */
    init {
        killHandler = android.os.Handler(android.os.Looper.getMainLooper())
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                activitiesStarted++
                killHandler?.removeCallbacksAndMessages(null)
            }
            override fun onActivityStopped(activity: Activity) {
                activitiesStarted = (activitiesStarted - 1).coerceAtLeast(0)
                scheduleStopIfIdle()
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
