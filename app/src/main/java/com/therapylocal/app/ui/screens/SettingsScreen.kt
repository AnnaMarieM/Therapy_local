package com.therapylocal.app.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.therapylocal.app.config.LiveCueSettings
import com.therapylocal.app.config.liveCueSettings
import com.therapylocal.app.config.saveLiveCueSettings
import com.therapylocal.app.models.GemmaVariant
import com.therapylocal.app.models.ModelRegistry
import com.therapylocal.app.models.ModelSetup
import com.therapylocal.app.models.SetupState
import com.therapylocal.app.ui.components.PillBadge
import com.therapylocal.app.ui.security.authenticateWithBiometric
import com.therapylocal.app.ui.security.findFragmentActivity
import com.therapylocal.app.ui.theme.BorderSubtle
import com.therapylocal.app.ui.theme.CharcoalText
import com.therapylocal.app.ui.theme.DangerBg
import com.therapylocal.app.ui.theme.DangerRed
import com.therapylocal.app.ui.theme.MutedText
import com.therapylocal.app.ui.theme.SageDark
import com.therapylocal.app.ui.theme.SageGreen
import com.therapylocal.app.ui.theme.SageLight
import com.therapylocal.app.ui.theme.SurfaceCard
import com.therapylocal.app.ui.theme.WarmBg
import com.therapylocal.app.vault.BiometricAvailability
import com.therapylocal.app.vault.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun SettingsScreen(
    onLockVault: () -> Unit,
    onOpenDebugMlView: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val scope = rememberCoroutineScope()
    var biometricsEnabled by remember { mutableStateOf(Vault.isBiometricEnabled(context)) }
    val availability = Vault.biometricAvailability(context)
    var autoSurfacePastNotes by remember { mutableStateOf(true) }
    val setup by ModelSetup.progress.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(WarmBg)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Text(
                text = "Settings",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
                color = CharcoalText
            )
            Text(
                text = "Security and preferences",
                fontSize = 12.sp,
                color = MutedText
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                SettingsGroupHeader(title = "SECURITY")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SettingsToggleRow(
                            icon = Icons.Default.Fingerprint,
                            title = "Biometric Quick Unlock",
                            subtitle = when (availability) {
                                BiometricAvailability.Available -> "Fingerprint / Face Unlock"
                                BiometricAvailability.NotEnrolled -> "No fingerprint or face set up on this device"
                                BiometricAvailability.Unsupported -> "Not available on this device"
                            },
                            checked = biometricsEnabled,
                            enabled = availability == BiometricAvailability.Available || biometricsEnabled,
                            onCheckedChange = { on ->
                                if (!on) {
                                    Vault.disableBiometric(context)
                                    biometricsEnabled = false
                                } else if (activity != null) {
                                    try {
                                        authenticateWithBiometric(
                                            activity, "Turn on Biometric Unlock", "Confirm with your fingerprint or face", "Cancel", Vault.biometricEnrollCipher(),
                                            onSuccess = { cipher ->
                                                scope.launch {
                                                    withContext(Dispatchers.Default) { Vault.completeBiometricEnroll(context, cipher) }
                                                    biometricsEnabled = true
                                                }
                                            },
                                            onError = { if (it.isNotEmpty()) Toast.makeText(context, it, Toast.LENGTH_LONG).show() },
                                        )
                                    } catch (e: Exception) {
                                        Toast.makeText(context, e.message ?: "Biometric unlock could not be turned on.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        )
                    }
                }
            }

            item {
                SettingsGroupHeader(title = "SETUP")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (setup.state) {
                            SetupState.Ready -> SettingsRow(
                                icon = Icons.Default.CheckCircle,
                                title = "MindVault is ready",
                                subtitle = "Everything is set up on this phone"
                            )
                            SetupState.Failed -> {
                                SettingsRow(
                                    icon = Icons.Default.Memory,
                                    title = "Setup was interrupted",
                                    subtitle = setup.message ?: "Please check your connection and try again"
                                )
                                TextButton(onClick = { ModelSetup.retry(context) }) { Text("Try again") }
                            }
                            else -> {
                                val eta = setup.etaSeconds?.let { " · ${ModelSetup.formatEta(it)} left" } ?: ""
                                SettingsRow(
                                    icon = Icons.Default.Memory,
                                    title = "Getting ready",
                                    subtitle = if (setup.totalBytes > 0) "${ModelSetup.formatBytes(setup.doneBytes)} of ${ModelSetup.formatBytes(setup.totalBytes)}$eta" else "Checking what is needed"
                                )
                                if (setup.totalBytes > 0) LinearProgressIndicator(progress = { setup.fraction }, modifier = Modifier.fillMaxWidth(), color = SageGreen)
                                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = SageGreen)
                            }
                        }
                    }
                }
            }

            item {
                SettingsGroupHeader(title = "PREFERENCES")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SettingsToggleRow(
                            icon = Icons.Default.Security,
                            title = "Show past notes",
                            subtitle = "Bring up relevant quotes from earlier sessions while you talk",
                            checked = autoSurfacePastNotes,
                            onCheckedChange = { autoSurfacePastNotes = it }
                        )
                    }
                }
            }

            item {
                SettingsGroupHeader(title = "KNOBS FOR NERDS")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LiveCueKnobs()
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BorderSubtle))
                        MemoryModelChoice()
                    }
                }
            }

            if (onOpenDebugMlView != null) {
                item {
                    SettingsGroupHeader(title = "DEVELOPER")
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenDebugMlView() },
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "Developer tools",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = CharcoalText
                                )
                                Text(
                                    text = "Logs, raw chunks and diagnostics",
                                    fontSize = 12.sp,
                                    color = MutedText
                                )
                            }
                            PillBadge(text = "Developer")
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onLockVault,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DangerBg,
                        contentColor = DangerRed
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = "Lock",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/** The live-cue settings, saved as each slider is let go. A session reads them when it starts. */
@Composable
private fun LiveCueKnobs() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var knobs by remember { mutableStateOf(prefs.liveCueSettings()) }
    val save = { prefs.saveLiveCueSettings(knobs) }

    Text(text = "Live cues", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = CharcoalText)
    Text(text = "Changes apply from the next session.", fontSize = 11.sp, color = MutedText)
    KnobSlider(
        title = "Lookback",
        value = "${knobs.lookbackS} s",
        position = knobs.lookbackS.toFloat(),
        range = LiveCueSettings.LOOKBACK_S.first.toFloat()..LiveCueSettings.LOOKBACK_S.last.toFloat(),
        steps = LiveCueSettings.LOOKBACK_S.last - LiveCueSettings.LOOKBACK_S.first - 1,
        onChange = { knobs = LiveCueSettings.of(knobs.intervalMs, it.roundToInt(), knobs.smoothing, knobs.scoreFloor) },
        onDone = save
    )
    KnobSlider(
        title = "Interval",
        value = String.format(Locale.US, "%.1f s", knobs.intervalMs / 1000f),
        position = knobs.intervalMs.toFloat(),
        range = LiveCueSettings.INTERVAL_MS.first.toFloat()..LiveCueSettings.INTERVAL_MS.last.toFloat(),
        steps = ((LiveCueSettings.INTERVAL_MS.last - LiveCueSettings.INTERVAL_MS.first) / INTERVAL_STEP_MS - 1).toInt(),
        onChange = { knobs = LiveCueSettings.of(it.roundToLong(), knobs.lookbackS, knobs.smoothing, knobs.scoreFloor) },
        onDone = save
    )
    KnobSlider(
        title = "Smoothing (weight of the newest tick)",
        value = String.format(Locale.US, "%.2f", knobs.smoothing),
        position = knobs.smoothing,
        range = LiveCueSettings.SMOOTHING,
        steps = 18,
        onChange = { knobs = LiveCueSettings.of(knobs.intervalMs, knobs.lookbackS, it, knobs.scoreFloor) },
        onDone = save
    )
    KnobSlider(
        title = "Score floor",
        value = String.format(Locale.US, "%.2f", knobs.scoreFloor),
        position = knobs.scoreFloor,
        range = LiveCueSettings.SCORE_FLOOR,
        steps = 19,
        onChange = { knobs = LiveCueSettings.of(knobs.intervalMs, knobs.lookbackS, knobs.smoothing, it) },
        onDone = save
    )
    TextButton(onClick = { knobs = LiveCueSettings.DEFAULT; save() }) { Text("Reset to defaults", color = SageDark) }
}

