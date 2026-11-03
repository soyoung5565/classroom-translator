package com.classroom.translator.local

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

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
 *
 * 【2026-11-03 新增：双引擎热切换轮换】
 * 用户反馈「时间长了就卡住，正确率下降，甚至整个引擎直接卡住」。
 * 项目自己的实测（2026-09-24）已坐实长课退化的真因是**内存枯竭**：
 *   MemFree 165MB / zram swap 9.2GB / ASR VmHWM 2.64GB → 重启两个 llama-server 后
 *   MemFree 回到 2973MB，ASR 尾段延迟从 22.3s 掉回 3.09s（7 倍改善）。
 * 所以「定期换引擎」不是玄学，是唯一被本项目数据验证过的解法。
 *
 * 轮换时序（用户 2026-11-03 明确指定）：
 *   - 周期 5 分钟；4 分 30 秒时开始启动备用引擎 B；
 *   - B 模型加载完（/health 就绪）后**接管** A；
 *   - A 延迟 2.5s 释放（让在途请求跑完）；
 *   - 下一轮 A 变成备用引擎，循环。
 *   - 若 B 在周期结束前没就绪 → A 继续服务，绝不为了「守时」把用户的服务搞挂。
 *
 * 端口：A 用设置里的端口（默认 ASR 8000 / 翻译 8002），B 用 +100 的端口，
 *      两边同时存在、互不抢端口；接管靠切换**对外暴露的 URL**（见 currentAsrBaseUrl）。
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

        /** 【2026-11-03】引擎轮换周期：5 分钟（用户明确要求）—— 真相源在 EngineRotationPolicy */
        const val ROTATE_MS = EngineRotationPolicy.ROTATE_MS
        /** 【2026-11-03】提前多久启动备用引擎：4 分 30 秒 */
        const val PREPARE_LEAD_MS = EngineRotationPolicy.PREPARE_LEAD_MS
        /** 备用引擎的端口偏移（避免与主引擎抢同一个端口） */
        private const val STANDBY_PORT_OFFSET = 100
        /** 连续失败多少次就强制重启该服务（挂死检测） */
        private const val FAILURE_THRESHOLD = 3
        /** 单个日志文件上限（超过就删掉重开，防止磁盘被 llama-server 日志撑爆） */
        private const val MAX_LOG_BYTES = 4L * 1024 * 1024
        /** 【2026-11-03】轮换接管时，等待在途请求跑完的最长时间 */
        private const val DRAIN_MAX_MS = 10_000L

        @Volatile private var instance: LocalLlmServerManager? = null
        fun get(app: Application): LocalLlmServerManager =
            instance ?: synchronized(this) {
                instance ?: LocalLlmServerManager(app).also { instance = it }
            }
    }

    /**
     * 引擎槽位 = ASR + 翻译两个 llama-server 进程。
     * A/B 两个槽位交替当「对外服务的那一个」，实现无感热切换。
     *
     * ⚠️ 真相源在 [EngineRotationPolicy.Slot]（测试直接用它，避免两处定义漂移）。
     *    Kotlin **不支持嵌套 typealias** → 只能直接写全名 `EngineRotationPolicy.Slot`。
     */


    // ---- 进程状态 ----
    @Volatile private var slotA: EngineRotationPolicy.Slot? = null
    @Volatile private var slotB: EngineRotationPolicy.Slot? = null
    /** true = A 在对外服务；false = B 在对外服务 */
    @Volatile private var activeIsA: Boolean = true
    @Volatile private var wantRunning = false
    /** 翻译会话进行中：此时即使 App 退到后台（如切去微信）也**不释放**服务 */
    @Volatile private var sessionActive = false
    @Volatile private var lastError: String? = null
    /** 正在后台自动下载模型（避免重复触发） */
    @Volatile var autoDownloading: Boolean = false
    private var watchThread: Thread? = null
    private var rotationThread: Thread? = null
    private var killHandler: android.os.Handler? = null
    private var activitiesStarted = 0

    /** 【2026-11-03】挂死检测：连续失败计数（/health 只探端口，探不出「进程活着但推理卡死」） */
    private val asrFailures = AtomicInteger(0)
    private val transFailures = AtomicInteger(0)

    /**
     * 【2026-11-03】在途请求计数 —— 轮换接管时必须等它们跑完。
     *
     * 否则：接管瞬间正好有一个 ASR 请求在跑 → 旧引擎被 kill → 那一句直接失败 →
     * 用户上课时看到"某句话突然没了"。课堂场景下一句都不能丢。
     */
    private val asrInFlight = AtomicInteger(0)
    private val transInFlight = AtomicInteger(0)

    /** 轮换时序（真相源在 EngineRotationPolicy.Timers —— 测试直接用它） */
    private val timers = EngineRotationPolicy.Timers()
    @Volatile private var standbyPreparing = false

    private val binFile: File get() = File(app.applicationInfo.nativeLibraryDir, BIN_NAME)
    /** 【2026-11-02】模型目录改由 ModelStore 决定 —— 优先共享存储，清应用数据不会删 */
    val modelStore: ModelStore = ModelStore(app)
    private val modelDir: File get() = modelStore.modelDir()
    private val logDir: File get() = File(app.getExternalFilesDir(null), "logs").apply { mkdirs() }

    // ------------------------------------------------------------------
    // 槽位访问
    // ------------------------------------------------------------------

    private fun active(): EngineRotationPolicy.Slot? = if (activeIsA) slotA else slotB
    private fun standby(): EngineRotationPolicy.Slot? = if (activeIsA) slotB else slotA

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
        append(" | 引擎:").append(active()?.name ?: "-")
        append(" ASR:").append(if (healthy(active()?.asrPort ?: 0)) "OK" else "DOWN")
        append(" 翻译:").append(if (healthy(active()?.transPort ?: 0)) "OK" else "DOWN")
        lastError?.let { append(" | 最后错误: ").append(it) }
    }

    /** 轮换状态（给 UI / logcat 看） */
    fun rotationInfo(): String {
        val sb = standby()
        val now = System.currentTimeMillis()
        return buildString {
            append("引擎=").append(active()?.name ?: "-")
            append(" 备用=").append(sb?.name ?: "-")
            append(if (standbyPreparing) " 备机启动中" else " 备机待命")
            val remain = (timers.rotateAt - now).coerceAtLeast(0)
            append(" 下次轮换=").append(remain / 1000).append("s")
        }
    }

    /** 由 UI 调用：解析设置里的端口（默认 8080 / 8002）后拉起 */
    fun start(asrUrl: String?, transUrl: String?, blockUntilReady: Boolean): Boolean {
        currentAsrUrl = asrUrl
        currentTransUrl = transUrl
        val aAsr = portOf(asrUrl, 8080)
        val aTrans = portOf(transUrl, 8002)
        asrPort = aAsr
        transPort = aTrans
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
        ensureSlots(aAsr, aTrans)
        val slot = active() ?: return false
        val now = System.currentTimeMillis()
        if (!healthy(slot.transPort)) {
            slot.transProc = spawn(transArgs(slot), "trans_${slot.name}.log")
            slot.transStartedAt = now
        }
        if (!healthy(slot.asrPort)) {
            slot.asrProc = spawn(asrArgs(slot), "asr_${slot.name}.log")
            slot.asrStartedAt = now
        }
        startWatchdog()
        startRotationLoop()
        if (!blockUntilReady) return true
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            if (healthy(slot.asrPort) && healthy(slot.transPort)) return true
            try { Thread.sleep(1000) } catch (e: InterruptedException) { return false }
        }
        lastError = "本地服务启动超时"
        return false
    }

    /**
     * 建立（或复用）A/B 两个槽位。
     *
     * 【2026-11-03】**必须复用**：prewarm() 先起服务，用户点「开始录音」时 start() 会被再调一次。
     * 若每次 start() 都重建槽位 → 已加载好的模型被丢掉 → 每次录音都要多等 10~30 秒。
     * 只有端口变了才重建。
     */
    private fun ensureSlots(aAsr: Int, aTrans: Int) {
        val cur = slotA
        if (cur != null && cur.asrPort == aAsr && cur.transPort == aTrans && slotB != null) return
        killSlotProcesses(cur)
        killSlotProcesses(slotB)
        slotA = EngineRotationPolicy.Slot("A", aAsr, aTrans)
        slotB = EngineRotationPolicy.Slot("B", aAsr + STANDBY_PORT_OFFSET, aTrans + STANDBY_PORT_OFFSET)
        activeIsA = true
        standbyPreparing = false
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

    /** ASR 服务是否健康（/health 返 200）—— 看的是**当前对外服务**的那个引擎 */
    fun isAsrHealthy(): Boolean = healthy(active()?.asrPort ?: 0)

    /** 翻译服务是否健康 */
    fun isTransHealthy(): Boolean = healthy(active()?.transPort ?: 0)

    /** 是否希望服务在跑（含正在加载模型的阶段） */
    fun isRunning(): Boolean = wantRunning

    /** 当前对外的 ASR 端口（供体检展示；轮换后会自动变成备用引擎的端口） */
    fun asrPort(): Int = active()?.asrPort ?: asrPort

    /** 当前对外的翻译端口 */
    fun transPort(): Int = active()?.transPort ?: transPort

    /**
     * 【2026-11-03】当前对外的 ASR Base URL。
     * 轮换后端口会变 —— 调用方必须用这个方法拿 URL，不能用设置里那个写死的。
     */
    fun currentAsrBaseUrl(): String = withPort(currentAsrUrl, active()?.asrPort ?: asrPort)

    /** 【2026-11-03】当前对外的翻译 Base URL */
    fun currentTransBaseUrl(): String = withPort(currentTransUrl, active()?.transPort ?: transPort)

    /**
     * 重启**当前对外**的引擎（自修复动作）。
     *
     * 【2026-11-03】不再走 stop()+start()：那会把整个服务停掉再重建（端口空窗期更久、
     * 还会把已加载好的备用引擎一起清掉）。现在只换掉对外那个引擎的两个进程，
     * 端口不变 → 调用方的 URL 不用改 → 客户端零感知。
     */
    suspend fun restartServices(): Boolean {
        val slot = active()
        Log.i(TAG, "restartServices: 手动重启本地服务 (${slot?.name})")
        if (slot == null) return false
        killSlotProcesses(slot)
        val ok = respawnSlot(slot)
        Log.i(TAG, "restartServices: ${if (ok) "成功" else "失败"} - ${statusText()}")
        return ok
    }

    /** 记住最近一次 start() 用的 URL，重启时复用 */
    @Volatile private var currentAsrUrl: String? = null
    @Volatile private var currentTransUrl: String? = null

    /** 配置里的端口（不随轮换变化） */
    @Volatile private var asrPort: Int = 8080
    @Volatile private var transPort: Int = 8002

    /** 释放两个服务（无会话且退到后台时调用） */
    fun stop() {
        wantRunning = false
        standbyPreparing = false
        killSlotProcesses(active())
        killSlotProcesses(standby())
        slotA = null
        slotB = null
        asrFailures.set(0)
        transFailures.set(0)
        // 兜底：清除本 App uid 下残留的 llama-server（进程名即二进制名）
        runCatching {
            ProcessBuilder("sh", "-c", "pkill -f $BIN_NAME").start()
        }
        Log.i(TAG, "本地服务已释放")
    }

    // ------------------------------------------------------------------
    // 【2026-11-03】挂死检测：/health 只探端口，探不出「进程活着但推理卡死」
    // ------------------------------------------------------------------

    /** 【2026-11-03】ASR 请求进入（在途计数 +1） */
    fun enterAsr() { asrInFlight.incrementAndGet() }

    /** 【2026-11-03】ASR 请求结束（在途计数 -1） */
    fun exitAsr() { asrInFlight.decrementAndGet() }

    /** 【2026-11-03】翻译请求进入（在途计数 +1） */
    fun enterTrans() { transInFlight.incrementAndGet() }

    /** 【2026-11-03】翻译请求结束（在途计数 -1） */
    fun exitTrans() { transInFlight.decrementAndGet() }

    /** 当前在途请求数（ASR + 翻译） */
    fun inFlight(): Int = asrInFlight.get() + transInFlight.get()

    /** ASR 请求成功 */
    fun reportAsrSuccess() { asrFailures.set(0) }

    /** ASR 请求失败（超时/5xx/连接错）—— 连续 3 次强制重启 ASR 引擎 */
    fun reportAsrFailure() {
        val n = asrFailures.incrementAndGet()
        Log.w(TAG, "ASR 请求失败（累计 $n 次）")
        if (n >= FAILURE_THRESHOLD) {
            asrFailures.set(0)
            Log.w(TAG, "ASR 连续失败 $n 次 → 强制重启引擎")
            restartActiveAsr()
        }
    }

    /** 翻译请求成功 */
    fun reportTransSuccess() { transFailures.set(0) }

    /** 翻译请求失败 —— 连续 3 次强制重启翻译引擎 */
    fun reportTransFailure() {
        val n = transFailures.incrementAndGet()
        Log.w(TAG, "翻译请求失败（累计 $n 次）")
        if (n >= FAILURE_THRESHOLD) {
            transFailures.set(0)
            Log.w(TAG, "翻译连续失败 $n 次 → 强制重启引擎")
            restartActiveTrans()
        }
    }

    /** 强制重启当前对外引擎的 ASR 进程 */
    fun restartActiveAsr() {
        val slot = active() ?: return
        runCatching { slot.asrProc?.destroy() }
        slot.asrProc = null
        slot.asrStartedAt = System.currentTimeMillis()
        slot.asrProc = spawn(asrArgs(slot), "asr_${slot.name}.log")
        Log.i(TAG, "ASR 引擎已重启 (${slot.name})")
    }

    /** 强制重启当前对外引擎的翻译进程 */
    fun restartActiveTrans() {
        val slot = active() ?: return
        runCatching { slot.transProc?.destroy() }
        slot.transProc = null
        slot.transStartedAt = System.currentTimeMillis()
        slot.transProc = spawn(transArgs(slot), "trans_${slot.name}.log")
        Log.i(TAG, "翻译引擎已重启 (${slot.name})")
    }

    // ---------- 内部实现 ----------

    private fun spawn(args: List<String>, logName: String): Process? = try {
        val pb = ProcessBuilder(args)
        pb.redirectErrorStream(true)
        // 【2026-11-03】日志文件封顶：llama-server 每次请求都打 prompt eval 明细，
        // 长课 + 5 分钟轮换（每次都 append）→ 日志文件会涨到几百 MB。
        val logFile = File(logDir, logName)
        if (logFile.exists() && logFile.length() > MAX_LOG_BYTES) {
            Log.w(TAG, "日志过大，删除重开: $logName (${logFile.length()} bytes)")
            runCatching { logFile.delete() }
        }
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
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
    private fun asrArgs(slot: EngineRotationPolicy.Slot): List<String> = listOf(
        binFile.absolutePath,
        "-m", File(modelDir, modelStore.asrModel.fileName).absolutePath,
        "--mmproj", File(modelDir, modelStore.asrMmproj.fileName).absolutePath,
        "--host", "127.0.0.1", "--port", slot.asrPort.toString(),
        "-ngl", "99", "-t", "4",
        "--parallel", "1", "-c", "2048", "--temp", "0",
        "--cache-ram", "0", "--no-cache-prompt"     // P0：禁 KV/prompt 缓存膨胀
    )

    /** 翻译：Qwen3-0.6B 纯文本 */
    private fun transArgs(slot: EngineRotationPolicy.Slot): List<String> = listOf(
        binFile.absolutePath,
        "-m", File(modelDir, modelStore.transModel.fileName).absolutePath,
        "--host", "127.0.0.1", "--port", slot.transPort.toString(),
        "-ngl", "99", "-t", "2",
        "--parallel", "1", "-c", "2048",
        "--cache-ram", "0", "--no-cache-prompt"
    )

    private fun healthy(port: Int): Boolean {
        if (port <= 0) return false
        return try {
            val c = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
            c.connectTimeout = 1500
            c.readTimeout = 1500
            val ok = c.responseCode == 200
            c.disconnect()
            ok
        } catch (t: Throwable) { false }
    }

    /** 前台巡检：掉线即拉起（替代电脑侧 watchdog.sh） */
    private fun startWatchdog() {
        if (watchThread?.isAlive == true) return
        watchThread = Thread {
            Log.i(TAG, "本地服务巡检已启动")
            while (wantRunning) {
                try { Thread.sleep(WATCH_MS) } catch (e: InterruptedException) { break }
                if (!wantRunning) break
                val slot = active() ?: continue
                val now = System.currentTimeMillis()
                // 关键：进程还活着且在「模型加载宽限期」内 → 判定为"还在加载"，绝不重启
                // （否则会重复起第二个实例去抢同一个端口，后起的必然绑定失败退出）
                val transLoading = slot.transProc?.isAlive == true && now - slot.transStartedAt < LOAD_GRACE_MS
                val asrLoading = slot.asrProc?.isAlive == true && now - slot.asrStartedAt < LOAD_GRACE_MS
                if (!healthy(slot.transPort) && !transLoading) {
                    Log.w(TAG, "翻译服务掉线 -> 重启 ${slot.name}")
                    slot.transProc = spawn(transArgs(slot), "trans_${slot.name}.log")
                    slot.transStartedAt = now
                }
                if (!healthy(slot.asrPort) && !asrLoading) {
                    Log.w(TAG, "ASR 服务掉线 -> 重启 ${slot.name}")
                    slot.asrProc = spawn(asrArgs(slot), "asr_${slot.name}.log")
                    slot.asrStartedAt = now
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    // ------------------------------------------------------------------
    // 【2026-11-03】双引擎热切换轮换
    // ------------------------------------------------------------------

    private fun startRotationLoop() {
        if (rotationThread?.isAlive == true) return
        val now = System.currentTimeMillis()
        timers.beginRound(now)
        rotationThread = Thread {
            Log.i(TAG, "引擎轮换已启动：周期 ${ROTATE_MS / 60000} 分钟，提前 ${PREPARE_LEAD_MS / 1000}s 启动备用引擎")
            while (wantRunning) {
                try { Thread.sleep(1000) } catch (e: InterruptedException) { break }
                if (!wantRunning) break
                try {
                    rotationTick()
                } catch (t: Throwable) {
                    Log.e(TAG, "轮换 tick 异常", t)
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun rotationTick() {
        val sb = standby() ?: return
        val now = System.currentTimeMillis()
        val procAlive = sb.asrProc != null || sb.transProc != null
        val isHealthy = slotHealthy(sb)

        // 判定逻辑只有一份 —— 在 EngineRotationPolicy 里（单元测试直接测它）
        when (EngineRotationPolicy.decide(
            nowMs = now,
            timers = timers,
            standbyPreparing = standbyPreparing,
            standbyProcAlive = procAlive,
            standbyHealthy = isHealthy,
            graceMs = LOAD_GRACE_MS
        )) {
            EngineRotationPolicy.Action.START_STANDBY -> {
                standbyPreparing = true
                Log.i(TAG, "轮换：启动备用引擎 ${sb.name} (ASR:${sb.asrPort} 翻译:${sb.transPort})")
                sb.transProc = spawn(transArgs(sb), "trans_${sb.name}.log")
                sb.transStartedAt = now
                sb.asrProc = spawn(asrArgs(sb), "asr_${sb.name}.log")
                sb.asrStartedAt = now
            }
            EngineRotationPolicy.Action.CUT_OVER -> cutOver(sb)
            EngineRotationPolicy.Action.GIVE_UP -> {
                Log.w(TAG, "轮换：备用引擎 ${sb.name} 未就绪，放弃本轮，继续由 ${active()?.name} 服务")
                killSlotProcesses(sb)
                standbyPreparing = false
                timers.reset(now)
            }
            EngineRotationPolicy.Action.WAIT -> Unit
        }
    }

    private fun slotHealthy(slot: EngineRotationPolicy.Slot): Boolean =
        healthy(slot.transPort) && healthy(slot.asrPort)

    /** 切换对外服务引擎：备用上位 → 旧的延迟释放 */
    private fun cutOver(sb: EngineRotationPolicy.Slot) {
        val old = active()
        Log.i(TAG, "轮换：${sb.name} 已就绪 → 接管（${old?.name} 让位）")
        activeIsA = !activeIsA          // standby 变 active
        standbyPreparing = false
        val now = System.currentTimeMillis()
        timers.beginRound(now)
        /**
         * 让在途请求跑完再杀旧的。
         *
         * 【2026-11-03 改进】原来是「固定等 2.5 秒」—— 但 ASR 单句可能要 3~10 秒
         * （长课末段更慢），2.5 秒不够 → 接管瞬间那个请求被 kill → **那句话直接丢了**。
         *
         * 现在改成「等在途请求归零，最多等 DRAIN_MAX_MS」：
         *   - 正常情况：几十毫秒就归零 → 几乎无感
         *   - 请求卡住：最多等 10 秒，超时也照常杀（不能为了等一个请求永远不换引擎）
         */
        Thread {
            val deadline = System.currentTimeMillis() + DRAIN_MAX_MS
            while (inFlight() > 0 && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(200) } catch (e: InterruptedException) { break }
            }
            val left = inFlight()
            if (left > 0) Log.w(TAG, "轮换：仍有 $left 个在途请求，杀掉旧引擎")
            killSlotProcesses(old)
            Log.i(TAG, "轮换完成：${active()?.name} 服务中 | ${rotationInfo()}")
        }.also { it.isDaemon = true; it.start() }
    }

    /** 杀掉某个槽位的进程（不 pkill，避免误杀另一个槽位） */
    private fun killSlotProcesses(slot: EngineRotationPolicy.Slot?) {
        if (slot == null) return
        runCatching { slot.transProc?.destroy() }
        runCatching { slot.asrProc?.destroy() }
        slot.transProc = null
        slot.asrProc = null
    }

    /** 重新拉起某个槽位的两个进程并等待就绪 */
    private fun respawnSlot(slot: EngineRotationPolicy.Slot): Boolean {
        val now = System.currentTimeMillis()
        slot.transProc = spawn(transArgs(slot), "trans_${slot.name}.log")
        slot.transStartedAt = now
        slot.asrProc = spawn(asrArgs(slot), "asr_${slot.name}.log")
        slot.asrStartedAt = now
        val deadline = now + 120_000
        while (System.currentTimeMillis() < deadline) {
            if (healthy(slot.transPort) && healthy(slot.asrPort)) return true
            try { Thread.sleep(1000) } catch (e: InterruptedException) { return false }
        }
        return false
    }

    private fun portOf(url: String?, def: Int): Int =
        url?.let { runCatching { URL(it).port }.getOrNull() }?.takeIf { it > 0 } ?: def

    /**
     * 把 URL 的端口换掉（保留 scheme / host / path）。
     * 轮换后对外端口会变，但用户设置里的 host/path 语义不变。
     */
    private fun withPort(url: String?, port: Int): String {
        if (port <= 0) return url ?: ""
        return try {
            val u = URL(url ?: "")
            val path = u.path.ifEmpty { "/v1" }
            "${u.protocol}://${u.host}:$port$path"
        } catch (t: Throwable) {
            "http://127.0.0.1:$port/v1"
        }
    }

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
