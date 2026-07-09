package com.classroom.translator.audio

import android.util.Log
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * DashScope Fun-ASR Realtime WebSocket client.
 * Streams audio chunks and receives real-time transcription.
 */
class DashScopeAsrStream(
    private val apiKey: String,
    private val model: String = "fun-asr-realtime-2026-02-28",
    private val sampleRate: Int = 16000,
    private val onResult: (text: String, isFinal: Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {}
) {
    companion object {
        private const val TAG = "DashScopeASR"
        private const val WS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
    }

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var taskId: String = ""
    @Volatile private var isConnected = false
    @Volatile private var isStarted = false
    private var sendCount = 0

    fun start() {
        if (isConnected) return
        sendCount = 0

        val request = Request.Builder()
            .url(WS_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("X-DashScope-DataInspection", "enable")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                isConnected = true
                onConnected()
                sendRunTask()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}")
                isConnected = false
                onError("连接错误: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                isConnected = false
                isStarted = false
                onDisconnected()
            }
        })
    }

    private fun sendRunTask() {
        taskId = "task_${System.currentTimeMillis()}"
        val msg = JSONObject().apply {
            put("header", JSONObject().apply {
                put("action", "run-task")
                put("task_id", taskId)
                put("streaming", "duplex")
            })
            put("payload", JSONObject().apply {
                put("task_group", "audio")
                put("task", "asr")
                put("function", "recognition")
                put("model", model)
                put("parameters", JSONObject().apply {
                    put("sample_rate", sampleRate)
                    put("format", "pcm")
                    val hints = JSONArray().apply {
                        put("zh")
                        put("en")
                    }
                    put("language_hints", hints)
                })
                put("input", JSONObject())  // empty input for streaming
            })
        }
        Log.i(TAG, ">>> Sending run-task: $taskId, model=$model, format=pcm, rate=$sampleRate")
        val json = msg.toString()
        Log.d(TAG, ">>> run-task JSON: ${json.take(200)}")
        webSocket?.send(json)
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val header = json.optJSONObject("header")
            val event = header?.optString("event", "")

            when (event) {
                "task-started" -> {
                    isStarted = true
                    Log.i(TAG, "Task started: $taskId")
                }
                "result-generated" -> {
                    val payload = json.optJSONObject("payload")
                    val output = payload?.optJSONObject("output")
                    val sentenceObj = output?.optJSONObject("sentence")
                    val text = sentenceObj?.optString("text", "") ?: ""
                    val sentenceEnd = sentenceObj?.optBoolean("sentence_end", false) ?: false
                    if (text.isNotBlank()) {
                        Log.d(TAG, "Result: $text (final=$sentenceEnd)")
                        onResult(text, sentenceEnd)
                    }
                }
                "task-finished" -> {
                    Log.i(TAG, "Task finished")
                    isStarted = false
                    onDisconnected()
                }
                "task-failed" -> {
                    val message = header?.optString("error_message", "Unknown error")
                    Log.e(TAG, "Task failed: $message")
                    onError("识别失败: $message")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
        }
    }

    /**
     * Send PCM audio data (16-bit, mono, 16kHz).
     * Call this from the audio recording thread.
     */
    fun sendAudio(pcmData: ByteArray) {
        if (!isConnected || !isStarted) return
        
        // Calculate RMS to verify audio has content
        var sum = 0L
        for (i in pcmData.indices step 2) {
            if (i + 1 < pcmData.size) {
                val lo = pcmData[i].toInt() and 0xFF
                val hi = pcmData[i + 1].toInt()
                val sample = lo or (hi shl 8)
                sum += sample.toLong() * sample.toLong()
            }
        }
        val rms = kotlin.math.sqrt(sum.toDouble() / (pcmData.size / 2))
        val hasVoice = rms > 100  // threshold for non-silence
        
        // Send as raw binary WebSocket frame (official DashScope protocol)
        val bs = okio.ByteString.of(*pcmData)
        webSocket?.send(bs)
        sendCount++
        
        if (sendCount <= 10 || sendCount % 20 == 0) {
            Log.d(TAG, "Audio #$sendCount: ${pcmData.size}B, RMS=${rms.toInt()}, voice=$hasVoice")
        }
        if (sendCount == 1) {
            // Log first 16 bytes for debugging
            val hex = pcmData.take(16).joinToString(" ") { b -> String.format("%02x", b.toInt() and 0xff) }
            Log.d(TAG, "First 16 bytes: $hex")
        }
    }

    fun stop() {
        if (!isConnected) return
        // Send finish-task
        val msg = JSONObject().apply {
            put("header", JSONObject().apply {
                put("action", "finish-task")
                put("task_id", taskId)
                put("streaming", "duplex")
            })
            put("payload", JSONObject().apply {
                put("input", JSONObject())
            })
        }
        val json = msg.toString()
        Log.d(TAG, ">>> run-task JSON: ${json.take(200)}")
        webSocket?.send(json)
        // Close after a short delay
        Thread {
            Thread.sleep(1000)
            webSocket?.close(1000, "Done")
        }.start()
    }

    fun isActive(): Boolean = isConnected && isStarted
}