@Composable
private fun KnobSlider(title: String, value: String, position: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit, onDone: () -> Unit) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = title, fontSize = 12.sp, color = CharcoalText)
            Text(text = value, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = SageDark)
        }
        Slider(
            value = position,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            onValueChangeFinished = onDone,
            colors = SliderDefaults.colors(thumbColor = SageGreen, activeTrackColor = SageGreen, inactiveTrackColor = BorderSubtle),
            modifier = Modifier.semantics { contentDescription = title }
        )
    }
}

/** The Gemma build behind the memory (E2B or E4B). A build not on the phone yet is downloaded after a confirmation; the unused one can be removed. */
@Composable
private fun MemoryModelChoice() {
    val context = LocalContext.current
    // Re-read after each change: the choice and the files on disk live outside Compose.
    var tick by remember { mutableIntStateOf(0) }
    val variant = remember(tick) { ModelSetup.gemmaVariant(context) }
    val unused = remember(tick) { ModelSetup.unusedGemma(context) }
    val suggested = remember { ModelSetup.suggestedGemma(context) }
    var confirm by remember { mutableStateOf<GemmaVariant?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }

    Text(text = "Memory model", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = CharcoalText)
    Text(text = "Suggested for this phone: ${suggested.title}", fontSize = 11.sp, color = MutedText)
    GemmaVariant.entries.forEach { v ->
        val spec = ModelRegistry.gemma(v)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = v != variant) {
                    if (spec.missingBytes(context) > 0) confirm = v else { ModelSetup.selectGemma(context, v); tick++ }
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = v == variant, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = SageGreen))
            Text(text = v.title, fontSize = 13.sp, color = CharcoalText, modifier = Modifier.weight(1f).padding(start = 8.dp))
            Text(text = ModelSetup.formatBytes(spec.totalBytes), fontSize = 11.sp, color = MutedText)
        }
    }
    if (unused != null) TextButton(onClick = { confirmRemove = true }) {
        Text("Remove the unused model (${unused.title}, ${ModelSetup.formatBytes(unused.totalBytes)})", color = SageDark)
    }

    if (confirmRemove && unused != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${unused.title}?") },
            text = { Text("${ModelSetup.formatBytes(unused.totalBytes)} will be freed. Choosing it again downloads it again.") },
            confirmButton = {
                TextButton(onClick = { ModelSetup.removeUnusedGemma(context); confirmRemove = false; tick++ }) { Text("Remove", color = DangerRed) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }

    confirm?.let { v ->
        val spec = ModelRegistry.gemma(v)
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Download ${v.title}?") },
            text = { Text("${ModelSetup.formatBytes(spec.missingBytes(context))} will be downloaded. Sessions wait for it, then are processed with it.") },
            confirmButton = { TextButton(onClick = { ModelSetup.selectGemma(context, v); confirm = null; tick++ }) { Text("Download") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

private const val INTERVAL_STEP_MS = 500L

@Composable
fun SettingsGroupHeader(title: String) {
    Text(
        text = title,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = MutedText,
        modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
    )
}

@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(SageLight),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = SageDark,
                modifier = Modifier.size(18.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = CharcoalText
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = MutedText
            )
        }
    }
}

@Composable
fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SageLight),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = SageDark,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = CharcoalText
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = MutedText
                )
            }
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = SageGreen,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = BorderSubtle
            )
        )
    }
}
