package com.jarves.mh.runtime

import android.app.ActivityManager
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogExporter {
    fun export(context: Context): String {
        return try {
            val sb = StringBuilder()
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            sb.appendLine("=== MOBILE HARNESS DIAGNOSTIC LOG ($ts) ===")
            sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            sb.appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            sb.appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")

            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            sb.appendLine("RAM: ${mi.availMem / (1024 * 1024)} MB free / ${mi.totalMem / (1024 * 1024)} MB total (lowMem=${mi.lowMemory})")

            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            sb.appendLine("Storage: ${stat.availableBytes / (1024 * 1024 * 1024)} GB free")

            val nativeDir = File(context.applicationInfo.nativeLibraryDir)
            listOf("libllama_server.so", "libproot.so").forEach { name ->
                val f = File(nativeDir, name)
                sb.appendLine("Native $name: exists=${f.exists()} exec=${f.canExecute()} size=${f.length()}")
            }

            sb.appendLine("\n=== LOCAL ENDPOINTS ===")
            sb.appendLine("8080 /health: ${probe("http://127.0.0.1:8080/health")}")
            sb.appendLine("8081 /status: ${probe("http://127.0.0.1:8081/status")}")

            sb.appendLine("\n=== DOWNLOADED MODELS ===")
            val modelsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MobileHarnessModels")
            modelsDir.listFiles()?.forEach { f ->
                sb.appendLine("${f.name} (${f.length() / (1024 * 1024)} MB)")
            } ?: sb.appendLine("(none)")

            sb.appendLine("\n=== LLAMA-SERVER.LOG ===")
            val llamaLog = File(context.filesDir, "llama-server.log")
            sb.appendLine(if (llamaLog.exists()) llamaLog.readText().takeLast(20000) else "(not created yet)")

            sb.appendLine("\n=== LOCAL-AGENT.LOG ===")
            val agLog = File(context.filesDir, "local-agent.log")
            sb.appendLine(if (agLog.exists()) agLog.readText().takeLast(8000) else "(none)")
            sb.appendLine("\n=== ANDROID LOGCAT (LAST 500 LINES) ===")
            val logcat = runCatching {
                ProcessBuilder("logcat", "-d", "-t", "500").redirectErrorStream(true).start()
                    .inputStream.bufferedReader().readText()
            }.getOrElse { "logcat error: ${it.message}" }
            sb.appendLine(logcat)

            val outDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MobileHarnessLogs")
            outDir.mkdirs()
            val outFile = File(outDir, "mh-log-latest.txt")
            outFile.writeText(sb.toString())
            MediaScannerConnection.scanFile(context, arrayOf(outFile.absolutePath), arrayOf("text/plain"), null)
            "Saved full log to Download/MobileHarnessLogs/mh-log-latest.txt"
        } catch (e: Exception) {
            "Log export failed: ${e.message}"
        }
    }

    private fun probe(url: String): String = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 1000
            readTimeout = 1000
        }
        "${c.responseCode}: ${runCatching { c.inputStream.bufferedReader().readText() }.getOrDefault("")}"
    } catch (e: Exception) {
        "ERR: ${e.message}"
    }
}
