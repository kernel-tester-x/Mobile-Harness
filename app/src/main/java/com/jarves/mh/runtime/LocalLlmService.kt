package com.jarves.mh.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import java.io.File

class LocalLlmService : Service() {
    companion object {
        const val ACTION_START = "com.jarves.mh.LOCAL_LLM_START"
        const val ACTION_STOP = "com.jarves.mh.LOCAL_LLM_STOP"
        const val EXTRA_MODEL_PATH = "model_path"
        private const val CHANNEL_ID = "local_llm_channel"
        private const val NOTIF_ID = 8080

        @Volatile
        private var process: Process? = null

        fun start(context: Context, modelPath: String) {
            val intent = Intent(context, LocalLlmService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODEL_PATH, modelPath)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LocalLlmService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            process?.destroyForcibly()
            process = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val modelPath = intent?.getStringExtra(EXTRA_MODEL_PATH) ?: return START_NOT_STICKY
        val modelName = File(modelPath).name
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification("Running $modelName"))

        Thread {
            try {
                process?.destroyForcibly()
                process = null
                val bin = File(applicationInfo.nativeLibraryDir, "libllama_server.so")
                val logFile = File(filesDir, "llama-server.log")
                val pb = ProcessBuilder(
                    bin.absolutePath,
                    "-m", modelPath,
                    "--jinja",
                    "--flash-attn", "on",
                    "--ctx-size", "12288",
                    "--parallel", "1",
                    "--cache-type-k", "q8_0",
                    "--cache-type-v", "q8_0",
                    "--batch-size", "512",
                    "--ubatch-size", "512",
                    "--threads", "4",
                    "--threads-batch", "6",
                    "--host", "127.0.0.1",
                    "--port", "8080"
                )
                pb.redirectErrorStream(true)
                pb.redirectOutput(logFile)
                process = pb.start()
            } catch (e: Exception) {
                File(filesDir, "llama-server.log").appendText("\nLaunch error: ${e.message}\n")
            }
        }.start()

        return START_STICKY
    }

    override fun onDestroy() {
        process?.destroyForcibly()
        process = null
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Local AI Engine", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Mobile Harness Local AI")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }
}
