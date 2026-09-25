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
    val isCorrected: Boolean = false
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

class TranslatorViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "TranslatorVM"
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

    private val _state = MutableStateFlow(TranslatorUiState())
    val state: StateFlow<TranslatorUiState> = _state.asStateFlow()

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

        audioRecorder.start()
        // 【防冻结】会话期间跑前台服务：否则 App 退到后台会被 Android App Freezer 冻结，
        // 连带 ProcessBuilder 拉起的本地推理服务一起冻住（实测：切微信后服务不再响应）
        runCatching { com.classroom.translator.SessionForegroundService.start(getApplication()) }
        _state.value = _state.value.copy(isRecording = true, status = "正在听...", error = null)

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
            val entry = TranslationEntry(english = english, chinese = chinese, latencyMs = latency)
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
        audioRecorder.setPcmListener { pcm ->
            localVllmStream?.feedAudio(pcm)
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

        _state.value = _state.value.copy(
            isRecording = false,
            audioLevel = 0f,
            status = "已停止"
        )
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
        if (currentEntries.isEmpty()) {
            stopRecording()
            return
        }
        if (_state.value.isRecording) stopRecording()

        val endTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val now = if (sessionStartTime.isBlank()) endTime else sessionStartTime
        val entries = currentEntries.map { TranslationEntryData(it.timestamp, it.english, it.chinese) }
        val summary = currentEntries.first().english.let { if (it.length > 50) it.take(50) + "..." else it }
        val session = TranslationSession(id = System.currentTimeMillis(), date = date, startTime = now, endTime = endTime, entries = entries, summary = summary)

        // Save immediately with audio path
        sessionManager.saveSession(session.copy(audioPath = savedAudioPath ?: ""))
        Log.i(TAG, "Session saved with audio: $savedAudioPath, ${entries.size} entries")

        // Background full-text correction, then re-save
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Default) {
            try {
                _state.value = _state.value.copy(status = "全文修正中...")
                val allSegments = currentEntries.map { it.english }
                // Cloud path: M3 via Anthropic protocol
                val corrections = api.batchTranslateAndMergeCloud(
                cloudBaseUrl = _state.value.settings.cloudBaseUrl,
                cloudApiKey = _state.value.settings.cloudApiKey,
                cloudModel = _state.value.settings.cloudModel,
                segments = allSegments
            )
                if (corrections.size == currentEntries.size) {
                    val corrected = corrections.mapIndexed { i, (en, zh) ->
                        TranslationEntryData(entries[i].timestamp, en, zh)
                    }
                    sessionManager.saveSession(session.copy(entries = corrected, audioPath = savedAudioPath ?: ""))
                    Log.i(TAG, "Full-text correction applied: ${corrected.size} entries")
                }
            } catch (e: Exception) { Log.e(TAG, "Full-text correction failed: ${e.message}") }
            _state.value = _state.value.copy(entries = emptyList(), status = "已保存")
            sessionStartTime = ""
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
                    newEntries.add(TranslationEntry(english = raw, chinese = chinese, timestamp = ts))
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
                val corrections = api.batchTranslateAndMergeCloud(
                    cloudBaseUrl = settings.cloudBaseUrl,
                    cloudApiKey = settings.cloudApiKey,
                    cloudModel = settings.cloudModel,
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
            val entry = TranslationEntry(english = text, chinese = chinese, latencyMs = System.currentTimeMillis() - startTime)
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
                val entry = if (timestamp != null) TranslationEntry(english = text, chinese = chinese, timestamp = timestamp)
                else TranslationEntry(english = text, chinese = chinese)
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
