package com.therapylocal.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.therapylocal.app.config.ChunkConfig
import com.therapylocal.app.log.AppLog
import com.therapylocal.app.models.ModelSetup
import com.therapylocal.app.models.SetupState
import com.therapylocal.app.memory.DevHarness
import com.therapylocal.app.memory.MemoryService
import com.therapylocal.app.ui.MindVaultApp
import com.therapylocal.app.ui.screens.VaultGate
import com.therapylocal.app.ui.theme.MindVaultTheme
import com.therapylocal.app.vault.Vault

class MainActivity : FragmentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var memoryLabRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        memoryLabRequested = isMemoryLabRequested(intent)
        // The AI files are not patient data: the download starts right away, even before the vault is unlocked.
        ModelSetup.start(applicationContext)
        setContent {
            val context = LocalContext.current
            val unlocked by Vault.unlocked.collectAsStateWithLifecycle()
            var gatePassed by rememberSaveable { mutableStateOf(false) }
            var showDebugScreen by rememberSaveable { mutableStateOf(MainViewModel.skipPrepare) }

            val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
            LaunchedEffect(Unit) {
                val wanted = buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())
            }
            val hasMic = { ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
            val requestMic = { permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }

            LaunchedEffect(unlocked, gatePassed) {
                if (unlocked && gatePassed) {
                    MemoryService.resumePending(applicationContext)
                    if (memoryLabRequested) {
                        memoryLabRequested = false
                        DevHarness.start(applicationContext)
                    }
                }
            }

            if (!unlocked || !gatePassed) {
                MindVaultTheme { VaultGate(onDone = { gatePassed = true }) }
            } else if (showDebugScreen) {
                MaterialTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Diagnostics & Raw ML", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                Button(onClick = { showDebugScreen = false }) {
                                    Text("Back to MindVault")
                                }
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                Screen(viewModel)
                            }
                        }
                    }
                }
            } else {
                MindVaultApp(
                    viewModel = viewModel,
                    hasMicPermission = hasMic,
                    requestMicPermission = requestMic,
                    // The developer harness (Record/Recordings/Memory/Logs) is a Settings menu item in debug builds only.
                    onShowLegacyDebugView = if (BuildConfig.DEBUG) ({ showDebugScreen = true }) else null
                )
            }
        }
    }
}

/** Debug only: `adb shell am start -n .../.MainActivity --ez memory_lab true` starts the queue described by files/memory/job.json once the vault is unlocked. */
private fun isMemoryLabRequested(intent: Intent?): Boolean {
    if (!BuildConfig.DEBUG || intent?.getBooleanExtra("memory_lab", false) != true) return false
    MainViewModel.skipPrepare = true
    return true
}

private const val MEMORY_TAB = 2
private val TABS = listOf("Record", "Recordings", "Memory", "Logs")

private fun fmt(ms: Long) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

@Composable
private fun Screen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    val hasMic = { ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
    val requestMic = { permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            TABS.forEachIndexed { i, t ->
                if (i == tab) Button(onClick = { tab = i }) { Text(t, fontSize = 12.sp) }
                else OutlinedButton(onClick = { tab = i }) { Text(t, fontSize = 12.sp) }
            }
        }
        Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            when (tab) {
                0 -> RecordTab(state, vm, hasMic, requestMic)
                1 -> RecordingsTab(state, vm)
                MEMORY_TAB -> com.therapylocal.app.memory.MemoryTab()
                else -> LogsTab()
            }
        }
    }

    // The Memory tab (and a lab launch, which skips model preloading) never needs the Record models, so no blocking dialog there.
    if (state.phase != Phase.Ready && tab != MEMORY_TAB && !MainViewModel.skipPrepare) PrepareDialog(state, onRetry = vm::retry)
}

