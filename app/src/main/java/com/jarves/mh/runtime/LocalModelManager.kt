package com.jarves.mh.runtime

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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
        LocalModelPreset("qwen2.5-coder-1.5b-censored", "Qwen 2.5 Coder 1.5B (Fast)", false, "~1.0 GB · 4GB+ RAM", "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf", "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf"),
        LocalModelPreset("qwen2.5-coder-3b-censored", "Qwen 2.5 Coder 3B (Balanced)", false, "~2.0 GB · 8GB RAM", "qwen2.5-coder-3b-instruct-q4_k_m.gguf", "https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/qwen2.5-coder-3b-instruct-q4_k_m.gguf"),
        LocalModelPreset("qwen2.5-coder-7b-censored", "Qwen 2.5 Coder 7B (High Quality)", false, "~4.7 GB · 8GB+ RAM", "qwen2.5-coder-7b-instruct-q4_k_m.gguf", "https://huggingface.co/Qwen/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/main/qwen2.5-coder-7b-instruct-q4_k_m.gguf"),
        LocalModelPreset("qwen2.5-coder-1.5b-uncensored", "Qwen 2.5 Coder 1.5B Abliterated", true, "~1.0 GB · 4GB+ RAM", "qwen2.5-coder-1.5b-instruct-abliterated-q4_k_m.gguf", "https://huggingface.co/issoufzousko07/Qwen2.5-Coder-1.5B-Instruct-abliterated-GGUF/resolve/main/Qwen2.5-Coder-1.5B-Instruct-abliterated-Q4_K_M.gguf"),
        LocalModelPreset("qwen2.5-coder-3b-uncensored", "Qwen 2.5 Coder 3B Abliterated", true, "~2.0 GB · 8GB RAM", "Qwen2.5-Coder-3B-Instruct-abliterated.Q4_K_M.gguf", "https://huggingface.co/MaziyarPanahi/Qwen2.5-Coder-3B-Instruct-abliterated-GGUF/resolve/main/Qwen2.5-Coder-3B-Instruct-abliterated.Q4_K_M.gguf"),
        LocalModelPreset("qwen2.5-coder-7b-uncensored", "Qwen 2.5 Coder 7B Abliterated", true, "~4.7 GB · 8GB+ RAM", "Qwen2.5-Coder-7B-Instruct-abliterated-Q4_K_M.gguf", "https://huggingface.co/bartowski/Qwen2.5-Coder-7B-Instruct-abliterated-GGUF/resolve/main/Qwen2.5-Coder-7B-Instruct-abliterated-Q4_K_M.gguf"),
    )

    fun getTotalRamGb(context: Context): Double {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem / (1024.0 * 1024.0 * 1024.0)
    }

    fun hasArmDotProd(): Boolean =
        runCatching { File("/proc/cpuinfo").readText().contains("asimddp") }.getOrDefault(true)

    fun recommendedThreads(): Int =
        if (Runtime.getRuntime().availableProcessors() >= 8) 4 else (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 4)

    fun buildLlamaServerCommand(): String {
        val flags = if (hasArmDotProd()) "-march=armv8.2-a+fp16+dotprod" else "-march=armv8-a"
        return "git clone https://github.com/ggml-org/llama.cpp ~/llama.cpp && cmake -S ~/llama.cpp -B ~/llama.cpp/build -DGGML_NATIVE=OFF -DCMAKE_C_FLAGS=\"$flags\" -DCMAKE_CXX_FLAGS=\"$flags\" && cmake --build ~/llama.cpp/build -j4 --target llama-server"
    }

    fun recommendedServerCommand(modelPath: String): String =
        "~/llama.cpp/build/bin/llama-server -m $modelPath --jinja --flash-attn --ctx-size 4096 --batch-size 256 -t ${recommendedThreads()} --host 127.0.0.1 --port 8080"

    fun modelsDir(context: Context): File = File(context.filesDir, "local_models").apply { mkdirs() }

    fun listDownloadedModels(context: Context): List<File> =
        modelsDir(context).listFiles()?.filter { it.isFile && it.name.endsWith(".gguf", true) }?.sortedBy { it.name } ?: emptyList()

    suspend fun searchHuggingFaceGguf(query: String): List<LocalModelPreset> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.ifBlank { "coder gguf" }, "UTF-8")
        val api = "https://huggingface.co/api/models?search=$q&filter=gguf&sort=downloads&direction=-1&limit=12"
        val arr = JSONArray(URL(api).readText())
        val out = mutableListOf<LocalModelPreset>()
        for (i in 0 until arr.length()) {
            val repo = arr.getJSONObject(i).optString("id")
            if (repo.isBlank()) continue
            runCatching {
                val info = JSONObject(URL("https://huggingface.co/api/models/$repo").readText())
                val siblings = info.optJSONArray("siblings") ?: return@runCatching
                val ggufs = (0 until siblings.length()).map { siblings.getJSONObject(it).optString("rfilename") }.filter { it.endsWith(".gguf", true) }
                val pick = ggufs.firstOrNull { it.contains("Q4_K_M", true) } ?: ggufs.firstOrNull() ?: return@runCatching
                val unc = repo.contains("abliterated", true) || repo.contains("uncensored", true) || repo.contains("dolphin", true)
                out += LocalModelPreset(repo, repo, unc, pick, pick.substringAfterLast('/'), "https://huggingface.co/$repo/resolve/main/$pick")
            }
        }
        out
    }

    suspend fun importGguf(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        var name = "imported-model.gguf"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = c.getString(idx) ?: name
            }
        }
        val dest = File(modelsDir(context), name.replace(Regex("[^a-zA-Z0-9._-]"), "_"))
        context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().buffered(1048576).use { output -> input.copyTo(output, 1048576) } } ?: error("Could not read file")
        dest
    }

    private fun downloadWithResume(url: String, dest: File, tmp: File, onProgress: (Int) -> Unit): File {
        val existing = if (tmp.exists()) tmp.length() else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Accept-Encoding", "identity")
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        val code = conn.responseCode
        val append = code == 206 && existing > 0
        if (code !in 200..299) error("HTTP $code")
        val contentLen = conn.contentLengthLong
        val total = if (append && contentLen > 0) existing + contentLen else contentLen
        var copied = if (append) existing else 0L
        conn.inputStream.use { input ->
            FileOutputStream(tmp, append).buffered(1048576).use { output ->
                val buf = ByteArray(1048576)
                while (true) {
                    val n = input.read(buf); if (n <= 0) break
                    output.write(buf, 0, n); copied += n
                    if (total > 0) onProgress(((copied * 100) / total).toInt().coerceIn(0, 100))
                }
            }
        }
        if (dest.exists()) dest.delete()
        tmp.renameTo(dest)
        onProgress(100)
        return dest
    }

    suspend fun downloadModel(context: Context, preset: LocalModelPreset, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val candidates = listOf(preset) + presets.filter { it.uncensored == preset.uncensored && it.id != preset.id }
        var lastErr: Throwable? = null
        for (cand in candidates) {
            val urls = listOf(cand.downloadUrl, cand.downloadUrl.replace("https://huggingface.co/", "https://hf-mirror.com/"))
            for (u in urls) {
                val dest = File(modelsDir(context), cand.filename)
                val tmp = File(modelsDir(context), "${cand.filename}.part")
                val res = runCatching { downloadWithResume(u, dest, tmp, onProgress) }
                if (res.isSuccess) return@withContext res.getOrThrow()
                lastErr = res.exceptionOrNull()
                tmp.delete()
            }
        }
        throw (lastErr ?: IllegalStateException("All model mirrors failed"))
    }

    suspend fun isServerRunning(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("http://127.0.0.1:8080/health").openConnection() as HttpURLConnection).apply { connectTimeout = 1000; readTimeout = 1000 }
            conn.responseCode in 200..299
        }.getOrDefault(false)
    }
}
