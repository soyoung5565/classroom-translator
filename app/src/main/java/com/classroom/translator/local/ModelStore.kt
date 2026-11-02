package com.classroom.translator.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * 模型仓库 —— 解决「清除应用数据把模型一起删掉」这个用户级事故。
 *
 * ## 事故根因
 * Android 的「清除应用数据」会同时删除两处：
 *   - /data/data/<pkg>/            内部存储
 *   - /sdcard/Android/data/<pkg>/  App 专属外部目录（getExternalFilesDir 落在这里）
 * 模型原先放在 getExternalFilesDir(null)/models，也就是第二条 → 一清数据就没了。
 * 而这三个 gguf 是手动 push 进去的约 1.4GB 资产（不是随 APK 分发的）→ 永久丢失，
 * 只能重新下载。这就是用户截图里「本地模型或可执行文件缺失」的由来。
 *
 * ## 解法
 * 模型迁到 **共享存储里不属于任何 App 的目录**：/sdcard/ClassroomTranslator/models。
 * 「清除应用数据」不碰这个路径 → 模型永久存活。
 * 需要 AllFilesAccess 权限；拿不到时降级到 App 专属目录（功能照常，只是抗不住清数据）。
 *
 * ## 其它职责
 *  - 校验完整性：按 HF 上的字节数核对（模型被截断是最难查的故障）
 *  - 断点续传下载：手机网络抖动 / 断网是常态，必须能续
 *  - 迁移：老目录里已有的模型自动搬到新目录，不让用户重新下 1.4GB
 */
class ModelStore(private val context: Context) {

    companion object {
        private const val TAG = "ModelStore"
        const val SHARED_DIR_NAME = "ClassroomTranslator"
        const val MODEL_DIR_NAME = "models"
        private const val HF = "https://huggingface.co"
        private const val MIRROR = "https://hf-mirror.com"

        /** 允许 ±2% 的字节误差（HF 偶尔重新量化会微调文件大小） */
        private const val SIZE_TOLERANCE = 0.02
    }

    /** 一个模型文件的规格 */
    data class ModelSpec(
        val fileName: String,
        val repo: String,
        val sizeBytes: Long,
        val label: String,
        /** false = 可选模型，缺失只降级不报错 */
        val required: Boolean = true,
    ) {
        fun remotePath(): String = "$repo/resolve/main/$fileName"
    }

    val asrModel = ModelSpec(
        fileName = "Qwen3-ASR-0.6B-Q8_0.gguf",
        repo = "ggml-org/Qwen3-ASR-0.6B-GGUF",
        sizeBytes = 804_749_248L,
        label = "语音识别模型",
    )

    val asrMmproj = ModelSpec(
        fileName = "mmproj-Qwen3-ASR-0.6B-Q8_0.gguf",
        repo = "ggml-org/Qwen3-ASR-0.6B-GGUF",
        sizeBytes = 214_392_480L,
        label = "语音识别音频编码器",
    )

    val transModel = ModelSpec(
        fileName = "Qwen3-0.6B-Q4_K_M.gguf",
        repo = "unsloth/Qwen3-0.6B-GGUF",
        sizeBytes = 396_705_472L,
        label = "本地翻译模型",
    )

    val specs: List<ModelSpec> = listOf(asrModel, asrMmproj, transModel)

    /** 模型总字节数（用于空间预检与下载进度） */
    val totalBytes: Long get() = specs.sumOf { it.sizeBytes }

    // ------------------------------------------------------------------
    // 目录解析
    // ------------------------------------------------------------------

    /** 老目录（会被清数据删掉的那个） */
    private fun legacyDir(): File = File(context.getExternalFilesDir(null), MODEL_DIR_NAME)

    /** 共享存储目录：清应用数据不会删 */
    fun sharedDir(): File? {
        if (!hasSharedStorageAccess()) return null
        val root = try {
            Environment.getExternalStorageDirectory()
        } catch (t: Throwable) {
            Log.w(TAG, "getExternalStorageDirectory failed", t); null
        } ?: return null
        val dir = File(File(root, SHARED_DIR_NAME), MODEL_DIR_NAME)
        return if (dir.exists() || dir.mkdirs()) dir else null
    }

