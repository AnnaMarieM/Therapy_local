package com.therapylocal.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Screen(viewModel) } } }
    }
}

@Composable
private fun Screen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Backend: ${state.backend.ifEmpty { "-" }}", style = MaterialTheme.typography.labelLarge)
        Text(
            state.transcript.ifEmpty { "Hold the button and speak" },
            Modifier.weight(1f).fillMaxWidth(),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (state.transcribing) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.lastTranscribeMs?.let { if (!state.transcribing) Text("Transcribed in ${it / 1000.0} s") }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        HoldToRecord(
            enabled = state.stage == Stage.Ready && !state.transcribing,
            recording = state.recording,
            onPress = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    vm.startRecording()
                } else {
                    permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                }
            },
            onRelease = vm::stopRecording,
        )
    }

    if (state.stage != Stage.Ready) ModelDialog(state, onRetry = vm::retry)
}

@Composable
private fun HoldToRecord(enabled: Boolean, recording: Boolean, onPress: () -> Unit, onRelease: () -> Unit) {
    Box(
        Modifier
            .size(120.dp)
            .clip(CircleShape)
            .pointerInput(enabled) {
                if (enabled) detectTapGestures(onPress = { onPress(); tryAwaitRelease(); onRelease() })
            }
            .then(Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            Modifier.fillMaxSize(),
            color = if (recording) Color.Red else if (enabled) MaterialTheme.colorScheme.primary else Color.Gray,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(if (recording) "Recording" else "Hold", color = Color.White)
            }
        }
    }
}

@Composable
private fun ModelDialog(state: UiState, onRetry: () -> Unit) {
    val mb = { b: Long -> b / (1024 * 1024) }
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(
                when (state.stage) {
                    Stage.Downloading -> "Downloading Gemma 4 E2B"
                    Stage.Verifying -> "Verifying download"
                    Stage.LoadingEngine -> "Loading model"
                    Stage.Failed -> "Something went wrong"
                    Stage.Ready -> ""
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state.stage) {
                    Stage.Downloading, Stage.Verifying -> {
                        LinearProgressIndicator(
                            progress = { if (state.total > 0) state.downloaded.toFloat() / state.total else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("${mb(state.downloaded)} / ${mb(state.total)} MB")
                    }
                    Stage.LoadingEngine -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Trying NPU, then GPU, then CPU")
                    }
                    Stage.Failed -> Text(state.error ?: "Unknown error")
                    Stage.Ready -> Unit
                }
            }
        },
        confirmButton = { if (state.stage == Stage.Failed) TextButton(onClick = onRetry) { Text("Retry") } },
    )
}
