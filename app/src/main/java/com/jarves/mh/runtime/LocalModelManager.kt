package com.jarves.mh.runtime

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class LocalModelPreset(
    val id: String,
    val title: String,
    val uncensored: Boolean,
    val sizeLabel: String,
    val filename: String,
    val downloadUrl: String,
)

object LocalModelManager {
    val presets = listOf(
        LocalModelPreset(
            id = "qwen2.5-coder-3b-instruct-q4_k_m",
            title = "Qwen 2.5 Coder 3B (Standard)",
            uncensored = false,
            sizeLabel = "~2.0 GB · 8GB RAM",
            filename = "qwen2.5-coder-3b-instruct-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/qwen2.5-coder-3b-instruct-q4_k_m.gguf",
        ),
        LocalModelPreset(
            id = "qwen2.5-coder-3b-instruct-abliterated-q4_k_m",
            title = "Qwen 2.5 Coder 3B (Uncensored)",
            uncensored = true,
            sizeLabel = "~2.0 GB · 8GB RAM",
            filename = "Qwen2.5-Coder-3B-Instruct-abliterated.Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/MaziyarPanahi/Qwen2.5-Coder-3B-Instruct-abliterated-GGUF/resolve/main/Qwen2.5-Coder-3B-Instruct-abliterated.Q4_K_M.gguf",
        ),
    )

    fun getTotalRamGb(context: Context): Double {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem / (1024.0 * 1024.0 * 1024.0)
    }

    fun modelsDir(context: Context): File =
        File(context.filesDir, "local_models").apply { mkdirs() }

    fun listDownloadedModels(context: Context): List<File> =
        modelsDir(context).listFiles()?.filter { it.isFile && it.name.endsWith(".gguf", true) }?.sortedBy { it.name } ?: emptyList()

    suspend fun importGguf(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        var name = "imported-model.gguf"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = c.getString(idx) ?: name
            }
        }
        val dest = File(modelsDir(context), name.replace(Regex("[^a-zA-Z0-9._-]"), "_"))
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Could not read selected file")
        dest
    }

    suspend fun downloadModel(context: Context, preset: LocalModelPreset, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dest = File(modelsDir(context), preset.filename)
        val tmp = File(modelsDir(context), "${preset.filename}.part")
        val conn = (URL(preset.downloadUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true; connectTimeout = 20000; readTimeout = 30000
        }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            tmp.outputStream().use { output ->
                val buf = ByteArray(65536); var copied = 0L
                while (true) {
                    val n = input.read(buf); if (n <= 0) break
                    output.write(buf, 0, n); copied += n
                    if (total > 0) onProgress(((copied * 100) / total).toInt().coerceIn(0, 100))
                }
            }
        }
        if (dest.exists()) dest.delete()
        tmp.renameTo(dest); onProgress(100); dest
    }

    suspend fun isServerRunning(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("http://127.0.0.1:8080/health").openConnection() as HttpURLConnection).apply {
                connectTimeout = 1000; readTimeout = 1000
            }
            conn.responseCode in 200..299
        }.getOrDefault(false)
    }
}
