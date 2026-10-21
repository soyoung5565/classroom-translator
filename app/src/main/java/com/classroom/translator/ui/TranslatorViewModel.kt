package com.classroom.translator.ui

import android.app.Application
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.classroom.translator.api.OpenAiApi
import com.classroom.translator.audio.AudioRecorder
import com.classroom.translator.audio.WavEncoder
import com.classroom.translator.audio.DashScopeAsrStream
import com.classroom.translator.audio.LocalVllmAsrStream
import com.classroom.translator.local.LocalLlmServerManager
import com.classroom.translator.audio.PlaybackCaptureRecorder
import com.classroom.translator.audio.AudioConverter
import com.classroom.translator.data.AppSettings
import com.classroom.translator.data.ClassSummary
import com.classroom.translator.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.classroom.translator.data.SessionManager
import com.classroom.translator.data.TranslationSession
import com.classroom.translator.data.TranslationEntryData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.classroom.translator.audio.OfflineAsrEngine

data class TranslationEntry(
    val id: Long = System.currentTimeMillis(),
    val english: String,
    val chinese: String,
    val timestamp: String = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date()),
    val latencyMs: Long = 0,
    val isCorrected: Boolean = false,
    /** 录音内偏移秒数（课堂总结时间区间 / 跳转回放用）；-1 = 未知 */
    val startSec: Int = -1,
    val endSec: Int = -1
)

data class TranslatorUiState(
    val isRecording: Boolean = false,
    val isProcessing: Boolean = false,
    val entries: List<TranslationEntry> = emptyList(),
    val status: String = "就绪",
    val audioLevel: Float = 0f,
    val error: String? = null,
    val settings: AppSettings = AppSettings()
)

/** AI 后台任务类型（课堂总结 / 云端润色） */
enum class AiTaskKind { SUMMARY, POLISH }

/**
 * AI 后台任务状态（按会话 id 跟踪）。任务跑在 viewModelScope，
 * 退出详情页/切屏不会取消 —— 回到详情页时按此状态继续显示进度/结果。
 */
data class AiTaskState(
    val kind: AiTaskKind,
    val running: Boolean = true,
    val status: String = "",
    val error: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    /** 成功完成时间戳（>0 = 结果已写入会话文件；UI 据此重载会话显示结果） */
    val completedAt: Long = 0L
)

class TranslatorViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "TranslatorVM"
        /** 课堂总结总尝试次数（首次 + 重试） */
        private const val MAX_SUMMARY_ATTEMPTS = 3
        /** 课堂总结 max_tokens：8000（3500 时实测已用 3306，截断风险高） */
        private const val SUMMARY_MAX_TOKENS = 8000
    }

    private val settingsRepo = SettingsRepository(app)
    private val audioRecorder = AudioRecorder(app)
    private val api = OpenAiApi()
    private var dashScopeStream: DashScopeAsrStream? = null
    private var localVllmStream: LocalVllmAsrStream? = null
    private val sessionManager = SessionManager(app)

    // ===== 测试模式（内录流式）=====
    private val playbackRecorder = PlaybackCaptureRecorder(app)
    private var mediaPlayer: android.media.MediaPlayer? = null
    // 测试素材：播放 + 内录喂 ASR；realPlaybackLatencyMs 记录"说话开始到 ASR 出字"的延迟
    @Volatile private var testModeActive = false
    @Volatile private var testPlaybackStartMs = 0L
    @Volatile var testLatencyMs: Long = 0L; private set
    // MediaProjection（由 Activity 授权后传入）
    private var mediaProjection: android.media.projection.MediaProjection? = null

    private var sessionStartTime: String = ""

    /**
     * 当前录音会话 id。
     * 【2026-10-20】同一段录音可能被归档多次（FAB 停止 + 点「完成」+ 改设置），
     * 用固定 id 覆盖写同一条记录，避免历史里出现重复条目。
     */
    private var currentSessionId: Long = 0L
    /** 当前录音是否已归档（决定再次归档时是覆盖还是新建） */
    private var sessionPersisted = false
    /**
     * 本地推理服务就绪前缓存的 PCM。
     * 【2026-10-20】local_vllm 模式启动时要等模型加载（10-30s），这期间麦克风
     * 已经在录了，但 pcmListener 还没挂上 → 开头说的话全丢。缓存起来补喂给 ASR。
     */
    private val pendingPcm = java.io.ByteArrayOutputStream()
    private val PENDING_PCM_MAX = 16000 * 2 * 60   // 最多缓存 60s

    /** 本次录音起点（毫秒）；停止录音后归零。用于条目→录音偏移换算 */
    private var recStartMs = 0L

    private val _state = MutableStateFlow(TranslatorUiState())
    val state: StateFlow<TranslatorUiState> = _state.asStateFlow()

    // ===== AI 后台任务（课堂总结 / 云端润色）状态：按会话 id =====
    // 关键修复（2026-09-29）：此前这两个任务在详情页的 rememberCoroutineScope 里 launch，
    // 一退出详情页协程即被取消 → 生成中止、重新进入要重来。现改为统一在 viewModelScope 跑。
    private val _aiTasks = MutableStateFlow<Map<Long, AiTaskState>>(emptyMap())
    val aiTasks: StateFlow<Map<Long, AiTaskState>> = _aiTasks.asStateFlow()

    private fun putAiTask(sessionId: Long, st: AiTaskState) {
        _aiTasks.value = _aiTasks.value + (sessionId to st)
    }

    /**
     * 启动「课堂总结」后台任务。退出详情页/切屏不会取消；
     * 结果写回会话文件（classSummary），UI 通过 aiTasks + 会话重载显示。
     */
    fun startSummaryTask(sessionId: Long) {
        if (_aiTasks.value[sessionId]?.running == true) return
        putAiTask(sessionId, AiTaskState(kind = AiTaskKind.SUMMARY, status = "正在生成课堂总结…"))
        viewModelScope.launch(Dispatchers.Default) {
            val session = sessionManager.loadSessions().find { it.id == sessionId }
            if (session == null) {
                putAiTask(sessionId, AiTaskState(kind = AiTaskKind.SUMMARY, running = false, error = "会话不存在"))
                return@launch
            }
            val r = generateSummary(session) { msg ->
                putAiTask(sessionId, AiTaskState(kind = AiTaskKind.SUMMARY, status = msg))
            }
            r.onSuccess {
                Log.i(TAG, "summary task done: session=$sessionId")
                putAiTask(sessionId, AiTaskState(kind = AiTaskKind.SUMMARY, running = false, completedAt = System.currentTimeMillis()))
            }.onFailure { e ->
                Log.e(TAG, "summary task failed: session=$sessionId", e)
                putAiTask(sessionId, AiTaskState(kind = AiTaskKind.SUMMARY, running = false, error = e.message ?: "未知错误"))
            }
        }
    }

    /**
     * 启动「云端润色」后台任务。退出详情页/切屏不会取消；
     * 结果写回会话文件（entries + orig 留档），UI 通过 aiTasks + 会话重载显示。
     */
    fun startPolishTask(sessionId: Long) {
        if (_aiTasks.value[sessionId]?.running == true) return
        putAiTask(sessionId, AiTaskState(kind = AiTaskKind.POLISH, status = "云端整课重译中…"))
        viewModelScope.launch(Dispatchers.Default) {
            val session = sessionManager.loadSessions().find { it.id == sessionId }
            if (session == null) {
                putAiTask(sessionId, AiTaskState(kind = AiTaskKind.POLISH, running = false, error = "会话不存在"))
                return@launch
            }
            val r = polishSessionCloud(session) { msg ->
                putAiTask(sessionId, AiTaskState(kind = AiTaskKind.POLISH, status = msg))
            }
            r.onSuccess {
                Log.i(TAG, "polish task done: session=$sessionId")
                putAiTask(sessionId, AiTaskState(kind = AiTaskKind.POLISH, running = false, completedAt = System.currentTimeMillis()))
            }.onFailure { e ->
                Log.e(TAG, "polish task failed: session=$sessionId", e)
                putAiTask(sessionId, AiTaskState(kind = AiTaskKind.POLISH, running = false, error = e.message ?: "未知错误"))
            }
        }
    }

    // AI batch merge buffer
    private val asrBuffer = mutableListOf<String>()
    private var lastBatchTime = 0L
    private val BATCH_SIZE = 3           // Process every 3 ASR results
    private val BATCH_TIMEOUT_MS = 10000L // Or every 10 seconds

    init {
        viewModelScope.launch {
            settingsRepo.settings.collect { settings ->
                _state.value = _state.value.copy(settings = settings)
            }
        }
    }

    fun toggleRecording() {
        if (_state.value.isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    /**
     * 测试模式：播放素材文件（系统媒体流）→ 内录（PlaybackCapture）→ 流式 ASR。
     * 麦克风 + 本地 llama-server（App 自己拉起，无需电脑）。
     * 声音全程走系统内部，无需外放（音量调最小也不影响测试，不打扰周围）。
     */
    private fun startRecording() {
        if (!audioRecorder.hasPermission) {
            _state.value = _state.value.copy(error = "需要麦克风权限")
            return
        }

        // 新的一段录音 → 新会话 id
        // 【2026-10-20】上一段若已归档（停止即保存），条目必须清空。
        // 否则新会话会把上一段的条目再写一遍 → 历史里同一段内容出现两次。
        if (sessionPersisted) {
            Log.i(TAG, "startRecording: previous session archived, clearing ${_state.value.entries.size} entries")
            _state.value = _state.value.copy(entries = emptyList())
        }
        currentSessionId = 0L
        sessionPersisted = false
        pendingPcm.reset()

        // 【2026-10-20】麦克风异常上报到 UI。
        // 原先麦克风被占用/读失败只在 logcat 里说一句，UI 照样显示"正在听..."，
        // 用户完全不知道"为什么没声音"。现在直接弹提示。
        audioRecorder.onMicError = { msg ->
            Log.w(TAG, "Mic error: $msg")
            _state.value = _state.value.copy(error = msg)
        }

        audioRecorder.start()
        recStartMs = System.currentTimeMillis()
        // 【防冻结】会话期间跑前台服务：否则 App 退到后台会被 Android App Freezer 冻结，
        // 连带 ProcessBuilder 拉起的本地推理服务一起冻住（实测：切微信后服务不再响应）
        runCatching { com.classroom.translator.SessionForegroundService.start(getApplication()) }
        _state.value = _state.value.copy(isRecording = true, status = "正在听...", error = null)

        // 【2026-10-20 修复 4】麦克风完全没声音 → 明确告知用户。
        // 用户反馈"有时候完全不知道为什么就是没有声音，我声音很大了还是没有识别到内容"。
        // 根因之一是麦克风被别的应用占用/HAL 失效，此时 AudioRecord 读到的全是 0，
        // 但 UI 一直显示"正在听..."，用户只能干等。现在 5 秒后检测一次原始电平，
        // 全零就直接提示（而不是等到整节课结束才发现白录）。
        viewModelScope.launch(Dispatchers.Default) {
            delay(5000)
            if (!_state.value.isRecording) return@launch
            val raw = audioRecorder.rawAudioLevel
            val gain = audioRecorder.currentGain
            if (raw <= 0.0001f) {
                Log.w(TAG, "Mic silent after 5s: raw=$raw gain=$gain")
                _state.value = _state.value.copy(
                    status = "正在听...（未检测到声音）",
                    error = "麦克风没有采集到任何声音。可能是被其它应用占用或已静音，请检查后重新开始。"
                )
            } else {
                Log.i(TAG, "Mic ok after 5s: raw=$raw gain=$gain")
            }
        }

        // DashScope streaming mode
        if (_state.value.settings.asrMode == "dashscope") {
            startDashScopeStreaming()
            return
        }

        // Local vLLM (Qwen3-ASR) sentence-level streaming mode
        // [手机自足] 本地推理服务由 App 自己拉起（无需电脑/adb）；首次冷启动加载模型约 10-30 秒
        if (_state.value.settings.asrMode == "local_vllm") {
            val s0 = _state.value.settings
            _state.value = _state.value.copy(status = "正在启动本地推理服务（首次约 10-30 秒）…")
            // 【2026-10-20 修复 2】先挂上 PCM listener。
            // 原先 listener 是在 startLocalVllmStreaming() 里挂的，而那要等模型加载完
            // （10-30s）—— 这期间麦克风已经在录，但音频没人接 → 开头说的全丢。
            // 现在：ASR 没就绪就先缓存（上限 60s），就绪后补喂。
            audioRecorder.setPcmListener { pcm ->
                val stream = localVllmStream
                if (stream != null) {
                    stream.feedAudio(pcm)
                } else {
                    synchronized(pendingPcm) {
                        pendingPcm.write(pcm)
                        if (pendingPcm.size() > PENDING_PCM_MAX) {
                            val excess = pendingPcm.size() - PENDING_PCM_MAX
                            val keep = pendingPcm.toByteArray()
                            pendingPcm.reset()
                            pendingPcm.write(keep, excess, keep.size - excess)
                        }
                    }
                }
            }
            viewModelScope.launch(Dispatchers.IO) {
                val mgr = LocalLlmServerManager.get(getApplication())
                val ok = mgr.start(s0.asrBaseUrl, s0.translateBaseUrl, blockUntilReady = true)
                withContext(Dispatchers.Main) {
                    if (ok) {
                        Log.i(TAG, "本地服务就绪: " + mgr.statusText())
                        startLocalVllmStreaming()
                    } else {
                        Log.e(TAG, "本地服务启动失败: " + mgr.statusText())
                        _state.value = _state.value.copy(
                            status = "本地服务启动失败",
                            error = "本地推理服务启动失败：" + mgr.statusText()
                        )
                    }
                }
            }
            return
        }

        // MIMO chunked mode (fallback)
        // Start the recording + processing loop
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Default) {
            try {
            val chunkSec = _state.value.settings.chunkSeconds
            val chunkMs = chunkSec * 1000L
            Log.i(TAG, "=== LOOP START chunk=${chunkSec}s ===")

            while (_state.value.isRecording) {
                Log.d(TAG, ">> New chunk, waiting ${chunkSec}s...")
                // Record for chunkSeconds - simple delay approach
                val startTime = System.currentTimeMillis()
                var elapsed = 0L
                while (_state.value.isRecording && elapsed < chunkMs) {
                    delay(200)
                    elapsed = System.currentTimeMillis() - startTime
                    // Update VU meter
                    _state.value = _state.value.copy(audioLevel = audioRecorder.audioLevel)
                }
                Log.d(TAG, ">> Chunk timer done, elapsed=${elapsed}ms")

                if (!_state.value.isRecording) break

                // Flush and process
                val pcmData = audioRecorder.flush()
                val size = pcmData?.size ?: 0
                Log.i(TAG, ">> Flushed: ${size} bytes")

                if (size > AudioRecorder.SAMPLE_RATE) {
                    Log.i(TAG, ">> Calling ASR with ${size} bytes...")
                    processAudio(pcmData!!, _state.value.settings)
                    Log.i(TAG, ">> ASR done")
                } else {
                    Log.w(TAG, ">> Audio too short: ${size} bytes")
                }
            }
            Log.i(TAG, "=== LOOP END ===")
            } catch (e: CancellationException) {
                Log.w(TAG, "Recording cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Recording CRASHED: ${e.message}", e)
                _state.value = _state.value.copy(
                    isRecording = false,
                    error = "录音错误: ${e.message}",
                    status = "出错"
                )
            }
        }
    }

    private suspend fun processAudio(pcmData: ByteArray, settings: AppSettings) {
        _state.value = _state.value.copy(isProcessing = true, status = "正在识别...")

        try {
            val startTime = System.currentTimeMillis()

            // Convert PCM to WAV
            val wavData = WavEncoder.encode(pcmData)
            val audioBase64 = Base64.encodeToString(wavData, Base64.NO_WRAP)

            Log.d(TAG, "Audio chunk: ${pcmData.size} bytes PCM, ${wavData.size} bytes WAV, base64=${audioBase64.length}")

            // ASR transcription
            Log.i(TAG, "Calling ASR: ${settings.asrModel} at ${settings.asrBaseUrl}")
            val english = try {
                api.transcribeAudio(
                    baseUrl = settings.asrBaseUrl,
                    apiKey = settings.asrApiKey,
                    model = settings.asrModel,
                    audioBase64 = audioBase64,
                    format = "wav"
                )
            } catch (e: Exception) {
                Log.e(TAG, "ASR failed", e)
                _state.value = _state.value.copy(
                    isProcessing = false,
                    status = "正在听...",
                    error = "ASR错误: ${e.message}"
                )
                return
            }

            Log.i(TAG, "ASR result: ${english.take(100)}")

            if (english.isBlank() || english.length < 2) {
                _state.value = _state.value.copy(isProcessing = false, status = "正在听...")
                return
            }

            // 1. Translate immediately (fast feedback)
            _state.value = _state.value.copy(status = "正在翻译...")
            val chinese = try {
                api.translate(baseUrl=settings.translateBaseUrl, apiKey=settings.translateApiKey, model=settings.translateModel, text=english)
            } catch (e: Exception) { "[翻译失败: ${e.message}]" }
            val latency = System.currentTimeMillis() - startTime
            val entry = TranslationEntry(english = english, chinese = chinese, latencyMs = latency, startSec = offsetNow())
            _state.value = _state.value.copy(entries = _state.value.entries + entry, isProcessing = false, status = "正在听...", error = null)
            autoSave(entry)
            Log.i(TAG, "Done: ${english.take(50)}... (${latency}ms)")

            // 2. 双翻译云端覆盖（每 3 条触发，云端译文后到直接替换本地）
            maybeCloudOverride()

        } catch (e: Exception) {
            Log.e(TAG, "processAudio failed", e)
            _state.value = _state.value.copy(
                isProcessing = false,
                status = "出错，重试中...",
                error = e.message
            )
        }
    }

    private fun startDashScopeStreaming() {
        val settings = _state.value.settings
        Log.i(TAG, "Starting DashScope streaming ASR")

        dashScopeStream = DashScopeAsrStream(
            apiKey = settings.asrApiKey,
            model = "fun-asr-realtime-2026-02-28",
            onResult = { text, isFinal ->
                if (text.isBlank()) return@DashScopeAsrStream
                Log.i(TAG, "DashScope result: $text (final=$isFinal)")
                if (isFinal) {
                    // Final sentence - translate immediately
                    viewModelScope.launch(Dispatchers.Default) {
                        val chinese = try {
                            api.translate(baseUrl=settings.translateBaseUrl, apiKey=settings.translateApiKey, model=settings.translateModel, text=text)
                        } catch (e: Exception) { "[翻译失败: ${e.message}]" }
                        addOrMergeEntry(text, chinese)
                        maybeCloudOverride()  // 双翻译：每 3 条云端覆盖
                    }
                } else {
                    // Intermediate result - show as preview
                    _state.value = _state.value.copy(status = "识别中: $text")
                }
            },
            onError = { error ->
                Log.e(TAG, "DashScope error: $error")
                _state.value = _state.value.copy(error = error)
            },
            onConnected = {
                Log.i(TAG, "DashScope connected, streaming audio...")
                // Start streaming audio from AudioRecorder
                viewModelScope.launch(Dispatchers.IO) {
                    // Wait for DashScope task to start
                        while (_state.value.isRecording && dashScopeStream != null && !dashScopeStream!!.isActive()) {
                            delay(50)
                        }
                        Log.i(TAG, "DashScope task started, sending audio...")
                        while (_state.value.isRecording && dashScopeStream?.isActive() == true) {
                        val pcmData = audioRecorder.flush()
                        if (pcmData != null && pcmData.isNotEmpty()) {
                            dashScopeStream?.sendAudio(pcmData)
                        }
                        delay(100)  // Send every 100ms
                    }
                }
            }
        )
        dashScopeStream?.start()
    }

    private fun startLocalVllmStreaming() {
        val settings = _state.value.settings
        val baseUrl = settings.asrBaseUrl.trimEnd('/')
        Log.i(TAG, "Starting local vLLM streaming ASR @ $baseUrl (model=${settings.asrModel})")

        // forceLanguage: "auto"/"en"/"zh" -> String?("English"/"Chinese"/null)
        val langArg: String? = when (settings.asrLanguage) {
            "en" -> "English"
            "zh" -> "Chinese"
            else -> null  // auto: 让 vLLM 自动检测, 支持马来+英语+中文混说
        }
        Log.i(TAG, "ASR language arg: ${langArg ?: "auto"}")
        localVllmStream = LocalVllmAsrStream(
            baseUrl = baseUrl,
            model = settings.asrModel,
            forceLanguage = langArg,
            inputAudioFormat = true,   // 本地后端是 llama-server，只认 input_audio（audio_url 会 400）
            onResult = { text, isFinal ->
                if (text.isBlank()) return@LocalVllmAsrStream
                Log.i(TAG, "vLLM result: $text (final=$isFinal)")
                if (isFinal) {
                    viewModelScope.launch(Dispatchers.Default) {
                        val chinese = try {
                            api.translate(
                                baseUrl = settings.translateBaseUrl,
                                apiKey = settings.translateApiKey,
                                model = settings.translateModel,
                                text = text
                            )
                        } catch (e: Exception) { "[翻译失败: ${e.message}]" }
                        addOrMergeEntry(text, chinese)
                        maybeCloudOverride()  // 双翻译：每 3 条云端覆盖
                    }
                }
            },
            onError = { err ->
                Log.e(TAG, "vLLM error: $err")
                _state.value = _state.value.copy(error = err)
            },
            onDisconnected = {
                Log.i(TAG, "vLLM disconnected (sentence batch done)")
            }
        )
        localVllmStream?.start()

        // 分段发送循环（2026-09-24 改）：等到「句末」或「缓冲满 8 秒」才发送整段，
        // 避免把半句切碎送 ASR 造成内容丢失（实测覆盖率 53% -> 目标 90%+）
        viewModelScope.launch(Dispatchers.IO) {
            while (_state.value.isRecording && localVllmStream?.isActive() == true) {
                delay(300)
                if (!_state.value.isRecording) break
                if (localVllmStream?.shouldFlush() != true) continue
                val text = try {
                    localVllmStream?.transcribePending()
                } catch (e: Exception) {
                    Log.e(TAG, "transcribePending failed: ${e.message}")
                    null
                }
                if (!text.isNullOrBlank()) {
                    _state.value = _state.value.copy(status = "已识别: ${text.take(40)}")
                } else {
                    _state.value = _state.value.copy(status = "正在听...")
                }
                // 更新 VU 表
                _state.value = _state.value.copy(audioLevel = audioRecorder.audioLevel)
            }
        }

        // 把 AudioRecorder 的 PCM 流喂给 LocalVllmAsrStream
        // 【2026-10-20 修复 2】模型加载期间（10-30s）麦克风已经在录了，但
        // ASR 还没就绪 → 开头说的话全丢。现在先缓存，就绪后补喂。
        // 注：listener 是在 startRecording() 里就挂上的（见 local_vllm 分支），
        //     这里只负责把缓存补喂进去。
        val buffered = synchronized(pendingPcm) {
            val b = pendingPcm.toByteArray()
            pendingPcm.reset()
            b
        }
        if (buffered.isNotEmpty()) {
            Log.i(TAG, "Feeding ${buffered.size} buffered PCM bytes to ASR (missed during model load)")
            // 分块喂（每次 0.5s），避免一次性塞太多导致段缓冲超限
            var off = 0
            while (off < buffered.size) {
                val n = minOf(16000, buffered.size - off)
                localVllmStream?.feedAudio(buffered.copyOfRange(off, off + n))
                off += n
            }
        }
        Log.i(TAG, "Local vLLM streaming started")
    }

    private fun stopRecording(releaseProj: Boolean = true) {
        dashScopeStream?.stop()
        dashScopeStream = null
        localVllmStream?.stop()
        localVllmStream = null
        // 会话结束：停掉前台服务（不再保活）
        runCatching { com.classroom.translator.SessionForegroundService.stop(getApplication()) }
        // 通知本地服务管理器：会话结束（无前台界面则自动释放，省内存/降温）
        runCatching { LocalLlmServerManager.get(getApplication()).onSessionEnd() }
        audioRecorder.setPcmListener(null)
        val audioPath = audioRecorder.getAudioFilePath()
        audioRecorder.stop()

        // 测试模式：停播放器 + 停内录
        testModeActive = false
        testPlaybackStartMs = 0L
        playbackRecorder.setPcmListener(null)
        playbackRecorder.stop()
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null

        // 释放 MediaProjection + 停掉内录前台服务
        // （之前全项目没有任何地方释放它——授权一次后前台服务永远活着，
        //   系统持续捕获音频 = 翻译停了手机还在耗电发热，2026-09-22 发现）
        if (releaseProj) releaseProjection()
        recStartMs = 0L

        _state.value = _state.value.copy(
            isRecording = false,
            audioLevel = 0f,
            status = "已停止"
        )

        // 【2026-10-20 修复 1】停止录音就落盘。
        // 原先 stopRecording() 只停设备不存档，存档完全依赖 finishSession()；
        // 而 finishSession() 在 entries 为空时直接 return（不写任何 session 文件），
        // 且「完成」按钮只在 count > 0 时才显示 —— 于是"点了开始但没识别到内容"
        // 这一路，录音文件在磁盘上却没有任何 session 指向它，历史里根本看不到，等于白录。
        // 现在：停止即归档，零识别也保留录音（可在详情页回放）。
        archiveCurrentSession(audioPath)
    }

    /**
     * 归档当前会话（写 session JSON）。
     * @param audioPath 录音文件路径（可空 —— 没有任何音频时）
     * @return 是否成功写入
     */
    private fun archiveCurrentSession(audioPath: String?): Boolean {
        val entries = _state.value.entries
        // 没有任何内容且没有录音文件 → 无可归档
        if (entries.isEmpty() && audioPath.isNullOrBlank()) return false
        // 【2026-10-20】同一段录音只归档一次。
        // 停止录音会归档 → 用户随后点「完成」又会走一次归档，若不守卫，
        // 第二次会用"空条目"覆盖第一次写好的 session（历史里就只剩一条空记录）。
        if (sessionPersisted) {
            Log.i(TAG, "archiveCurrentSession: already persisted (id=$currentSessionId), skip")
            return true
        }
        if (currentSessionId == 0L) currentSessionId = System.currentTimeMillis()

        val endTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val now = if (sessionStartTime.isBlank()) endTime else sessionStartTime
        val dataEntries = entries.mapIndexed { i, e ->
            val s = e.startSec
            val end = when {
                e.endSec >= 0 -> e.endSec
                else -> {
                    val nxt = entries.drop(i + 1).firstOrNull { it.startSec >= 0 }?.startSec
                    when {
                        nxt != null && nxt > s && s >= 0 -> nxt
                        s >= 0 -> s + 15
                        else -> -1
                    }
                }
            }
            TranslationEntryData(timestamp = e.timestamp, english = e.english, chinese = e.chinese, startSec = s, endSec = end)
        }
        // 零识别时给一句说明性的摘要（历史列表会显示它）
        val summary = if (entries.isNotEmpty()) {
            entries.first().english.let { if (it.length > 50) it.take(50) + "..." else it }
        } else {
            "录音已保存（未识别到语音内容）"
        }
        val session = TranslationSession(
            id = currentSessionId,
            date = date,
            startTime = now,
            endTime = endTime,
            entries = dataEntries,
            summary = summary
        )
        sessionManager.saveSession(session.copy(audioPath = audioPath ?: ""))
        sessionPersisted = true
        Log.i(TAG, "Session archived: id=$currentSessionId entries=${dataEntries.size} audio=$audioPath")
        return true
    }

    fun startNewSession() {
        // 有未保存条目时先存档再开新会话——之前直接清空, 测试 96 条凭空消失 (2026-09-22)
        if (_state.value.entries.isNotEmpty()) {
            Log.i(TAG, "startNewSession: save ${_state.value.entries.size} pending entries first")
            finishSession()
            return
        }
        sessionStartTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        _state.value = _state.value.copy(entries = emptyList(), status = "就绪", error = null)
    }
    fun deleteEntry(entryId: Long) {
        _state.value = _state.value.copy(entries = _state.value.entries.filter { it.id != entryId })
    }

    fun clearEntries() {
        _state.value = _state.value.copy(entries = emptyList())
    }

    fun finishSession() {
        val currentEntries = _state.value.entries
        val savedAudioPath = audioRecorder.getAudioFilePath()
        // 【2026-10-20 修复 1】零识别也要保存。
        // 原先这里 entries.isEmpty() 直接 return —— 不写 session 文件，
        // 录音文件在磁盘上却没有任何 session 指向它，历史里看不到 = 白录。
        // 现在：先停录音（stopRecording 内部已归档），再走整课重译。
        if (currentEntries.isEmpty()) {
            if (_state.value.isRecording) {
                stopRecording()          // 内部会归档（零识别也存）
            } else {
                archiveCurrentSession(savedAudioPath)
            }
            _state.value = _state.value.copy(status = "已保存", entries = emptyList())
            sessionStartTime = ""
            currentSessionId = 0L
            sessionPersisted = false
            return
        }
        if (_state.value.isRecording) stopRecording()

        val endTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val now = if (sessionStartTime.isBlank()) endTime else sessionStartTime
        val entries = currentEntries.mapIndexed { i, e ->
            val s = e.startSec
            val end = when {
                e.endSec >= 0 -> e.endSec
                else -> {
                    val nxt = currentEntries.drop(i + 1).firstOrNull { it.startSec >= 0 }?.startSec
                    when {
                        nxt != null && nxt > s && s >= 0 -> nxt
                        s >= 0 -> s + 15
                        else -> -1
                    }
                }
            }
            TranslationEntryData(timestamp = e.timestamp, english = e.english, chinese = e.chinese, startSec = s, endSec = end)
        }
        val summary = currentEntries.first().english.let { if (it.length > 50) it.take(50) + "..." else it }
        val session = TranslationSession(id = currentSessionId.takeIf { it > 0 } ?: System.currentTimeMillis(), date = date, startTime = now, endTime = endTime, entries = entries, summary = summary)

        // Save immediately with audio path
        sessionManager.saveSession(session.copy(audioPath = savedAudioPath ?: ""))
        sessionPersisted = true
        Log.i(TAG, "Session saved with audio: $savedAudioPath, ${entries.size} entries")

        // 课后整课重译（"润色"核心）：分块云端重译 → 替换后重存并标记 polished。
        // (2026-09-26: 从"一次性全量"改为分块, 避免长会话单请求超限; 云端默认切火山 ds4.1)
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Default) {
            try {
                _state.value = _state.value.copy(status = "全文修正中...")
                val r = cloudReTranslate(entries) { done, total ->
                    _state.value = _state.value.copy(status = "全文修正中 $done/$total")
                }
                if (r != null && r.second > 0) {
                    val (newEntries, replaced) = r
                    sessionManager.saveSession(session.copy(entries = newEntries, audioPath = savedAudioPath ?: "", polished = true))
                    Log.i(TAG, "Full-text correction applied: $replaced/${entries.size} entries")
                }
            } catch (e: Exception) { Log.e(TAG, "Full-text correction failed: ${e.message}") }
            _state.value = _state.value.copy(entries = emptyList(), status = "已保存")
            sessionStartTime = ""
            currentSessionId = 0L
            sessionPersisted = false
        }
    }

    fun loadSession(sessionId: Long): TranslationSession? {
        return sessionManager.loadSessions().find { it.id == sessionId }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    fun updateSettings(block: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            settingsRepo.update(block)
        }
    }

    /**
     * 整课录音转录：上传到本地 VibeVoice HTTP 服务，逐段翻译，生成双语字幕。
     * @param serverUrl 例 http://192.168.1.249:8001
     */
    fun transcribeWholeSession(
        audioBytes: ByteArray,
        fileName: String,
        serverUrl: String,
        onProgress: (String) -> Unit = {},
        onComplete: () -> Unit = {}
    ) {
        val settings = _state.value.settings
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Default) {
            _state.value = _state.value.copy(isRecording = false)
            _state.value = _state.value.copy(isProcessing = true, status = "上传转写中: $fileName")
            try {
                // 1) 转 WAV（若非 wav）
                var wavData = audioBytes
                if (AudioConverter.needsConversion(fileName)) {
                    onProgress("转换音频格式...")
                    _state.value = _state.value.copy(status = "转换音频格式...")
                    val converted = AudioConverter.convertToWav(getApplication(), audioBytes, fileName)
                    if (converted == null) {
                        _state.value = _state.value.copy(isProcessing = false, status = "就绪",
                            error = "不支持的音频格式: $fileName")
                        return@launch
                    }
                    wavData = converted
                }
                onProgress("上传到转录服务...")
                _state.value = _state.value.copy(status = "上传到转录服务...")

                // 2) Multipart 上传到本地服务
                val mediaType = "application/octet-stream".toMediaType()
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", fileName, wavData.toRequestBody(mediaType))
                    .build()
                val baseUrl = serverUrl.trimEnd('/')
                val req = Request.Builder()
                    .url("$baseUrl/transcribe")
                    .post(body)
                    .build()
                val resp = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(600, TimeUnit.SECONDS)
                    .writeTimeout(120, TimeUnit.SECONDS)
                    .build()
                    .newCall(req).execute()
                val respBody = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    _state.value = _state.value.copy(isProcessing = false, status = "就绪",
                        error = "转录服务错误 ${resp.code}: ${respBody.take(200)}")
                    return@launch
                }
                val json = JSONObject(respBody)
                val segments = json.getJSONArray("segments")
                val count = segments.length()
                if (count == 0) {
                    _state.value = _state.value.copy(isProcessing = false, status = "就绪",
                        error = "未识别到语音内容")
                    return@launch
                }
                _state.value = _state.value.copy(status = "识别到 $count 段，开始翻译...")

                // 3) 逐段翻译，加入条目（带时间戳）
                val newEntries = mutableListOf<TranslationEntry>()
                for (i in 0 until count) {
                    val seg = segments.getJSONObject(i)
                    val raw = seg.optString("Text", seg.optString("text", ""))
                    if (raw.isBlank()) continue
                    val start = seg.optDouble("Start", 0.0)
                    val end = seg.optDouble("End", 0.0)
                    val ts = "${formatTime(start)} - ${formatTime(end)}"
                    onProgress("翻译第 ${i + 1}/$count 段")
                    _state.value = _state.value.copy(status = "翻译第 ${i + 1}/$count 段...")
                    val chinese = try {
                        api.translate(baseUrl = settings.translateBaseUrl, apiKey = settings.translateApiKey,
                            model = settings.translateModel, text = raw)
                    } catch (e: Exception) { "[翻译失败: ${e.message}]" }
                    newEntries.add(TranslationEntry(english = raw, chinese = chinese, timestamp = ts, startSec = start.toInt(), endSec = end.toInt()))
                }
                _state.value = _state.value.copy(
                    entries = _state.value.entries + newEntries,
                    isProcessing = false, status = "完成（$count 段）"
                )
                newEntries.forEach { autoSave(it) }
                onProgress("完成")
                onComplete()
            } catch (e: Exception) {
                Log.e(TAG, "Whole session failed", e)
                _state.value = _state.value.copy(isProcessing = false, status = "就绪",
                    error = "整课转录失败: ${e.message}")
            }
        }
    }

    private fun formatTime(sec: Double): String {
        val total = sec.toInt()
        val m = total / 60
        val s = total % 60
        return "%02d:%02d".format(m, s)
    }

    /**
     * 云端整课重译（"润色"核心）—— 分块执行（20 条/块），逐块严格 1:1 替换。
     * @return (替换后的条目, 成功替换条数)；条目为空返回 null；某块失败该块保留原文。
     */
    private suspend fun cloudReTranslate(
        entries: List<TranslationEntryData>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Pair<List<TranslationEntryData>, Int>? {
        if (entries.isEmpty()) return null
        val settings = _state.value.settings
        val out = ArrayList<TranslationEntryData>(entries.size)
        var replaced = 0
        var done = 0
        for (chunk in entries.indices.chunked(20)) {
            val segs = chunk.map { entries[it].english }
            val pairs = try {
                api.cloudBatchTranslate(
                    baseUrl = settings.cloudBaseUrl,
                    apiKey = settings.cloudApiKey,
                    model = settings.cloudModel,
                    segments = segs
                )
            } catch (e: Exception) {
                Log.e(TAG, "cloudReTranslate chunk failed: ${e.message}")
                null
            }
            chunk.forEachIndexed { j, idx ->
                val e = entries[idx]
                val pr = pairs?.getOrNull(j)
                if (pr != null && pr.first != "[解析失败]" && pr.first != "[无翻译]" && pr.second != "[解析失败]" && pr.second != "[无翻译]") {
                    val newEn = pr.first.trim()
                    val newZh = pr.second.trim()
                    // 润色前原文留档（详情页「（原：…）」对比）：仅在本轮确有变化且尚未留档时记录，
                    // 多次润色保留最早的"润色前"版本。
                    out.add(
                        e.copy(
                            english = newEn,
                            chinese = newZh,
                            origEnglish = when {
                                newEn == e.english.trim() -> e.origEnglish
                                e.origEnglish.isNotBlank() -> e.origEnglish
                                else -> e.english
                            },
                            origChinese = when {
                                newZh == e.chinese.trim() -> e.origChinese
                                e.origChinese.isNotBlank() -> e.origChinese
                                else -> e.chinese
                            }
                        )
                    )
                    replaced++
                } else out.add(e)
            }
            done += chunk.size
            onProgress(done, entries.size)
        }
        return out to replaced
    }

    /**
     * 详情页「云端润色」：对已保存会话做整课重译，成功后写回存储。
     * 对应设计稿"课后整课润色：会话结束后按上下文重译,术语更一致"（手动触发版；
     * finishSession 结束时会自动做一次）。
     */
    suspend fun polishSessionCloud(
        session: TranslationSession,
        onProgress: (String) -> Unit = {}
    ): Result<TranslationSession> {
        val settings = _state.value.settings
        onProgress("云端整课重译中…（${settings.cloudModel}）")
        return try {
            val r = cloudReTranslate(session.entries) { done, total -> onProgress("云端整课重译中 $done/$total") }
            if (r == null || r.second == 0) {
                Result.failure(Exception("云端未返回有效译文"))
            } else {
                val updated = session.copy(entries = r.first, polished = true)
                sessionManager.saveSession(updated)
                Log.i(TAG, "polishSessionCloud: ${r.second}/${session.entries.size} replaced")
                Result.success(updated)
            }
        } catch (e: Exception) {
            Log.e(TAG, "polishSessionCloud failed", e)
            Result.failure(e)
        }
    }

    /**
     * 详情页「课堂总结」：整课转写 → 云端大模型 → 结构化总结
     * （概要/重点+时间区间/知识点讲解/作业/复习自测/关键词），成功后写回 session.classSummary。
     *
     * 2026-09-29 加固（此前偶发"总结解析失败（模型输出非 JSON）"）：
     *  1) 解析失败自动重试（最多 MAX_SUMMARY_ATTEMPTS 次），重试附加"只输出 JSON"强约束；
     *  2) max_tokens 3500→8000（实测长课 completion 已到 3306/3500，思考+JSON 容易被截断）；
     *  3) 失败时把模型原始输出 head/tail 打进 logcat，根因可查。
     */
    suspend fun generateSummary(
        session: TranslationSession,
        onProgress: (String) -> Unit = {}
    ): Result<ClassSummary> {
        val settings = _state.value.settings
        val entries = session.entries
        if (entries.isEmpty()) return Result.failure(Exception("没有转写内容"))
        onProgress("正在生成课堂总结…（${settings.cloudModel}，思考深度最高档）")
        return try {
            val sysPrompt = ClassSummary.buildSystemPrompt()
            val usrPrompt = ClassSummary.buildUserPrompt(entries)
            var summary: ClassSummary? = null
            var lastFinish = ""
            var attempt = 0
            while (summary == null && attempt < MAX_SUMMARY_ATTEMPTS) {
                attempt++
                // 思考深度开到最高（用户要求 2026-09-26）：火山方舟 reasoning_effort=max
                // （deepseek-v4 家族官方档位 none/minimal/low/high/max，最高=max；实测思考量最大）
                val sys = if (attempt == 1) sysPrompt else sysPrompt + "\n" + ClassSummary.RETRY_SYSTEM_SUFFIX
                val (raw, finish) = requestSummaryRaw(settings, sys, usrPrompt, SUMMARY_MAX_TOKENS)
                lastFinish = finish
                summary = ClassSummary.parseModelOutput(raw, entries, session.startTime, settings.cloudModel, session.audioPath)
                if (summary == null) {
                    Log.e(
                        TAG,
                        "generateSummary 解析失败 attempt=$attempt/$MAX_SUMMARY_ATTEMPTS finish=$finish " +
                            "len=${raw.length} HEAD[${raw.take(360)}] TAIL[${raw.takeLast(240)}]"
                    )
                    if (attempt < MAX_SUMMARY_ATTEMPTS) {
                        onProgress("模型输出格式异常，正在重试（第 ${attempt + 1}/$MAX_SUMMARY_ATTEMPTS 次）…")
                    }
                } else {
                    Log.i(TAG, "generateSummary OK attempt=$attempt finish=$finish len=${raw.length}")
                }
            }
            if (summary == null) {
                Result.failure(Exception("总结解析失败（模型输出非 JSON，已重试 $MAX_SUMMARY_ATTEMPTS 次，finish=$lastFinish）"))
            } else {
                sessionManager.saveSession(session.copy(classSummary = summary.toJson()))
                Result.success(summary)
            }
        } catch (e: Exception) {
            Log.e(TAG, "generateSummary failed", e)
            Result.failure(e)
        }
    }

    /**
     * 课堂总结一次性请求（含 reasoning_effort=max 的端点兼容回退）。
     * @return (内容文本, finish_reason)；finish_reason=length 表示被 max_tokens 截断。
     */
    private suspend fun requestSummaryRaw(
        settings: AppSettings,
        sysPrompt: String,
        usrPrompt: String,
        maxTokens: Int
    ): Pair<String, String> {
        return try {
            val r = api.cloudChatFull(
                baseUrl = settings.cloudBaseUrl,
                apiKey = settings.cloudApiKey,
                model = settings.cloudModel,
                system = sysPrompt,
                user = usrPrompt,
                maxTokens = maxTokens,
                temperature = 0.3,
                reasoningEffort = "max"
            )
            r.text to r.finishReason
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("400") || msg.contains("InvalidParameter") || msg.contains("reasoning", ignoreCase = true)) {
                Log.w(TAG, "reasoning_effort=max 被端点拒绝（${msg.take(140)}），退回默认档重试")
                val r = api.cloudChatFull(
                    baseUrl = settings.cloudBaseUrl,
                    apiKey = settings.cloudApiKey,
                    model = settings.cloudModel,
                    system = sysPrompt,
                    user = usrPrompt,
                    maxTokens = maxTokens,
                    temperature = 0.3
                )
                r.text to r.finishReason
            } else throw e
        }
    }


    /**
     * 双翻译云端覆盖：每 3 条触发一次云端 M3 批量翻译，
     * 云端译文后到时直接【替换】本地快翻结果（isCorrected=true 标记）。
     * 所有流式模式（local_vllm / 测试模式 / DashScope）共用。
     * （之前只接在 MIMO 备用模式里，流式模式从未触发过云端覆盖——2026-09-22 全面检查发现）
     */
    private fun maybeCloudOverride() {
        val currentEntries = _state.value.entries
        if (currentEntries.size < 3 || currentEntries.size % 3 != 0) return
        val settings = _state.value.settings
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Default) {
            try {
                Log.i(TAG, "Cloud override START: ${currentEntries.size} entries total")
                _state.value = _state.value.copy(status = "云端复盘中...")
                val recent = currentEntries.takeLast(5)
                val segments = recent.map { it.english }
                // Cloud path: M3 via Anthropic protocol (REPLACES local, not "corrects")
                val corrections = api.cloudBatchTranslate(
                    baseUrl = settings.cloudBaseUrl,
                    apiKey = settings.cloudApiKey,
                    model = settings.cloudModel,
                    segments = segments
                )
                Log.i(TAG, "Cloud override result: ${corrections.size} items")
                var corrected = false
                val updated = _state.value.entries.toMutableList()
                for (i in corrections.indices) {
                    val idx = updated.size - recent.size + i
                    if (idx >= 0 && idx < updated.size) {
                        val (newEn, newZh) = corrections[i]
                        if (newEn != "[解析失败]" && newEn != "[无翻译]" && newZh != "[解析失败]" && newZh != "[无翻译]" && (newEn != updated[idx].english || newZh != updated[idx].chinese)) {
                            updated[idx] = updated[idx].copy(english = newEn, chinese = newZh, isCorrected = true)
                            corrected = true
                            Log.i(TAG, "Override #$idx: ${updated[idx].english.take(30)} -> ${newEn.take(30)}")
                        }
                    }
                }
                if (corrected) {
                    _state.value = _state.value.copy(entries = updated, status = "云端已覆盖")
                    delay(2000)
                }
                if (_state.value.isRecording) _state.value = _state.value.copy(status = "正在听...")
            } catch (e: Exception) {
                Log.e(TAG, "Cloud override FAILED: ${e.message}", e)
                if (_state.value.isRecording) _state.value = _state.value.copy(status = "正在听...")
            }
        }
    }

    private suspend fun translateAndAdd(text: String, settings: AppSettings, startTime: Long) {
        try {
            val chinese = api.translate(baseUrl = settings.translateBaseUrl, apiKey = settings.translateApiKey,
                model = settings.translateModel, text = text)
            val entry = TranslationEntry(english = text, chinese = chinese, latencyMs = System.currentTimeMillis() - startTime, startSec = offsetNow())
            _state.value = _state.value.copy(entries = _state.value.entries + entry)
            autoSave(entry)
        } catch (e: Exception) { Log.e(TAG, "Merge translate failed", e) }
    }

    /**
     * 加条目（带去重/续写合并）：
     * - 与上一条完全相同 → 丢弃（模型重复输出）
     * - 上一条是新条前缀（同句续写）→ 原地升级，避免重复条目
     * 2026-09-23: 24.8min 实测出现 5 条重复条目（模型整段重发导致）。
     */
    // [debug-only] UI 验证用：伪装"录音中"展示态（不启动真实录音）
    internal fun seedRecordingUi() {
        _state.value = _state.value.copy(
            isRecording = true,
            status = "录音中 · 已连接服务器",
        )
    }

    /** 当前时刻相对本次录音起点的偏移秒数（-1 = 非录音态，如种子数据/整课导入） */
    private fun offsetNow(): Int =
        if (recStartMs > 0) ((System.currentTimeMillis() - recStartMs) / 1000).toInt() else -1

    internal fun addOrMergeEntry(text: String, chinese: String, timestamp: String? = null) {
        val entries = _state.value.entries
        val last = entries.lastOrNull()
        val t = text.trim()
        when {
            last != null && last.english.trim() == t -> {
                Log.i(TAG, "drop duplicate entry: ${t.take(40)}")
            }
            last != null && last.english.trim().length >= 12 && t.startsWith(last.english.trim()) -> {
                _state.value = _state.value.copy(
                    entries = entries.dropLast(1) + last.copy(english = text, chinese = chinese)
                )
                Log.i(TAG, "merge continuation: ${t.take(40)}")
            }
            else -> {
                val entry = if (timestamp != null) TranslationEntry(english = text, chinese = chinese, timestamp = timestamp, startSec = offsetNow())
                else TranslationEntry(english = text, chinese = chinese, startSec = offsetNow())
                _state.value = _state.value.copy(entries = entries + entry)
                autoSave(entry)
            }
        }
    }

    private fun autoSave(entry: TranslationEntry) {
        try {
            // 用内部存储（100% 可写，不依赖外部存储权限）
            val dir = File(getApplication<Application>().filesDir, "translations")
            dir.mkdirs()
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val file = File(dir, "translation_${date}.txt")
            file.appendText("[${entry.timestamp}] ${entry.english}\n  → ${entry.chinese}\n\n")
        } catch (e: Exception) { Log.e(TAG, "Auto-save failed", e) }
    }

    /** 释放 MediaProjection + 停止内录前台服务（幂等，可重复调用） */
    private fun releaseProjection() {
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        com.classroom.translator.ProjectionForegroundService.stop(getApplication<Application>())
    }

    override fun onCleared() {
        super.onCleared()
        // 全量释放：之前只停了麦克风，内录/播放器/投影/ASR 流都留在原地继续耗电
        try { dashScopeStream?.stop() } catch (_: Exception) {}
        dashScopeStream = null
        try { localVllmStream?.stop() } catch (_: Exception) {}
        localVllmStream = null
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
        try { playbackRecorder.stop() } catch (_: Exception) {}
        audioRecorder.stop()
        releaseProjection()
    }
}