@Composable
private fun Chips(label: String, options: List<String>, selected: String, enabled: Boolean, onPick: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, Modifier.padding(end = 4.dp), fontSize = 13.sp)
        options.forEach { o ->
            if (o == selected) Button(onClick = {}, enabled = enabled) { Text(o, fontSize = 12.sp) }
            else OutlinedButton(onClick = { onPick(o) }, enabled = enabled) { Text(o, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun RecordTab(s: UiState, vm: MainViewModel, hasMic: () -> Boolean, requestMic: () -> Unit) {
    val locked = s.recording
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Chips("Transcript", listOf("whisper", "gemma"), s.engine, !locked, vm::setEngine)
        if (s.engine == "whisper") {
            Chips("Whisper", listOf("tiny", "base", "small"), s.whisperSize, !locked, vm::setWhisperSize)
            Chips("Language", listOf("auto", "en", "sk", "cs"), s.language, !locked, vm::setLanguage)
        }
        if (BuildConfig.DEBUG) Chips("Backend", listOf("auto", "npu", "gpu", "cpu"), s.backendOverride, !locked, vm::setBackendOverride)
        Text("Embeddings: embeddinggemma-2 ${s.embedBackend}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text("Chunking: ${ChunkConfig.describe()}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Divider()
        Button(
            onClick = { if (s.recording) vm.stopRecording() else if (hasMic()) vm.startRecording() else requestMic() },
            enabled = s.phase == Phase.Ready,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (s.recording) "Stop recording" else "Start recording") }
        Text(s.status.ifEmpty { "Idle" })
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        s.activeRecordingId?.let { Text("Recording #$it (see Recordings)", fontSize = 12.sp) }
        if (BuildConfig.DEBUG && s.testFiles.isNotEmpty()) {
            Divider()
            Text("Debug: replay a test file through the chunker", fontSize = 12.sp)
            s.testFiles.forEach { f ->
                OutlinedButton(onClick = { vm.debugRecordFile(f) }, enabled = s.phase == Phase.Ready && !s.recording) { Text("Record from $f", fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun RecordingsTab(s: UiState, vm: MainViewModel) {
    var confirmDelete by remember { mutableStateOf<Long?>(null) }
    var confirmAll by remember { mutableStateOf(false) }

    val sel = s.selected
    if (sel != null) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.select(null) }) { Text("Back") }
                OutlinedButton(onClick = { confirmDelete = sel }) { Text("Delete") }
            }
            Text("Recording #$sel, ${s.selectedChunks.size} chunks", style = MaterialTheme.typography.titleSmall)
            Text("Whole recording (overlap words removed)", fontSize = 12.sp)
            Text(s.selectedMerged.ifEmpty { "-" })
            Text("Chunks (${ChunkConfig.describe()})", fontSize = 12.sp)
            s.selectedChunks.forEach { c ->
                Divider()
                Text(
                    "chunk ${c.idx}  ${fmt(c.startMs)}-${fmt(c.endMs)}  overlap ${c.overlapMs / 1000}s",
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                )
                Text(c.text ?: "(not transcribed yet)")
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${s.recordings.size} recordings", Modifier.weight(1f))
                OutlinedButton(onClick = { vm.refreshRecordings() }) { Text("Refresh") }
                OutlinedButton(onClick = { confirmAll = true }, enabled = s.recordings.isNotEmpty()) { Text("Delete all") }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(s.recordings, key = { it.id }) { r ->
                    Divider()
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("#${r.id}  ${r.chunkCount} chunks  ${fmt(r.durationMs)}")
                            Text(r.model, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                        TextButton(onClick = { vm.select(r.id) }) { Text("Open") }
                        TextButton(onClick = { confirmDelete = r.id }) { Text("Delete") }
                    }
                }
            }
        }
    }

    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete recording #$id?") },
            text = { Text("Removes its chunks and transcripts. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { vm.deleteRecording(id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Delete ALL recordings?") },
            text = { Text("Removes every recording and transcript. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { vm.deleteAll(); confirmAll = false }) { Text("Delete all") } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LogsTab() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf(AppLog.tail(300)) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { text = AppLog.tail(300) }) { Text("Refresh", fontSize = 12.sp) }
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copy", fontSize = 12.sp) }
            OutlinedButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, "Share log"))
            }) { Text("Share", fontSize = 12.sp) }
            OutlinedButton(onClick = { AppLog.clear(); text = "" }) { Text("Clear", fontSize = 12.sp) }
        }
        Text("sid ${AppLog.sessionId}   text logging: ${AppLog.fullTextLogging}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState(), reverseScrolling = true)) {
            Text(text, fontSize = 9.sp, fontFamily = FontFamily.Monospace, lineHeight = 11.sp)
        }
    }
}

@Composable
private fun PrepareDialog(s: UiState, onRetry: () -> Unit) {
    val progress by ModelSetup.progress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = {},
        title = { Text(if (progress.state == SetupState.Failed || s.phase == Phase.Failed) "Something went wrong" else "Preparing models") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                when (progress.state) {
                    SetupState.Downloading -> {
                        val eta = progress.etaSeconds?.let { " · ${ModelSetup.formatEta(it)}" } ?: ""
                        Text("Downloading ${ModelSetup.formatBytes(progress.doneBytes)} of ${ModelSetup.formatBytes(progress.totalBytes)}$eta", fontSize = 13.sp)
                        LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                    }
                    SetupState.Failed -> Text(progress.message ?: "Download failed", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    SetupState.Checking -> { Text("Checking files", fontSize = 13.sp); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    SetupState.Ready -> if (s.phase == Phase.Preparing) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(s.prepareMsg, fontSize = 12.sp)
                    }
                }
                if (s.phase == Phase.Failed) Text(s.error ?: "Unknown error", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        },
        confirmButton = {
            if (progress.state == SetupState.Failed) TextButton(onClick = { ModelSetup.retry(context) }) { Text("Retry") }
            else if (s.phase == Phase.Failed) TextButton(onClick = onRetry) { Text("Retry") }
        },
    )
}
