package com.jarves.mh.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.material.icons.filled.Delete
import android.media.MediaScannerConnection
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import com.jarves.mh.runtime.LocalLlmService
import com.jarves.mh.runtime.LocalModelManager
import com.jarves.mh.runtime.LocalModelPreset
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

private val cardShape = RoundedCornerShape(20.dp)
private val pillShape = RoundedCornerShape(50)

data class BgDownloadState(
    val activeId: String? = null,
    val progressPct: Int = 0,
    val statusMsg: String = "",
)

object LocalBgDownloader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _state = MutableStateFlow(BgDownloadState())
    val state = _state.asStateFlow()

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = BgDownloadState(statusMsg = "Download cancelled")
    }

    fun start(context: Context, url: String, filename: String, id: String, onReady: (File) -> Unit) {
        job?.cancel()
        job = scope.launch {
            val dir = LocalModelManager.modelsDir(context)
            val part = File(dir, "$filename.part")
            val finalFile = File(dir, filename)
            val urls = listOf(url, url.replace("https://huggingface.co/", "https://hf-mirror.com/"))
            var ok = false
            for (u in urls) {
                if (!isActive) break
                try {
                    val existing = if (part.exists()) part.length() else 0L
                    val conn = (URL(u).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 30000
                        if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
                    }
                    val code = conn.responseCode
                    val append = code == 206
                    val total = if (append) existing + conn.contentLengthLong else conn.contentLengthLong
                    var downloaded = if (append) existing else 0L
                    conn.inputStream.use { input ->
                        FileOutputStream(part, append).use { out ->
                            val buf = ByteArray(1024 * 1024)
                            var lastUi = 0L
                            while (isActive) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                downloaded += n
                                val now = System.currentTimeMillis()
                                if (now - lastUi > 350) {
                                    lastUi = now
                                    val pct = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 99) else 0
                                    _state.value = BgDownloadState(id, pct, "${downloaded / (1024 * 1024)} MB")
                                }
                            }
                        }
                    }
                    if (isActive) {
                        if (finalFile.exists()) finalFile.delete()
                        part.renameTo(finalFile)
                        MediaScannerConnection.scanFile(context, arrayOf(finalFile.absolutePath), null, null)
                        ok = true
                        break
                    }
                } catch (_: Exception) {
                }
            }
            if (ok) {
                _state.value = BgDownloadState(null, 100, "Saved to Downloads")
                withContext(Dispatchers.Main) { onReady(finalFile) }
            } else if (isActive) {
                _state.value = BgDownloadState(null, 0, "Download failed; tap to retry")
            }
        }
    }
}

data class EngineLiveUi(val alive: Boolean = false, val model: String? = null, val starting: Boolean = false)

object LocalEngineLiveState {
    val state = MutableStateFlow(EngineLiveUi())
    suspend fun refresh(startingOverride: Boolean? = null) {
        val ok = probeHealth()
        val model = if (ok) probeRunningModel() else null
        val prev = state.value
        state.value = EngineLiveUi(
            alive = ok,
            model = model ?: if (ok) prev.model else null,
            starting = if (ok) false else (startingOverride ?: prev.starting),
        )
    }
}

private fun detectQuant(name: String): String {
    val m = Regex("(I?Q\\d+_[A-Z0-9_]+|F16|BF16)", RegexOption.IGNORE_CASE).find(name)
    return m?.value?.uppercase() ?: "GGUF"
}

private fun ramFitLabel(bytes: Long, ramGb: Double): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    val needed = gb * 1.35 + 1.2
    return if (ramGb >= needed + 0.8) "Fits RAM" else if (ramGb >= needed - 0.5) "Tight Fit" else "Heavy"
}

private suspend fun probeHealth(): Boolean = withContext(Dispatchers.IO) {
    try {
        val c = URL("http://127.0.0.1:8080/health").openConnection() as HttpURLConnection
        c.connectTimeout = 800
        c.readTimeout = 800
        c.responseCode == 200
    } catch (_: Exception) {
        false
    }
}

