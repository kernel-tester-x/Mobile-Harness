package com.jarves.mh.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.runtime.AppLogExporter
import com.jarves.mh.runtime.LocalModelManager
import com.jarves.mh.runtime.LocalModelPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun LocalAiCard(
    context: Context,
    provider: ProviderProfile,
    onSaveProvider: (ProviderProfile, String) -> Unit,
    scope: CoroutineScope,
) {
    val clipboard = LocalClipboardManager.current
    var filterMode by rememberSaveable { mutableStateOf("ALL") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var hfResults by remember { mutableStateOf<List<LocalModelPreset>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    var localDownloadPct by remember { mutableStateOf<Int?>(null) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var localStatusMsg by remember { mutableStateOf("") }
    var localServerAlive by remember { mutableStateOf(false) }
    var localFilesTick by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) { localServerAlive = LocalModelManager.isServerRunning() }

    fun applyAndStart(file: File) {
        onSaveProvider(provider.copy(kind = ProviderKind.LOCAL_AI, baseUrl = "http://127.0.0.1:8080/v1", model = file.name, dshApi = "openai-completions"), "local")
        scope.launch {
            val msg = LocalModelManager.tryAutoStartServer(context, file)
            localServerAlive = LocalModelManager.isServerRunning()
            clipboard.setText(AnnotatedString(LocalModelManager.recommendedServerCommand(file.absolutePath)))
            localStatusMsg = "$msg (saved in Downloads/MobileHarnessModels)"
        }
    }

    val importGgufLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { LocalModelManager.importGguf(context, uri) }
                .onSuccess { f -> localFilesTick++; applyAndStart(f) }
                .onFailure { e -> localStatusMsg = e.message ?: "Import failed" }
        }
    }

    val ramGb = remember { LocalModelManager.getTotalRamGb(context) }
    val freeGb = remember(localFilesTick) { LocalModelManager.getFreeStorageGb(context) }
    val downloaded = remember(localFilesTick) { LocalModelManager.listDownloadedModels(context) }
    val baseList = if (hfResults.isNotEmpty()) hfResults else LocalModelManager.presets
    val shownModels = when (filterMode) {
        "CENSORED" -> baseList.filter { !it.uncensored }
        "UNCENSORED" -> baseList.filter { it.uncensored }
        "4_8" -> baseList.filter { it.minRamGb <= 6 }
        else -> baseList
    }
    val accentBlue = Color(0xFFA8C7FA)

    Column(
        Modifier.fillMaxWidth().animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text("Local AI Gallery", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Text(String.format("%.1f GB RAM · %.1f GB free · %s", ramGb, freeGb, if (localServerAlive) "Server Online" else "Server Offline"), fontSize = 12.sp, color = if (localServerAlive) Color(0xFF81C995) else MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.weight(1f).clickable { importGgufLauncher.launch(arrayOf("*/*")) },
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Import .gguf", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = accentBlue)
                    Text("Auto-applies model", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.weight(1f).clickable {
                    clipboard.setText(AnnotatedString(LocalModelManager.buildLlamaServerCommand()))
                    localStatusMsg = "Copied fogos ARMv8.2 build cmd (paste in Terminal)"
                },
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Build Engine", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color(0xFF81C995))
                    Text("fogos DotProd tuned", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.fillMaxWidth().clickable {
                clipboard.setText(AnnotatedString(LocalModelManager.miBandSkillCommand()))
                localStatusMsg = "Copied Mi Band 10 (Vela JS) skill cmd — paste inside your Mi Band project Terminal"
            },
        ) {
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mi Band 10 / Vela QuickApp Skills", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = accentBlue)
                    Text("Tap to copy skill installer for Mi Band projects only", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Copy", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = accentBlue)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ALL" to "All", "CENSORED" to "Censored", "UNCENSORED" to "Uncensored", "4_8" to "Fast 4-6GB").forEach { (key, label) ->
                val active = filterMode == key
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (active) accentBlue.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, if (active) accentBlue else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier.clickable { filterMode = key },
                ) {
                    Text(label, fontSize = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, color = if (active) accentBlue else MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, placeholder = { Text("Search Hugging Face (e.g. Qwen3.5, Gemma 4)...", fontSize = 12.sp) }, singleLine = true, shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f))
            Button(enabled = !searching, shape = RoundedCornerShape(24.dp), onClick = {
                scope.launch {
                    searching = true
                    val q = if (filterMode == "UNCENSORED" && !searchQuery.contains("abliterated", true) && !searchQuery.contains("uncensored", true)) "${searchQuery} abliterated" else searchQuery
                    runCatching { LocalModelManager.searchHuggingFaceGguf(q) }
                        .onSuccess { hfResults = it; localStatusMsg = "Found ${it.size} models" }
                        .onFailure { localStatusMsg = it.message ?: "Search failed" }
                    searching = false
                }
            }) { Text(if (searching) "..." else "Search") }
        }

        shownModels.forEach { preset ->
            val exists = downloaded.any { it.name == preset.filename }
            val (compatText, compatColorInt) = LocalModelManager.compatibilityLabel(ramGb, preset)
            val isExpanded = expandedId == preset.id
            val isDownloadingThis = downloadingId == preset.id

            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth().clickable { expandedId = if (isExpanded) null else preset.id },
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(preset.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Surface(shape = RoundedCornerShape(6.dp), color = if (preset.uncensored) Color(0xFFEF4444).copy(alpha = 0.18f) else accentBlue.copy(alpha = 0.18f)) {
                                    Text(if (preset.uncensored) "Uncensored" else "Standard", fontSize = 10.sp, color = if (preset.uncensored) Color(0xFFFCA5A5) else accentBlue, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                            }
                            Text("${preset.sizeLabel} · $compatText", fontSize = 12.sp, color = Color(compatColorInt))
                        }
                        Icon(if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
                    }
                    Text(preset.reviewNote, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    AnimatedVisibility(
                        visible = isExpanded,
                        enter = fadeIn(tween(220)) + expandVertically(spring(stiffness = Spring.StiffnessMediumLow)),
                        exit = fadeOut(tween(180)) + shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)),
                    ) {
                        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Saves to: Download/MobileHarnessModels/${preset.filename}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (isDownloadingThis && localDownloadPct != null) {
                                LinearProgressIndicator(progress = { (localDownloadPct ?: 0) / 100f }, modifier = Modifier.fillMaxWidth())
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(enabled = localDownloadPct == null, shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f), onClick = {
                                    scope.launch {
                                        downloadingId = preset.id; localDownloadPct = 0
                                        runCatching { LocalModelManager.downloadModel(context, preset) { localDownloadPct = it } }
                                            .onSuccess { f -> localFilesTick++; applyAndStart(f) }
                                            .onFailure { localStatusMsg = it.message ?: "Download failed" }
                                        localDownloadPct = null; downloadingId = null
                                    }
                                }) { Text(if (isDownloadingThis) "${localDownloadPct ?: 0}%" else if (exists) "Re-download & Use" else "Download & Use") }

                                if (exists) {
                                    Button(shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f), onClick = {
                                        applyAndStart(downloaded.first { it.name == preset.filename })
                                    }) { Text("Use Model") }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (downloaded.isNotEmpty()) {
            Text("Saved in Downloads (${downloaded.size})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            downloaded.forEach { file ->
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(file.name, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Text(String.format("%.2f GB", file.length() / (1024.0 * 1024.0 * 1024.0)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { applyAndStart(file) }) { Text("Use") }
                    }
                }
            }
        }

        if (localStatusMsg.isNotEmpty()) Text(localStatusMsg, fontSize = 12.sp, color = accentBlue)
            OutlinedButton(
                onClick = { localStatusMsg = AppLogExporter.export(context) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Export Full App & AI Log") }
    }
}
