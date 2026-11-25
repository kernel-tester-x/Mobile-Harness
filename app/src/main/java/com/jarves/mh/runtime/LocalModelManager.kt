package com.jarves.mh.runtime

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
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
    val minRamGb: Int,
    val sizeGb: Double,
    val sizeLabel: String,
    val reviewNote: String,
    val filename: String,
    val downloadUrl: String,
)

object LocalModelManager {
    private var serverProcess: Process? = null

    val presets = listOf(
        LocalModelPreset("qwen3-1.7b", "Qwen3 1.7B (Ultra-Fast)", false, 3, 1.1, "1.1 GB · 3GB+ RAM", "Fastest 2026 coding & tool model for mobile CPU (~3x faster prefill).", "Qwen3-1.7B-Q4_K_M.gguf", "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"),
        LocalModelPreset("qwen3-4b-2507", "Qwen3 4B Instruct (2507)", false, 4, 2.5, "2.5 GB · 4GB+ RAM", "Fast 2026 4B model with strong tool calling & zero thinking lag.", "Qwen3-4B-Instruct-2507-Q4_K_M.gguf", "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf"),
        LocalModelPreset("qwen3.5-4b", "Qwen3.5 4B (Top 8GB Pick)", false, 6, 3.4, "3.4 GB · 6GB+ RAM", "Top-rated 2026 model for 8GB RAM: coding, tool use & reasoning.", "Qwen3.5-4B-Q4_K_M.gguf", "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf"),
        LocalModelPreset("qwen3-8b", "Qwen3 8B", false, 8, 5.0, "5.0 GB · 8GB+ RAM", "High-accuracy 2026 coding & reasoning; close background apps on 8GB.", "Qwen3-8B-Q4_K_M.gguf", "https://huggingface.co/Qwen/Qwen3-8B-GGUF/resolve/main/Qwen3-8B-Q4_K_M.gguf"),
        LocalModelPreset("qwen3.5-9b", "Qwen3.5 9B", false, 8, 5.7, "5.7 GB · 8-12GB RAM", "Flagship compact Qwen3.5 for 8-12GB devices; best multi-file coding.", "Qwen3.5-9B-Q4_K_M.gguf", "https://huggingface.co/unsloth/Qwen3.5-9B-GGUF/resolve/main/Qwen3.5-9B-Q4_K_M.gguf"),
        LocalModelPreset("qwen3.5-9b-uncensored", "Qwen3.5 9B Abliterated", true, 8, 5.2, "5.2 GB · 8-12GB RAM", "Uncensored Qwen3.5 9B with refusals abliterated; strong coding.", "Qwen3.5-9B-abliterated-Q4_K_M.gguf", "https://huggingface.co/lukey03/Qwen3.5-9B-abliterated-GGUF/resolve/main/Qwen3.5-9B-abliterated-Q4_K_M.gguf"),
        LocalModelPreset("qwen2.5-coder-3b-uncensored", "Qwen2.5 Coder 3B Abliterated", true, 4, 2.0, "2.0 GB · 4GB+ RAM", "Lightweight uncensored coding fallback for fast responses.", "Qwen2.5-Coder-3B-Instruct-abliterated.Q4_K_M.gguf", "https://huggingface.co/MaziyarPanahi/Qwen2.5-Coder-3B-Instruct-abliterated-GGUF/resolve/main/Qwen2.5-Coder-3B-Instruct-abliterated.Q4_K_M.gguf"),
    )

    fun getTotalRamGb(context: Context): Double {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem / (1024.0 * 1024.0 * 1024.0)
    }

    fun getFreeStorageGb(context: Context): Double = runCatching {
        val st = StatFs(modelsDir(context).absolutePath)
        st.availableBytes / (1024.0 * 1024.0 * 1024.0)
    }.getOrDefault(0.0)

    fun compatibilityLabel(ramGb: Double, preset: LocalModelPreset): Pair<String, Int> = when {
        ramGb >= preset.minRamGb + 1.5 -> "Compatible ✓" to 0xFF81C995.toInt()
        ramGb >= preset.minRamGb - 0.5 -> "Tight Fit · Close apps" to 0xFFF59E0B.toInt()
        else -> "Needs ${preset.minRamGb + 2}GB+ RAM" to 0xFFEF4444.toInt()
    }

    fun hasArmDotProd(): Boolean =
        runCatching { File("/proc/cpuinfo").readText().contains("asimddp") }.getOrDefault(true)

    fun recommendedThreads(): Int =
        if (Runtime.getRuntime().availableProcessors() >= 8) 4 else (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 4)

