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
        /** 【2026-10-22】GPU 加速库，必须与 BIN_NAME 一起打包（否则二进制无法启动） */
        private const val OPENCL_LIB = "libOpenCL.so"
        /**
         * 【2026-11-02】模型文件名不再硬编码在这里，统一由 [ModelStore] 提供
         * （下载、校验、迁移都走那一处，避免两处文件名对不上）。
         */
        private const val ASR_MODEL = "Qwen3-ASR-0.6B-Q8_0.gguf"
        private const val ASR_MMPROJ = "mmproj-Qwen3-ASR-0.6B-Q8_0.gguf"
        private const val TRANS_MODEL = "Qwen3-0.6B-Q4_K_M.gguf"
        private const val WATCH_MS = 10_000L
        private const val LOAD_GRACE_MS = 90_000L   // 模型加载宽限期：期间即使 /health 未就绪也不算掉线
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
    @Volatile private var asrStartedAt = 0L
    @Volatile private var transStartedAt = 0L
    @Volatile private var asrPort = 8080
    @Volatile private var transPort = 8002
    @Volatile private var wantRunning = false
    /** 翻译会话进行中：此时即使 App 退到后台（如切去微信）也**不释放**服务 */
    @Volatile private var sessionActive = false
    @Volatile private var lastError: String? = null
    /** 正在后台自动下载模型（避免重复触发） */
    @Volatile var autoDownloading: Boolean = false
    private var watchThread: Thread? = null
    private var killHandler: android.os.Handler? = null
    private var activitiesStarted = 0

    private val binFile: File get() = File(app.applicationInfo.nativeLibraryDir, BIN_NAME)
    /** 【2026-11-02】模型目录改由 ModelStore 决定 —— 优先共享存储，清应用数据不会删 */
    val modelStore: ModelStore = ModelStore(app)
    private val modelDir: File get() = modelStore.modelDir()
    private val logDir: File get() = File(app.getExternalFilesDir(null), "logs").apply { mkdirs() }

    /** 模型/二进制是否齐备（供 UI 提示）—— 现在带大小校验，能识别"下到一半的坏文件" */
    fun assetsReady(): Boolean =
        binFile.exists() && modelStore.allReady()

    /** 缺失/损坏的模型说明（给 UI 显示） */
    fun assetsError(): String {
        if (!binFile.exists()) return "缺少本地推理可执行文件（$BIN_NAME）"
        val broken = modelStore.brokenModels()
        if (broken.isEmpty()) return ""
        return broken.joinToString("；") { it.problem() ?: "" }
    }

    /**
     * 依赖库是否齐备。
     *
     * 【2026-10-22】libOpenCL.so 必须和 libllamasrv.so 一起打包进 jniLibs。
     * 少了它 → 二进制启动时报 "library libOpenCL.so not found" → 本地 ASR 完全不可用，
     * 而 App 界面只是安静地"正在听..."，用户完全不知道发生了什么。
     */
    fun depsReady(): Boolean = File(app.applicationInfo.nativeLibraryDir, OPENCL_LIB).exists()

    /** 依赖库缺失时的说明（给 UI 显示） */
    fun depsError(): String =
        if (depsReady()) "" else "缺少 $OPENCL_LIB（GPU 加速库），本地识别无法启动"

    fun statusText(): String = buildString {
        append(if (wantRunning) "运行中" else "已停止")
        append(" | ASR:"); append(if (healthy(asrPort)) "OK" else "DOWN")
        append(" 翻译:"); append(if (healthy(transPort)) "OK" else "DOWN")
        lastError?.let { append(" | 最后错误: ").append(it) }
    }

    /** 由 UI 调用：解析设置里的端口（默认 8080 / 8002）后拉起 */
    fun start(asrUrl: String?, transUrl: String?, blockUntilReady: Boolean): Boolean {
        currentAsrUrl = asrUrl
        currentTransUrl = transUrl
        asrPort = portOf(asrUrl, 8080)
        transPort = portOf(transUrl, 8002)
        wantRunning = true
        sessionActive = true
        lastError = null
        if (!assetsReady()) {
            // 【2026-11-02】说清楚缺什么、缺多少，而不是一句"缺失"。
            // 用户看到的原话是「本地推理服务启动失败：运行中 | ASR:DOWN 翻译:DOWN | 最后错误: 本地模型或可执行文件缺失」——
            // 定位不到是哪个模型、也没告诉他模型住在哪、怎么找回。
            val detail = assetsError().ifBlank { "模型文件缺失或损坏" }
            lastError = detail
            Log.e(TAG, "$lastError: bin=${binFile.exists()} dir=$modelDir durable=${modelStore.isDurable()}")

            // 模型缺失是「清除应用数据」之后最常见的故障 —— 后台自动重新下载，
            // 用户不用再去设置页手动点一次。下载完看门狗会把它拉起来。
            if (binFile.exists()) {
                autoDownloading = true
                Thread {
                    runCatching {
                        // ensureAssets 是 suspend —— 在普通线程里用 runBlocking 驱动
                        kotlinx.coroutines.runBlocking { ensureAssets() }
                    }
                    autoDownloading = false
                    Log.i(TAG, "auto download finished: ${statusText()}")
                }.also { it.isDaemon = true; it.start() }
                lastError = "$detail（正在自动重新下载，完成后会自动启动）"
            }
            return false
        }
        if (!healthy(transPort)) { transProc = spawn(transArgs(), "trans.log"); transStartedAt = System.currentTimeMillis() }
        if (!healthy(asrPort)) { asrProc = spawn(asrArgs(), "asr.log"); asrStartedAt = System.currentTimeMillis() }
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

    /**
     * 确保模型齐备：缺什么下什么（断点续传）。
     * 「清除应用数据」把模型删掉后，这是唯一能把 1.4GB 资产找回来的途径。
     *
     * @param onProgress 进度回调（下载字节 / 总字节）
     * @return true = 全部就绪
     */
    suspend fun ensureAssets(
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }
    ): Boolean {
        // 先把老目录里的模型搬过来 —— 用户已经下过的 1.4GB 不该再下一遍
        val moved = modelStore.migrateFromLegacy()
        if (moved > 0) Log.i(TAG, "migrated $moved bytes of models to durable storage")

        var allOk = true
        for (spec in modelStore.specs) {
            val state = modelStore.stateOf(spec)
            if (state.ok) continue
            Log.w(TAG, "model not ready: ${state.problem()}")
            val ok = modelStore.download(spec) { done, total -> onProgress(done, total) }
            if (!ok) {
                allOk = false
                Log.e(TAG, "model download failed: ${spec.fileName}")
            }
        }
        return allOk && assetsReady()
    }

    /**
     * 预热：App 一打开就后台把服务拉起来（不阻塞 UI）。
     * 用户点「开始」时模型已加载完毕 → 立刻可用。退到后台且无会话时照常释放。
     */
    fun prewarm(asrUrl: String?, transUrl: String?) {
        if (wantRunning) return                 // 已在跑（可能有活跃会话）→ 不重复
        Thread {
            runCatching {
                start(asrUrl, transUrl, blockUntilReady = false)
                sessionActive = false          // 预热不算会话：退后台仍会释放
                scheduleStopIfIdle()
            }
        }.also { it.isDaemon = true; it.start() }
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

    // ------------------------------------------------------------------
    // 对外健康状态（供自修复工具体检用）
    // ------------------------------------------------------------------

    /** ASR 服务是否健康（/health 返 200） */
    fun isAsrHealthy(): Boolean = healthy(asrPort)

    /** 翻译服务是否健康 */
    fun isTransHealthy(): Boolean = healthy(transPort)

    /** 是否希望服务在跑（含正在加载模型的阶段） */
    fun isRunning(): Boolean = wantRunning

    /** 当前 ASR 端口（供体检展示） */
    fun asrPort(): Int = asrPort

    /** 当前翻译端口 */
    fun transPort(): Int = transPort

    /**
     * 重启两个服务（自修复动作）。
     * 先杀掉旧进程再拉起 —— 掉线自愈有时会留下"端口被占但进程已死"的僵持状态。
     */
    suspend fun restartServices(): Boolean {
        Log.i(TAG, "restartServices: 手动重启本地服务")
        stop()
        Thread.sleep(800)
        val ok = start(currentAsrUrl, currentTransUrl, blockUntilReady = true)
        Log.i(TAG, "restartServices: ${if (ok) "成功" else "失败"} - ${statusText()}")
        return ok
    }

    /** 记住最近一次 start() 用的 URL，重启时复用 */
    @Volatile private var currentAsrUrl: String? = null
    @Volatile private var currentTransUrl: String? = null

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
            // 【2026-10-22 关键修复】LD_LIBRARY_PATH 只指向 App 自己的 nativeLibraryDir。
            //
            // 之前的写法是 nativeLibraryDir + ":/vendor/lib64:/system/lib64"，
            // 结果本地推理服务 100% 起不来（用户反馈"完全没有声音"、一个字都识别不到）：
            //   CANNOT LINK EXECUTABLE "libllamasrv.so": cannot locate symbol
            //   "_ZNSt3__113__hash_memoryEPKvm" referenced by "/system/lib64/liblog.so"
            //
            // 原因：/vendor/lib64 里的 libOpenCL.so 被优先加载，而它 NEEDED libc++.so
            // （私有 API）→ 拉入 liblog.so → liblog.so 需要 libc++ 的 __hash_memory。
            // 但作为「独立可执行文件」启动时（不是 dlopen），Android linker namespace
            // 只暴露公开 API 库，libc++ 不可见 → 符号解析失败 → 整个二进制无法启动。
            //
            // 修复：把 libOpenCL.so 打包进 APK（jniLibs），它就在 nativeLibraryDir，
            //      且 LD_LIBRARY_PATH 不再包含 /vendor/lib64，避免加载到系统那份。
            // 实测（真机 Android 17）：只留 nativeLibraryDir 时二进制正常启动。
            this["LD_LIBRARY_PATH"] = app.applicationInfo.nativeLibraryDir
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
        "-m", File(modelDir, modelStore.asrModel.fileName).absolutePath,
        "--mmproj", File(modelDir, modelStore.asrMmproj.fileName).absolutePath,
        "--host", "127.0.0.1", "--port", asrPort.toString(),
        "-ngl", "99", "-t", "4",
        "--parallel", "1", "-c", "2048", "--temp", "0",
        "--cache-ram", "0", "--no-cache-prompt"     // P0：禁 KV/prompt 缓存膨胀
    )

    /** 翻译：Qwen3-0.6B 纯文本 */
    private fun transArgs(): List<String> = listOf(
        binFile.absolutePath,
        "-m", File(modelDir, modelStore.transModel.fileName).absolutePath,
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
                val now = System.currentTimeMillis()
                // 关键：进程还活着且在「模型加载宽限期」内 → 判定为"还在加载"，绝不重启
                // （否则会重复起第二个实例去抢同一个端口，后起的必然绑定失败退出）
                val transLoading = transProc?.isAlive == true && now - transStartedAt < LOAD_GRACE_MS
                val asrLoading = asrProc?.isAlive == true && now - asrStartedAt < LOAD_GRACE_MS
                if (!healthy(transPort) && !transLoading) {
                    Log.w(TAG, "翻译服务掉线 -> 重启")
                    transProc = spawn(transArgs(), "trans.log")
                    transStartedAt = now
                }
                if (!healthy(asrPort) && !asrLoading) {
                    Log.w(TAG, "ASR 服务掉线 -> 重启")
                    asrProc = spawn(asrArgs(), "asr.log")
                    asrStartedAt = now
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
