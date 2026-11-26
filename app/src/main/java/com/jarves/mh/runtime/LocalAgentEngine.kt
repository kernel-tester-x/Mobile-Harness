package com.jarves.mh.runtime

import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.RuntimeEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object LocalAgentEngine {
    private const val SYSTEM_PROMPT = """You are Local AI running offline on the user's phone inside a coding workspace.
For normal questions, answer directly and concisely.
When you need to inspect files, edit files, or run build/Python/C++/Git commands in the workspace, output ONE XML tool block and wait for the result:
<tool>{"name":"list_files","path":"."}</tool>
<tool>{"name":"read_file","path":"file.txt"}</tool>
<tool>{"name":"write_file","path":"file.txt","content":"..."}</tool>
<tool>{"name":"run_command","cmd":"python3 --version"}</tool>"""

    suspend fun runSession(
        sessionId: String,
        workspace: File,
        prompt: String,
        history: List<ChatMessage>,
        installer: RuntimeInstaller,
        eventBus: MutableSharedFlow<RuntimeEvent>,
        isStopped: () -> Boolean,
    ) {
        val alive = runCatching {
            val c = URL("http://127.0.0.1:8080/health").openConnection() as HttpURLConnection
            c.connectTimeout = 1000
            c.readTimeout = 1000
            c.responseCode == 200
        }.getOrDefault(false)

        if (!alive) {
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, "Local AI engine is not running. Open Agent → Local AI and tap Use Model or Start Engine."))
            return
        }

        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        history.takeLast(6).forEach { m ->
            val r = if (m.toString().let { it.contains("USER", true) || it.contains("isUser=true", true) || it.contains("fromUser=true", true) }) "user" else "assistant"
            if (m.text.isNotBlank()) msgs.put(JSONObject().put("role", r).put("content", m.text.take(1500)))
        }
        msgs.put(JSONObject().put("role", "user").put("content", prompt))

        for (step in 0 until 6) {
            if (isStopped()) break
            val reply = callServer(msgs, isStopped) { delta ->
                if (!delta.contains("<tool>")) {
                    eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, delta))
                }
            }
            val m = Regex("<tool>(\\{[\\s\\S]*?\\})</tool>").find(reply)
            if (m == null) break

            val json = runCatching { JSONObject(m.groupValues[1]) }.getOrNull() ?: break
            val toolName = json.optString("name")
            val detail = json.optString("path").ifBlank { json.optString("cmd") }
            eventBus.emit(RuntimeEvent.ToolStarted(sessionId, toolName, detail))
            val result = executeTool(toolName, json, workspace, installer)
            eventBus.emit(RuntimeEvent.ToolCompleted(sessionId, toolName, result.take(240)))

            msgs.put(JSONObject().put("role", "assistant").put("content", reply))
            msgs.put(JSONObject().put("role", "user").put("content", "Tool result ($toolName):\n${result.take(4000)}"))
        }
    }

    private fun executeTool(name: String, args: JSONObject, workspace: File, installer: RuntimeInstaller): String {
        return runCatching {
            when (name) {
                "list_files" -> {
                    val rel = args.optString("path", ".").removePrefix("/")
                    val dir = File(workspace, rel).canonicalFile
                    if (!dir.path.startsWith(workspace.canonicalPath)) return "Access denied"
                    dir.listFiles()?.sortedBy { it.name }?.take(100)
                        ?.joinToString("\n") { (if (it.isDirectory) "[dir] " else "") + it.name }
                        ?: "(empty)"
                }
                "read_file" -> {
                    val f = File(workspace, args.optString("path").removePrefix("/")).canonicalFile
                    if (!f.path.startsWith(workspace.canonicalPath) || !f.exists()) return "File not found"
                    f.readText().take(8000)
                }
                "write_file" -> {
                    val f = File(workspace, args.optString("path").removePrefix("/")).canonicalFile
                    if (!f.path.startsWith(workspace.canonicalPath)) return "Access denied"
                    f.parentFile?.mkdirs()
                    f.writeText(args.optString("content"))
                    "Wrote ${f.name} (${f.length()} bytes)"
                }
                "run_command" -> {
                    val cmd = args.optString("cmd")
                    val installed = runCatching { installer.installedRuntime() }.getOrNull()
                    val proc = if (installed != null && installed.proot.canExecute() && installed.rootfs.exists()) {
                        installer.process(installed.proot, installed.rootfs, workspace, emptyMap(), listOf("sh", "-c", cmd))
                    } else {
                        ProcessBuilder("sh", "-c", cmd).directory(workspace).redirectErrorStream(true).start()
                    }
                    val out = proc.inputStream.bufferedReader().readText()
                    val code = proc.waitFor()
                    "Exit $code:\n${out.take(4000)}"
                }
                else -> "Unknown tool: $name"
            }
        }.getOrElse { "Tool error: ${it.message}" }
    }

    private suspend fun callServer(messages: JSONArray, isStopped: () -> Boolean, onDelta: suspend (String) -> Unit): String {
        val body = JSONObject()
            .put("model", "local")
            .put("stream", true)
            .put("max_tokens", 1024)
            .put("temperature", 0.6)
            .put("cache_prompt", true)
            .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            .put("messages", messages)

        val conn = (URL("http://127.0.0.1:8080/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 30 * 60 * 1000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
        }
        val full = StringBuilder()
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode !in 200..299) {
                error("HTTP ${conn.responseCode}: ${conn.errorStream?.bufferedReader()?.readText().orEmpty().take(300)}")
            }
            conn.inputStream.bufferedReader().use { reader ->
                while (!isStopped()) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") break
                    val delta = runCatching {
                        JSONObject(data).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                    }.getOrNull() ?: continue
                    val piece = delta.optString("content", "")
                    if (piece.isNotEmpty()) {
                        full.append(piece)
                        onDelta(piece)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return full.toString()
    }
}