    /**
     * 当前生效的模型目录。
     * 优先共享存储（抗清数据），拿不到权限则降级 App 专属目录。
     */
    fun modelDir(): File {
        val shared = sharedDir()
        if (shared != null) return shared
        return legacyDir().apply { mkdirs() }
    }

    /** 当前目录是否抗得住「清除应用数据」 */
    fun isDurable(): Boolean = sharedDir() != null

    /** 是否有权访问共享存储 */
    fun hasSharedStorageAccess(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // Android 10 及以下：写权限已在 manifest 声明
            @Suppress("DEPRECATION")
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    } catch (t: Throwable) {
        false
    }

    /**
     * 引导用户授予"所有文件访问权限"。
     *
     * 没有这个权限，模型只能放在 App 专属目录 —— 而那个目录**会被"清除应用数据"删掉**，
     * 也就是用户这次遇到的事故本身。所以这个权限直接决定模型能不能活下来。
     *
     * Android 11+ 必须跳系统设置页手动授权，App 无法静默申请。
     */
    fun permissionRequestIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } catch (t: Throwable) {
            // 部分 ROM 没有这个 Activity → 退到全局页
            try {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } catch (t2: Throwable) {
                null
            }
        }
    }

    /** 权限未授予时给用户看的一句话 */
    fun permissionHint(): String {
        if (hasSharedStorageAccess()) return ""
        return "未授予「所有文件访问权限」：模型只能存在 App 专属目录，" +
            "而该目录会被系统「清除应用数据」删除（就是这次的故障原因）。"
    }

    // ------------------------------------------------------------------
    // 完整性校验
    // ------------------------------------------------------------------

    /** 单个模型的状态 */
    data class FileState(
        val spec: ModelSpec,
        val file: File,
        val exists: Boolean,
        val actualBytes: Long,
        val sizeOk: Boolean,
    ) {
        val ok: Boolean get() = exists && sizeOk
        /** 缺失/损坏时的说明 */
        fun problem(): String? = when {
            !exists -> "${spec.label}缺失（${spec.fileName}）"
            !sizeOk -> "${spec.label}大小异常（期望 ${spec.sizeBytes}，实际 $actualBytes）"
            else -> null
        }
    }

    fun stateOf(spec: ModelSpec, dir: File = modelDir()): FileState {
        val f = File(dir, spec.fileName)
        val exists = f.exists()
        val size = if (exists) f.length() else 0L
        val tol = (spec.sizeBytes * SIZE_TOLERANCE).toLong()
        val sizeOk = exists && kotlin.math.abs(size - spec.sizeBytes) <= tol
        return FileState(spec, f, exists, size, sizeOk)
    }

    fun allStates(dir: File = modelDir()): List<FileState> = specs.map { stateOf(it, dir) }

    fun allReady(dir: File = modelDir()): Boolean = allStates(dir).all { it.ok }

    /** 有问题的模型（缺失或大小不对） */
    fun brokenModels(dir: File = modelDir()): List<FileState> =
        allStates(dir).filter { !it.ok }

    // ------------------------------------------------------------------
    // 迁移：老目录 → 新目录
    // ------------------------------------------------------------------

    /**
     * 把老目录（会被清数据删掉的）里的模型搬到共享存储目录。
     * 返回搬运的字节数。搬完不删老文件 —— 万一新目录权限出问题还能回退。
     */
    fun migrateFromLegacy(): Long {
        val target = sharedDir() ?: return 0L
        val legacy = legacyDir()
        if (!legacy.exists()) return 0L
        var moved = 0L
        for (spec in specs) {
            val src = File(legacy, spec.fileName)
            val dst = File(target, spec.fileName)
            if (!src.exists() || src.length() <= 0) continue
            if (dst.exists() && dst.length() == src.length()) {
                Log.i(TAG, "migrate: ${spec.fileName} already present at target")
                continue
            }
            val ok = runCatching { src.copyTo(dst, overwrite = true) }.isSuccess
            if (ok) {
                moved += src.length()
                Log.i(TAG, "migrate: ${spec.fileName} -> shared (${src.length()} bytes)")
            } else {
                Log.e(TAG, "migrate failed: ${spec.fileName}")
            }
        }
        return moved
    }

    /**
     * 删除所有已下载的模型（恢复初始形态时可选用）。
     * @return 释放的字节数
     */
    fun deleteAll(): Long {
        var freed = 0L
        val dirs = listOf(sharedDir(), legacyDir())
        for (spec in specs) {
            for (d in dirs) {
                if (d == null) continue
                for (f in listOf(File(d, spec.fileName), File(d, spec.fileName + ".part"))) {
                    if (f.exists() && f.delete()) {
                        freed += spec.sizeBytes
                        Log.i(TAG, "deleteAll: removed ${f.name}")
                    }
                }
            }
        }
        return freed
    }

    // ------------------------------------------------------------------
    // 下载（断点续传）
    // ------------------------------------------------------------------

    /** 下载进度回调： (已下载字节, 总字节) */
    fun interface ProgressListener {
        fun onProgress(downloaded: Long, total: Long)
    }

    /**
     * 下载一个模型（断点续传）。已存在且大小正确则直接跳过。
     * @return true = 文件就绪
     */
    suspend fun download(
        spec: ModelSpec,
        onProgress: ProgressListener = ProgressListener { _, _ -> },
    ): Boolean = withContext(Dispatchers.IO) {
        val dir = modelDir()
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "download: cannot create ${dir.absolutePath}")
            return@withContext false
        }
        val target = File(dir, spec.fileName)
        val part = File(dir, spec.fileName + ".part")

        // 已就绪
        if (target.exists() && stateOf(spec, dir).sizeOk) {
            Log.i(TAG, "download: ${spec.fileName} already ready")
            onProgress.onProgress(spec.sizeBytes, spec.sizeBytes)
            return@withContext true
        }
        // 目标存在但大小不对（上次下坏了）→ 删掉重下
        if (target.exists() && !stateOf(spec, dir).sizeOk) {
            Log.w(TAG, "download: ${spec.fileName} size mismatch, re-download")
            target.delete()
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        val mirrors = listOf(HF, MIRROR)
        var lastError: Throwable? = null

        for (base in mirrors) {
            val url = "$base/${spec.remotePath()}"
            try {
                val resumeAt = if (part.exists()) part.length() else 0L
                val builder = Request.Builder().url(url)
                if (resumeAt > 0) builder.header("Range", "bytes=$resumeAt-")
                val resp = client.newCall(builder.build()).execute()
                if (!resp.isSuccessful) {
                    resp.close()
                    throw IllegalStateException("HTTP ${resp.code}")
                }
                // 服务端不支持断点（返回 200 而不是 206）→ 从头写
                val resuming = resp.code == 206
                if (!resuming && resumeAt > 0) {
                    part.delete()
                }
                val body = resp.body ?: throw IllegalStateException("empty body")
                val total = (body.contentLength() + (if (resuming) resumeAt else 0L)).coerceAtLeast(spec.sizeBytes)
                var downloaded = if (resuming) resumeAt else 0L

                RandomAccessFile(part, "rw").use { raf ->
                    raf.seek(if (resuming) resumeAt else 0L)
                    body.byteStream().use { input ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            raf.write(buf, 0, n)
                            downloaded += n
                            onProgress.onProgress(downloaded, total)
                        }
                    }
                }
                resp.close()

                if (downloaded < spec.sizeBytes * 0.5) {
                    throw IllegalStateException("下载不完整：$downloaded/$spec.sizeBytes")
                }
                // 校验大小后改名
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                val st = stateOf(spec, dir)
                Log.i(TAG, "download done: ${spec.fileName} ${st.actualBytes} bytes sizeOk=${st.sizeOk}")
                if (!st.sizeOk) {
                    Log.e(TAG, "download: size check failed for ${spec.fileName}")
                    return@withContext false
                }
                return@withContext true
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "download via $base failed: ${t.message}")
            }
        }
        Log.e(TAG, "download failed: ${spec.fileName}", lastError)
        false
    }

    /** 磁盘剩余空间（字节）；拿不到返回 -1 */
    fun freeSpaceBytes(): Long = try {
        val stat = android.os.StatFs(modelDir().absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    } catch (t: Throwable) {
        -1L
    }

    /** 人类可读的字节数 */
    fun human(bytes: Long): String = when {
        bytes < 0 -> "未知"
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
        else -> "$bytes B"
    }
}