    fun buildLlamaServerCommand(): String {
        val flags = if (hasArmDotProd()) "-march=armv8.2-a+fp16+dotprod" else "-march=armv8-a"
        return "git clone https://github.com/ggml-org/llama.cpp ~/llama.cpp && cmake -S ~/llama.cpp -B ~/llama.cpp/build -DGGML_NATIVE=OFF -DCMAKE_C_FLAGS=\"$flags\" -DCMAKE_CXX_FLAGS=\"$flags\" && cmake --build ~/llama.cpp/build -j4 --target llama-server"
    }

    fun miBandSkillCommand(): String =
        "mkdir -p .claude/skills && git clone https://github.com/Aser-Mohamed/Vela-JS-Skill .claude/skills/vela-js && git clone https://github.com/Sucharek233/MiBand-DevBox-Android .claude/skills/miband-devbox"

    fun recommendedServerCommand(modelPath: String): String =
        "~/llama.cpp/build/bin/llama-server -m $modelPath --jinja --flash-attn --ctx-size 4096 --batch-size 256 -t ${recommendedThreads()} --host 127.0.0.1 --port 8080"

    fun modelsDir(context: Context): File {
        val dl = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MobileHarnessModels")
        if (android.os.Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) { if (runCatching { dl.mkdirs() || dl.exists() }.getOrDefault(false) && dl.canWrite()) return dl }
        return File(context.filesDir, "local_models").apply { mkdirs() }
    }

    fun listDownloadedModels(context: Context): List<File> =
        modelsDir(context).listFiles()?.filter { it.isFile && it.name.endsWith(".gguf", true) }?.sortedBy { it.name } ?: emptyList()

    suspend fun tryAutoStartServer(context: Context, modelFile: File): String = withContext(Dispatchers.IO) {
        try {
            LocalLlmService.start(context, modelFile.absolutePath)
            "Local AI engine starting automatically! Switch to Chat in ~10s."
        } catch (e: Exception) {
            "Local AI error: ${e.message}"
        }
    }

    suspend fun searchHuggingFaceGguf(query: String): List<LocalModelPreset> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.ifBlank { "Qwen3.5 GGUF" }, "UTF-8")
        val api = "https://huggingface.co/api/models?search=$q&filter=gguf&sort=downloads&direction=-1&limit=12"
        val arr = JSONArray(URL(api).readText())
        val out = mutableListOf<LocalModelPreset>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val repo = obj.optString("id")
            val downloads = obj.optInt("downloads", 0)
            if (repo.isBlank()) continue
            runCatching {
                val info = JSONObject(URL("https://huggingface.co/api/models/$repo?blobs=true").readText())
                val siblings = info.optJSONArray("siblings") ?: return@runCatching
                var pickName = ""; var pickSize = 0L
                for (j in 0 until siblings.length()) {
                    val sib = siblings.getJSONObject(j)
                    val fn = sib.optString("rfilename")
                    if (fn.endsWith(".gguf", true)) {
                        val sz = sib.optLong("size", 0L)
                        if (pickName.isEmpty() || fn.contains("Q4_K_M", true)) {
                            pickName = fn; pickSize = sz
                            if (fn.contains("Q4_K_M", true)) break
                        }
                    }
                }
                if (pickName.isEmpty()) return@runCatching
                val gb = if (pickSize > 0) pickSize / (1024.0 * 1024.0 * 1024.0) else 3.5
                val minRam = when { gb >= 5.0 -> 8; gb >= 3.0 -> 6; else -> 4 }
                val unc = repo.contains("abliterated", true) || repo.contains("uncensored", true) || repo.contains("dolphin", true)
                out += LocalModelPreset(repo, repo.substringAfter('/'), unc, minRam, gb, String.format("%.2f GB · %dGB+ RAM", gb, minRam), "Hugging Face · %,d downloads · %s".format(downloads, repo), pickName.substringAfterLast('/'), "https://huggingface.co/$repo/resolve/main/$pickName")
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
            instanceFollowRedirects = true; connectTimeout = 15000; readTimeout = 30000
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
        tmp.renameTo(dest); onProgress(100); return dest
    }

    suspend fun downloadModel(context: Context, preset: LocalModelPreset, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val candidates = listOf(preset) + presets.filter { it.uncensored == preset.uncensored && it.id != preset.id }
        var lastErr: Throwable? = null
        for (cand in candidates) {
            for (u in listOf(cand.downloadUrl, cand.downloadUrl.replace("https://huggingface.co/", "https://hf-mirror.com/"))) {
                val dest = File(modelsDir(context), cand.filename)
                val tmp = File(modelsDir(context), "${cand.filename}.part")
                val res = runCatching { downloadWithResume(u, dest, tmp, onProgress) }
                if (res.isSuccess) return@withContext res.getOrThrow()
                lastErr = res.exceptionOrNull(); tmp.delete()
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
