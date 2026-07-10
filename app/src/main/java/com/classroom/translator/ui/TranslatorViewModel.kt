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
import com.classroom.translator.data.SessionManager
import com.classroom.translator.data.TranslationSession
import com.classroom.translator.data.TranslationEntryData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var lastTranslatedText = ""
    private val sessionManager = SessionManager(app)

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

    private fun startRecording() {
        if (!audioRecorder.hasPermission) {
            _state.value = _state.value.copy(error = "需要麦克风权限")
            return
        }

        audioRecorder.start()
        _state.value = _state.value.copy(isRecording = true, status = "正在听...", error = null)

        // DashScope streaming mode
        if (_state.value.settings.asrMode == "dashscope") {
            startDashScopeStreaming()
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

            // 2. AI correction review (every 3 entries)
            val currentEntries = _state.value.entries
            if (currentEntries.size >= 3 && currentEntries.size % 3 == 0) {
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.Default) {
                    try {
                        Log.i(TAG, "AI review START: ${currentEntries.size} entries total")
                        _state.value = _state.value.copy(status = "AI复盘中...")
                        Log.i(TAG, "AI review: checking last 5 of ${currentEntries.size} entries")
                        val recent = currentEntries.takeLast(5)
                        val segments = recent.map { it.english }
                        val corrections = api.batchTranslateAndMerge(
                            baseUrl=settings.translateBaseUrl, apiKey=settings.translateApiKey,
                            model=settings.translateModel, segments=segments
                        )
                        Log.i(TAG, "AI review result: ${corrections.size} items")
                        var corrected = false
                        val updated = currentEntries.toMutableList()
                        for (i in corrections.indices) {
                            val idx = updated.size - recent.size + i
                            if (idx >= 0 && idx < updated.size) {
                                val (newEn, newZh) = corrections[i]
                                if (newEn != "[解析失败]" && newEn != "[无翻译]" && (newEn != updated[idx].english || newZh != updated[idx].chinese)) {
                                    updated[idx] = updated[idx].copy(english = newEn, chinese = newZh, isCorrected = true)
                                    corrected = true
                                    Log.i(TAG, "Corrected #$idx: ${updated[idx].english.take(30)} -> ${newEn.take(30)}")
                                }
                            }
                        }
                        if (corrected) {
                            _state.value = _state.value.copy(entries = updated, status = "已修正")
                            delay(2000)
                        }
                        _state.value = _state.value.copy(status = "正在听...")
                    } catch (e: Exception) {
                        Log.e(TAG, "AI review FAILED: ${e.message}", e)
                        _state.value = _state.value.copy(status = "正在听...")
                    }
                }
            }

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
                if (text == lastTranslatedText) return@DashScopeAsrStream
                lastTranslatedText = text
                Log.i(TAG, "DashScope result: $text (final=$isFinal)")
                viewModelScope.launch(Dispatchers.Default) {
                    val chinese = try {
                        api.translate(baseUrl=settings.translateBaseUrl, apiKey=settings.translateApiKey, model=settings.translateModel, text=text)
                    } catch (e: Exception) { "[翻译失败: ${e.message}]" }
                    val entry = TranslationEntry(english = text, chinese = chinese)
                    _state.value = _state.value.copy(entries = _state.value.entries + entry)
                    autoSave(entry)
                    if (isFinal) finishSession()
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

    private fun stopRecording() {
        dashScopeStream?.stop()
        dashScopeStream = null
        val audioPath = audioRecorder.getAudioFilePath()
        audioRecorder.stop()
        _state.value = _state.value.copy(
            isRecording = false,
            audioLevel = 0f,
            status = "已停止"
        )
    }

    fun saveCurrentSession() {
        // Public method to save current session (called by BackHandler)
        viewModelScope.launch {
            if (_state.value.isRecording) {
                stopRecording()
            }
            finishSession()
        }
    }

    fun startNewSession() {
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
                val corrections = api.batchTranslateAndMerge(
                    baseUrl = _state.value.settings.translateBaseUrl,
                    apiKey = _state.value.settings.translateApiKey,
                    model = _state.value.settings.translateModel,
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

    fun importAudioFile(audioBytes: ByteArray, fileName: String) {
        val settings = _state.value.settings
        if (settings.asrApiKey.isBlank()) {
            _state.value = _state.value.copy(error = "请先设置API密钥")
            return
        }
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Default) {
            _state.value = _state.value.copy(isProcessing = true, status = "正在识别: $fileName")
            try {
                val needsConvert = AudioConverter.needsConversion(fileName)
                val (audioData, format) = if (needsConvert) {
                    Log.i(TAG, "Converting $fileName to WAV...")
                    val converted = AudioConverter.convertToWav(getApplication(), audioBytes, fileName)
                    if (converted == null) {
                        _state.value = _state.value.copy(isProcessing = false, status = "就绪", error = "不支持的音频格式: $fileName")
                        return@launch
                    }
                    Pair(converted, "wav")
                } else if (fileName.endsWith(".mp3")) {
                    Pair(audioBytes, "mp3")
                } else {
                    Pair(audioBytes, "wav")
                }
                val audioBase64 = Base64.encodeToString(audioData, Base64.NO_WRAP)
                val english = api.transcribeAudio(
                    baseUrl = settings.asrBaseUrl, apiKey = settings.asrApiKey,
                    model = settings.asrModel, audioBase64 = audioBase64,
                    format = format, language = settings.asrLanguage
                )
                if (english.isBlank()) {
                    _state.value = _state.value.copy(isProcessing = false, status = "就绪", error = "文件无语音内容")
                    return@launch
                }
                _state.value = _state.value.copy(status = "正在翻译...")
                val chinese = try {
                    api.translate(baseUrl = settings.translateBaseUrl, apiKey = settings.translateApiKey,
                        model = settings.translateModel, text = english)
                } catch (e: Exception) { "[翻译失败: ${e.message}]" }
                val entry = TranslationEntry(english = english, chinese = chinese)
                _state.value = _state.value.copy(entries = _state.value.entries + entry, isProcessing = false, status = "就绪")
                autoSave(entry)
            } catch (e: Exception) {
                Log.e(TAG, "Import failed", e)
                _state.value = _state.value.copy(isProcessing = false, status = "就绪", error = "导入失败: ${e.message}")
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

    private fun autoSave(entry: TranslationEntry) {
        try {
            val dir = File(getApplication<Application>().getExternalFilesDir(null), "translations")
            dir.mkdirs()
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val file = File(dir, "translation_${date}.txt")
            file.appendText("[${entry.timestamp}] ${entry.english}\n  → ${entry.chinese}\n\n")
        } catch (e: Exception) { Log.e(TAG, "Auto-save failed", e) }
    }

    override fun onCleared() {
        super.onCleared()
        val audioPath = audioRecorder.getAudioFilePath()
        audioRecorder.stop()
    }
}
