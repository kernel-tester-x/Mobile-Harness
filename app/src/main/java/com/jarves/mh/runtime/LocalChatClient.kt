package com.jarves.mh.runtime

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

object LocalChatClient {
    data class Msg(val role: String, val content: String)

    const val SYSTEM_PROMPT = "You are a helpful assistant running on the user's phone. Be concise."
    private const val MAX_HISTORY_CHARS = 6000

    fun trimHistory(history: List<Msg>): List<Msg> {
        var used = 0
        val kept = ArrayList<Msg>()
        for (m in history.asReversed()) {
            used += m.content.length
            if (used > MAX_HISTORY_CHARS && kept.isNotEmpty()) break
            kept += m
        }
        return kept.asReversed()
    }

    fun stream(
        history: List<Msg>,
        maxTokens: Int = 768,
        isCancelled: () -> Boolean,
        onDelta: (String) -> Unit,
    ): String {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        trimHistory(history).forEach { messages.put(JSONObject().put("role", it.role).put("content", it.content)) }

        val body = JSONObject()
            .put("model", "local")
            .put("stream", true)
            .put("max_tokens", maxTokens)
            .put("temperature", 0.7)
            .put("cache_prompt", true)
            .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            .put("messages", messages)

        val conn = URL("http://127.0.0.1:8080/v1/chat/completions").openConnection() as HttpURLConnection
        val full = StringBuilder()
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5_000
            conn.readTimeout = 10 * 60 * 1000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty().take(400)
                error("Engine returned HTTP $code: $err")
            }
            conn.inputStream.bufferedReader().use { reader ->
                while (true) {
                    if (isCancelled()) break
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") break
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