private suspend fun probeRunningModel(): String? = withContext(Dispatchers.IO) {
    try {
        val c = URL("http://127.0.0.1:8080/v1/models").openConnection() as HttpURLConnection
        c.connectTimeout = 800
        c.readTimeout = 800
        if (c.responseCode != 200) return@withContext null
        val body = c.inputStream.bufferedReader().readText()
        val id = org.json.JSONObject(body).optJSONArray("data")?.optJSONObject(0)?.optString("id").orEmpty()
        id.substringAfterLast('/').removeSuffix(".gguf").ifBlank { null }
    } catch (_: Exception) {
        null
    }
}

private fun applyLocalModel(
    context: Context,
    provider: ProviderProfile,
    onSaveProvider: (ProviderProfile, String) -> Unit,
    scope: CoroutineScope,
    file: File,
    onStatus: (String) -> Unit,
) {
    onSaveProvider(
        provider.copy(
            kind = ProviderKind.LOCAL_AI,
            baseUrl = "http://127.0.0.1:8080/v1",
            model = file.absolutePath,
            dshApi = "openai-completions",
        ),
        "local-offline",
    )
    scope.launch { onStatus(LocalModelManager.tryAutoStartServer(context, file)) }
}

@Composable
fun LocalAiCard(
    context: Context,
    provider: ProviderProfile,
    onSaveProvider: (ProviderProfile, String) -> Unit,
    scope: CoroutineScope,
) {
    val dlState by LocalBgDownloader.state.collectAsState()
    var filter by rememberSaveable { mutableStateOf("All") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var hfResults by remember { mutableStateOf<List<LocalModelPreset>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    var localStatusMsg by remember { mutableStateOf("") }
    var serverAlive by remember { mutableStateOf(false) }
    var runningModelName by remember { mutableStateOf<String?>(null) }
    var filesTick by remember { mutableIntStateOf(0) }
    val clipboard = LocalClipboardManager.current

    val ramGb = remember { LocalModelManager.getTotalRamGb(context) }
    val freeGb = remember(filesTick) { LocalModelManager.getFreeStorageGb(context) }
    val downloadedFiles = remember(filesTick, dlState.activeId, dlState.progressPct) {
        LocalModelManager.listDownloadedModels(context)
    }

    LaunchedEffect(dlState.activeId, localStatusMsg, filesTick) {
        serverAlive = probeHealth()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            localStatusMsg = "Importing AI model to Downloads..."
            val importedRes = withContext(Dispatchers.IO) { runCatching {
                var name = "imported-model.gguf"
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
                }
                val dst = File(LocalModelManager.modelsDir(context), name)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dst.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
                }
                MediaScannerConnection.scanFile(context, arrayOf(dst.absolutePath), null, null)
                dst
            } }
            filesTick++
            importedRes.onSuccess { dst -> applyLocalModel(context, provider, onSaveProvider, scope, dst) { localStatusMsg = it } }.onFailure { localStatusMsg = "Import failed: ${it.message}" }
        }
    }

    val allModels = remember(hfResults, filter, downloadedFiles) {
        val base = if (hfResults.isNotEmpty()) hfResults else LocalModelManager.presets
        base.filter { m ->
            when (filter) {
                "Downloaded" -> downloadedFiles.any { it.name.equals(m.filename, true) }
                "Censored" -> !m.uncensored
                "Uncensored" -> m.uncensored
                "Fast 4-6GB" -> m.sizeGb <= 4.0
                else -> true
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            Text("Local AI Gallery", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                String.format("%.1f GB RAM · %.1f GB free · %s", ramGb, freeGb, if (serverAlive) "Running: ${runningModelName ?: "Offline AI"}" else "Engine Idle"),
                fontSize = 12.sp,
                color = if (serverAlive) Color(0xFF81C995) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                shape = cardShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.weight(1f).clickable { picker.launch(arrayOf("*/*")) },
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Import AI", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color(0xFFA8C7FA))
                    Text("Load .gguf & auto-start", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Surface(
                shape = cardShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.weight(1f).clickable {
                    if (serverAlive) {
                        LocalLlmService.stop(context)
                        serverAlive = false
                        localStatusMsg = "Local AI engine stopped."
                    } else {
                        val active = downloadedFiles.firstOrNull { it.absolutePath == provider.model } ?: downloadedFiles.firstOrNull()
                        if (active != null) {
                            applyLocalModel(context, provider, onSaveProvider, scope, active) { localStatusMsg = it }
                        } else {
                            localStatusMsg = "Download or import a model first."
                        }
                    }
                },
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        if (serverAlive) "Stop Engine" else "Start Engine",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = if (serverAlive) Color(0xFFF28B82) else Color(0xFF81C995),
                    )
                    Text(
                        if (serverAlive) "127.0.0.1:8080 · 12K q8_0" else "Tap to start offline AI",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        LocalChatCard(scope = scope, engineOnline = serverAlive)

        Surface(
            shape = cardShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mi Band 10 / Vela QuickApp Skills", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("Project-scoped skill installer", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(LocalModelManager.miBandSkillCommand()))
                    localStatusMsg = "Copied Mi Band 10 skill installer!"
                }) { Text("Copy") }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("All", "Downloaded", "Censored", "Uncensored", "Fast 4-6GB").forEach { tag ->
                FilterChip(selected = filter == tag, onClick = { filter = tag }, label = { Text(tag, fontSize = 12.sp) }, shape = pillShape)
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search Hugging Face (e.g. Qwen3.5, Gemma 4)...", fontSize = 12.sp) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    searching = true
                    scope.launch {
                        hfResults = runCatching { LocalModelManager.searchHuggingFaceGguf(searchQuery) }.getOrDefault(emptyList())
                        searching = false
                    }
                },
                shape = pillShape,
            ) { Text(if (searching) "..." else "Search") }
        }

        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            Button(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
                    }
                },
                shape = pillShape,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Grant All Files Access (Required for Downloads)") }
        }
        if (localStatusMsg.isNotEmpty()) {
            Text(localStatusMsg, fontSize = 12.sp, color = Color(0xFFA8C7FA))
        }

        allModels.forEach { preset ->
            val targetFile = File(LocalModelManager.modelsDir(context), preset.filename)
            val isDownloaded = targetFile.exists() && targetFile.length() > 10_000_000L
            val isDownloading = dlState.activeId == preset.id
            val isExpanded = expandedId == preset.id
            val (compat, compatArgb) = LocalModelManager.compatibilityLabel(ramGb, preset)

            Surface(
                shape = cardShape,
                color = Color(0xFF101726),
                border = BorderStroke(1.dp, if (isDownloaded) Color(0xFF38BDF8).copy(alpha = 0.6f) else Color(0xFF28354D)),
                modifier = Modifier.fillMaxWidth().animateContentSize(spring(stiffness = Spring.StiffnessMedium, dampingRatio = Spring.DampingRatioNoBouncy)),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { expandedId = if (isExpanded) null else preset.id },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(preset.title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (preset.uncensored) Color(0xFF5C2B29) else Color(0xFF2B3E50),
                                ) {
                                    Text(
                                        if (preset.uncensored) "Uncensored" else "Standard",
                                        fontSize = 10.sp,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            Text("${preset.sizeLabel} · $compat", fontSize = 12.sp, color = Color(compatArgb))
                            Text(preset.reviewNote, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
                    }

                    if (isDownloading) {
                        LinearProgressIndicator(progress = { dlState.progressPct / 100f }, modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("${dlState.progressPct}% · ${dlState.statusMsg}", fontSize = 11.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { LocalBgDownloader.cancel() }) { Text("Cancel", color = Color(0xFFF28B82)) }
                        }
                    } else if (isDownloaded) {
                        Button(
                            onClick = { applyLocalModel(context, provider, onSaveProvider, scope, targetFile) { localStatusMsg = it } },
                            shape = pillShape,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Use Model") }
                    } else {
                        Button(
                            onClick = {
                                LocalBgDownloader.start(context, preset.downloadUrl, preset.filename, preset.id) { file ->
                                    filesTick++
                                    applyLocalModel(context, provider, onSaveProvider, scope, file) { localStatusMsg = it }
                                }
                            },
                            shape = pillShape,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(String.format("Download (%.2f GB)", preset.sizeGb)) }
                    }

                    AnimatedVisibility(visible = isExpanded, enter = fadeIn(tween(150)) + expandVertically(), exit = fadeOut(tween(150)) + shrinkVertically()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("File: Download/MobileHarnessModels/${preset.filename}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (isDownloaded && !isDownloading) {
                                OutlinedButton(
                                    onClick = {
                                        LocalBgDownloader.start(context, preset.downloadUrl, preset.filename, preset.id) { file ->
                                            filesTick++
                                            applyLocalModel(context, provider, onSaveProvider, scope, file) { localStatusMsg = it }
                                        }
                                    },
                                    shape = pillShape,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Re-download & Use") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LocalAiBottomPanel(
    context: Context,
    activeModel: String,
    onSelectModel: (String) -> Unit,
    scope: CoroutineScope,
) {
    var filesTick by remember { mutableIntStateOf(0) }
    var statusMsg by remember { mutableStateOf("") }
    var serverAlive by remember { mutableStateOf(false) }
    var runningModelName by remember { mutableStateOf<String?>(null) }
    val startInteraction = remember { MutableInteractionSource() }
    val startPressed by startInteraction.collectIsPressedAsState()
    val startScale by animateFloatAsState(if (startPressed) 0.97f else 1f, spring(stiffness = Spring.StiffnessHigh), label = "startScale")
    val selectInteraction = remember { MutableInteractionSource() }
    val selectPressed by selectInteraction.collectIsPressedAsState()
    val selectScale by animateFloatAsState(if (selectPressed) 0.97f else 1f, spring(stiffness = Spring.StiffnessHigh), label = "selectScale")
    val ramGb = remember { LocalModelManager.getTotalRamGb(context) }
    val models = remember(filesTick) { LocalModelManager.listDownloadedModels(context) }

    val liveEngine by LocalEngineLiveState.state.collectAsState()
    LaunchedEffect(liveEngine) {
        serverAlive = liveEngine.alive
        runningModelName = liveEngine.model
    }
    LaunchedEffect(Unit) {
        while (true) {
            LocalEngineLiveState.refresh()
            delay(2000)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            statusMsg = "Importing AI model..."
            val importedRes = withContext(Dispatchers.IO) { runCatching {
                var name = "imported-model.gguf"
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
                }
                val dst = File(LocalModelManager.modelsDir(context), name)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dst.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
                }
                MediaScannerConnection.scanFile(context, arrayOf(dst.absolutePath), null, null)
                dst
            } }
            filesTick++
            importedRes.onSuccess { dst ->
                onSelectModel(dst.absolutePath)
                statusMsg = LocalModelManager.tryAutoStartServer(context, dst)
            }.onFailure { statusMsg = "Import failed: ${it.message}" }
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

        Surface(shape = cardShape, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Local Server", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("Private on-device engine · Works without internet", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Model & access", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("+ Import AI") }
        }

        if (models.isEmpty()) {
            Text("No downloaded models yet in Download/MobileHarnessModels/", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            models.forEach { file ->
                val isActive = activeModel == file.absolutePath
                val sizeGb = file.length() / (1024.0 * 1024.0 * 1024.0)
                val quant = detectQuant(file.name)
                val fit = ramFitLabel(file.length(), ramGb)
                Surface(
                    shape = cardShape,
                    color = if (isActive) Color(0xFF1F2B38) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                    border = BorderStroke(1.dp, if (isActive) Color(0xFFA8C7FA) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(file.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(String.format("%.2f GB · %s · %s", sizeGb, quant, fit), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (isActive) {
                            Text("Active", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF81C995))
                        } else {
                            IconButton(onClick = {
                                runCatching { file.delete() }
                                filesTick++
                                statusMsg = "Deleted ${file.name}"
                            }) { Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFF28B82)) }
                            Button(
                                onClick = {
                                    onSelectModel(file.absolutePath)
                                    scope.launch { statusMsg = LocalModelManager.tryAutoStartServer(context, file) }
                                },
                                shape = pillShape,
                            ) { Text("Use") }
                        }
                    }
                }
            }
        }

        Surface(shape = cardShape, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Local Engine & Diagnostics", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "Status: ${if (serverAlive) "Running (${runningModelName ?: "Offline AI"})" else "Stopped"} · Context: 12288 (q8_0) · Threads: 4",
                    fontSize = 11.sp,
                    color = if (serverAlive) Color(0xFF81C995) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        interactionSource = startInteraction,
                        onClick = {
                            scope.launch {
                                val ok = probeHealth()
                                serverAlive = ok
                                statusMsg = if (ok) "Offline AI is ready!" else "Offline AI is not running yet."
                            }
                        },
                        shape = pillShape,
                        modifier = Modifier.weight(1f),
                    ) { Text("Test", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = {
                            if (serverAlive) {
                                LocalLlmService.stop(context)
                                serverAlive = false
                                statusMsg = "Engine stopped."
                            } else {
                                val m = models.firstOrNull { it.absolutePath == activeModel } ?: models.firstOrNull()
                                if (m != null) {
                                    onSelectModel(m.absolutePath)
                                    scope.launch { statusMsg = LocalModelManager.tryAutoStartServer(context, m) }
                                }
                            }
                        },
                        shape = pillShape,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (serverAlive) "Stop" else "Start", fontSize = 12.sp) }
                    Button(
                        onClick = { statusMsg = AppLogExporter.export(context) },
                        shape = pillShape,
                        modifier = Modifier.weight(1.2f),
                    ) { Text("Export Log", fontSize = 12.sp) }
                }
                if (statusMsg.isNotEmpty()) {
                    Text(statusMsg, fontSize = 11.sp, color = Color(0xFFA8C7FA))
                }
            }
        }

        val toolsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                statusMsg = "Restoring Developer Tools..."
                statusMsg = withContext(Dispatchers.IO) {
                    runCatching {
                        val rootfs = File(context.filesDir, "runtime/ubuntu").apply { mkdirs() }
                        val tmp = File(context.cacheDir, "tools-import.tar.gz")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            tmp.outputStream().use { out -> input.copyTo(out, 1024 * 1024) }
                        }
                        val proc = ProcessBuilder("tar", "-xzf", tmp.absolutePath, "-C", rootfs.absolutePath)
                            .redirectErrorStream(true).start()
                        val out = proc.inputStream.bufferedReader().readText()
                        val code = proc.waitFor()
                        tmp.delete()
                        if (code == 0) "Developer Tools restored into PRoot!" else "Restore error ($code): $out"
                    }.getOrElse { "Restore failed: ${it.message}" }
                }
            }
        }

        Surface(shape = cardShape, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Backup & Restore Hub", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("Models stay in Download/MobileHarnessModels. Back up or restore Python, C++ & SDK tools here.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                statusMsg = "Backing up Developer Tools..."
                                statusMsg = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val rootfs = File(context.filesDir, "runtime/ubuntu")
                                        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MobileHarnessToolsBackup").apply { mkdirs() }
                                        val outTar = File(dir, "tools-backup.tar.gz")
                                        val targets = listOf("opt", "root/.local", "root/.android", "var/cache/apt/archives", "usr/local", ".pocket-language-tools-version", ".pocket-core-tools-version", ".pocket-system-upgrade-version", ".pocket-dev-stacks.json")
                                            .filter { File(rootfs, it).exists() }
                                        if (targets.isEmpty()) return@runCatching "No installed tools found to back up yet."
                                        val cmd = mutableListOf("tar", "-czf", outTar.absolutePath, "-C", rootfs.absolutePath)
                                        cmd.addAll(targets)
                                        val code = ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor()
                                        MediaScannerConnection.scanFile(context, arrayOf(outTar.absolutePath), null, null)
                                        if (code == 0) "Tools backed up to Download/MobileHarnessToolsBackup/tools-backup.tar.gz" else "Backup exited with $code"
                                    }.getOrElse { "Backup failed: ${it.message}" }
                                }
                            }
                        },
                        shape = pillShape,
                        modifier = Modifier.weight(1f),
                    ) { Text("Backup Tools", fontSize = 12.sp) }
                    Button(
                        onClick = { toolsPicker.launch(arrayOf("*/*")) },
                        shape = pillShape,
                        modifier = Modifier.weight(1f),
                    ) { Text("Import Tools", fontSize = 12.sp) }
                }
            }
        }

    }
}
