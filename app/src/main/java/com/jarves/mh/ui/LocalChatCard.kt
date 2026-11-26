package com.jarves.mh.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.runtime.LocalChatClient
import kotlinx.coroutines.*

@Composable
fun LocalChatCard(
    scope: CoroutineScope,
    engineOnline: Boolean,
) {
    val history = remember { mutableStateListOf<LocalChatClient.Msg>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var waitSeconds by remember { mutableIntStateOf(0) }
    var gotFirstToken by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Local Chat", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (history.isNotEmpty() && !busy) {
                    TextButton(onClick = { history.clear(); error = "" }) { Text("Clear") }
                }
            }
            Text(
                if (engineOnline) "Direct to the engine · 2-5s replies" else "Start the engine to chat.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            history.takeLast(6).forEach { m ->
                val mine = m.role == "user"
                Text(
                    (if (mine) "You: " else "AI: ") + m.content.ifEmpty { "…" },
                    fontSize = 13.sp,
                    color = if (mine) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (busy && !gotFirstToken) {
                Text("Reading your message… ${waitSeconds}s", fontSize = 11.sp, color = Color(0xFFA8C7FA))
            }
            if (error.isNotEmpty()) {
                Text(error, fontSize = 11.sp, color = Color(0xFFF28B82))
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Message the local model") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = engineOnline && !busy && input.isNotBlank(),
                    onClick = {
                        val text = input.trim()
                        input = ""
                        error = ""
                        history += LocalChatClient.Msg("user", text)
                        history += LocalChatClient.Msg("assistant", "")
                        val replyIndex = history.lastIndex
                        val snapshot = history.take(replyIndex).toList()
                        busy = true
                        gotFirstToken = false
                        waitSeconds = 0
                        val ticker = scope.launch { while (isActive) { delay(1000); waitSeconds++ } }
                        job = scope.launch {
                            val sb = StringBuilder()
                            try {
                                withContext(Dispatchers.IO) {
                                    LocalChatClient.stream(history = snapshot, isCancelled = { !isActive }) { piece ->
                                        sb.append(piece)
                                        gotFirstToken = true
                                        history[replyIndex] = LocalChatClient.Msg("assistant", sb.toString())
                                    }
                                }
                                if (sb.isEmpty()) error = "The engine returned an empty reply."
                            } catch (e: Exception) {
                                if (isActive) error = "Chat failed: ${e.message ?: e.javaClass.simpleName}"
                            } finally {
                                ticker.cancel()
                                busy = false
                            }
                        }
                    },
                ) { Text("Send") }
                if (busy) {
                    OutlinedButton(onClick = { job?.cancel() }) { Text("Stop") }
                }
            }
        }
    }
}
