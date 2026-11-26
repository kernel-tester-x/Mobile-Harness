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
    private const val SYSTEM_PROMPT = """[System Instructions]
You are Local AI running offline on the user's phone inside a coding workspace.
For normal questions, answer directly and concisely.
Paths are relative to the workspace root ('.' or 'attachments'). To inspect files, edit files, or run build/Python/C++/Git commands, output ONE tool block:
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
        val logFile = File(workspace.parentFile?.parentFile ?: workspace, "local-agent.log")
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

        val raw = mutableListOf<Pair<String, String>>()
        history.takeLast(6).forEach { m ->
            val r = if (m.toString().let { it.contains("USER", true) || it.contains("isUser=true", true) || it.contains("fromUser=true", true) }) "user" else "assistant"
            if (m.text.isNotBlank()) raw += r to m.text.take(1500)
        }
        raw += "user" to prompt

        val msgs = buildAlternatingMessages(raw)

        for (step in 0 until 6) {
            if (isStopped()) break
            val reply = callServer(msgs, logFile, isStopped)
            val m = Regex("<tool>(\\{[\\s\\S]*?\\})</tool>").find(reply)
            val visible = reply.replace(Regex("<tool>[\\s\\S]*?</tool>"), "").trim()
            if (visible.isNotEmpty()) {
                eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, visible + if (m != null) "\n" else ""))
            }
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

    private fun buildAlternatingMessages(raw: List<Pair<String, String>>): JSONArray {
        val merged = mutableListOf<Pair<String, String>>()
        for ((role, text) in raw) {
            if (merged.isEmpty() && role == "assistant") continue
            if (merged.isNotEmpty() && merged.last().first == role) {
                val prev = merged.removeAt(merged.lastIndex)
                merged += role to "${prev.second}\n\n$text"
            } else {
                merged += role to text
            }
        }
        if (merged.isEmpty()) merged += "user" to "hi"
        val first = merged[0]
        merged[0] = "user" to "$SYSTEM_PROMPT\n\n${first.second}"
        val arr = JSONArray()
        merged.forEach { (r, t) -> arr.put(JSONObject().put("role", r).put("content", t)) }
        return arr
    }

    private fun resolvePath(workspace: File, raw: String): File {
        var rel = raw.trim()
        rel = rel.replace(Regex("^/workspace/[^/]+/?"), "").replace(Regex("^/workspace/?"), "")
        rel = rel.removePrefix("/").ifBlank { "." }
        val f = File(workspace, rel).canonicalFile
        if (!f.path.startsWith(workspace.canonicalPath)) error("Access denied: $raw")
        return f
    }

    private fun executeTool(name: String, args: JSONObject, workspace: File, installer: RuntimeInstaller): String {
        return runCatching {
            when (name) {
                "list_files" -> {
                    val target = resolvePath(workspace, args.optString("path", "."))
                    if (!target.exists()) return "Not found: ${target.name}"
                    if (target.isFile) return "FILE: ${target.name} (${target.length()} bytes)"
                    val items = target.walkTopDown().maxDepth(3).filter { it != target }.take(80).toList()
                    if (items.isEmpty()) "(empty)" else items.joinToString("\n") {
                        val rel = it.relativeTo(workspace).path
                        if (it.isDirectory) "[dir] $rel" else "$rel (${it.length()} bytes)"
                    }
                }
                "read_file" -> {
                    val f = resolvePath(workspace, args.optString("path"))
                    if (!f.exists() || !f.isFile) return "File not found"
                    f.readText().take(8000)
                }
                "write_file" -> {
                    val f = resolvePath(workspace, args.optString("path"))
                    f.parentFile?.mkdirs()
                    f.writeText(args.optString("content"))
                    "Wrote ${f.relativeTo(workspace).path} (${f.length()} bytes)"
                }
                "run_command" -> {
                    val cmd = args.optString("cmd")
                    val installed = runCatching { installer.installedRuntime() }.getOrNull()
                    val env = mapOf(
                        "HOME" to "/root",
                        "TMPDIR" to "/tmp",
                        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                        "LANG" to "C.UTF-8",
                    )
                    val proc = if (installed != null && installed.proot.canExecute() && installed.rootfs.exists()) {
                        installer.process(installed.proot, installed.rootfs, workspace, env, listOf("/bin/sh", "-c", cmd))
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

    private fun callServer(messages: JSONArray, logFile: File, isStopped: () -> Boolean): String {
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
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty().take(300)
                runCatching { logFile.appendText("HTTP ${conn.responseCode}: $err\n") }
                error("HTTP ${conn.responseCode}: $err")
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
                    val piece = if (delta.isNull("content")) "" else delta.optString("content", "")
                    if (piece.isNotEmpty()) full.append(piece)
                }
            }
        } finally {
            conn.disconnect()
        }
        return full.toString()
    }
}
