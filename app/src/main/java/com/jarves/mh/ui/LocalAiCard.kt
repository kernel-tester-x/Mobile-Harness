package com.jarves.mh.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.runtime.LocalModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun LocalAiCard(
    context: Context,
    provider: ProviderProfile,
    onSaveProvider: (ProviderProfile, String) -> Unit,
    scope: CoroutineScope,
) {
    var localUncensored by rememberSaveable { mutableStateOf(false) }
    var localDownloadPct by remember { mutableStateOf<Int?>(null) }
    var localStatusMsg by remember { mutableStateOf("") }
    var localServerAlive by remember { mutableStateOf(false) }
    var localFilesTick by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        localServerAlive = LocalModelManager.isServerRunning()
    }

    val importGgufLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { LocalModelManager.importGguf(context, uri) }
                .onSuccess { f -> localFilesTick++; localStatusMsg = "Imported ${f.name}" }
                .onFailure { e -> localStatusMsg = e.message ?: "Import failed" }
        }
    }

    val ramGb = remember { LocalModelManager.getTotalRamGb(context) }
    val downloaded = remember(localFilesTick) { LocalModelManager.listDownloadedModels(context) }
    val filteredPresets = LocalModelManager.presets.filter { it.uncensored == localUncensored }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Local AI (Offline)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    String.format("%.1f GB RAM · %s", ramGb, if (localServerAlive) "Server On" else "Server Off"),
                    fontSize = 12.sp,
                    color = if (localServerAlive) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!localUncensored) {
                    Button(onClick = { localUncensored = false }, modifier = Modifier.weight(1f)) { Text("Censored") }
                    OutlinedButton(onClick = { localUncensored = true }, modifier = Modifier.weight(1f)) { Text("Uncensored") }
                } else {
                    OutlinedButton(onClick = { localUncensored = false }, modifier = Modifier.weight(1f)) { Text("Censored") }
                    Button(onClick = { localUncensored = true }, modifier = Modifier.weight(1f)) { Text("Uncensored") }
                }
            }
            filteredPresets.forEach { preset ->
                val exists = downloaded.any { it.name == preset.filename }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(preset.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(preset.sizeLabel, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(enabled = localDownloadPct == null, onClick = {
                        scope.launch {
                            localDownloadPct = 0
                            runCatching { LocalModelManager.downloadModel(context, preset) { localDownloadPct = it } }
                                .onSuccess { localFilesTick++; localStatusMsg = "Downloaded ${preset.filename}" }
                                .onFailure { localStatusMsg = it.message ?: "Download failed" }
                            localDownloadPct = null
                        }
                    }) {
                        Text(if (localDownloadPct != null) "${localDownloadPct}%" else if (exists) "Re-download" else "Download")
                    }
                }
            }
            if (downloaded.isNotEmpty()) {
                Text("Installed: " + downloaded.joinToString { it.name }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { importGgufLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) { Text("Import .gguf") }
                Button(onClick = {
                    val chosen = filteredPresets.firstOrNull()?.id ?: ProviderKind.LOCAL_AI.defaultModel
                    onSaveProvider(
                        provider.copy(
                            kind = ProviderKind.LOCAL_AI,
                            baseUrl = "http://127.0.0.1:8080/v1",
                            model = chosen,
                            dshApi = "openai-completions",
                        ),
                        "local",
                    )
                    localStatusMsg = "Local AI active (127.0.0.1:8080/v1)"
                }, modifier = Modifier.weight(1f)) { Text("Use Local AI") }
            }
            if (localStatusMsg.isNotEmpty()) {
                Text(localStatusMsg, fontSize = 11.sp, color = Color(0xFFF97316))
            }
        }
    }
}
