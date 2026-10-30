package com.therapylocal.app.ui.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.therapylocal.app.BuildConfig
import com.therapylocal.app.config.LiveCueSettings
import com.therapylocal.app.config.liveCueSettings
import com.therapylocal.app.config.saveLiveCueSettings
import com.therapylocal.app.models.GemmaVariant
import com.therapylocal.app.models.ModelRegistry
import com.therapylocal.app.models.ModelSetup
import com.therapylocal.app.models.SetupState
import com.therapylocal.app.ui.data.ClientInput
import com.therapylocal.app.ui.design.Choice
import com.therapylocal.app.ui.design.ChoiceBar
import com.therapylocal.app.ui.design.Palette
import com.therapylocal.app.ui.design.Type
import com.therapylocal.app.ui.design.rememberChoiceState
import com.therapylocal.app.ui.design.rememberHaptics
import com.therapylocal.app.ui.motion.SheetState
import com.therapylocal.app.ui.motion.axisDrag
import com.therapylocal.app.ui.security.authenticateWithBiometric
import com.therapylocal.app.ui.security.findFragmentActivity
import com.therapylocal.app.vault.BiometricAvailability
import com.therapylocal.app.vault.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Settings, grown out of the gear. A plain list: the few things worth changing, then the knobs for the curious. */
@Composable
fun SettingsSheet(
    sheet: SheetState,
    failedSessions: Int,
    onRetryFailed: () -> Unit,
    onLock: () -> Unit,
    onDeveloper: (() -> Unit)?,
    onClose: () -> Unit,
    notice: NoticeState,
) {
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findFragmentActivity() }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val bar = rememberChoiceState()
    val list = rememberLazyListState()
    val setup by ModelSetup.progress.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    var bio by remember(tick) { mutableStateOf(Vault.isBiometricEnabled(ctx)) }
    val availability = remember(tick) { Vault.biometricAvailability(ctx) }
    val notifications = remember(tick) { ctx.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled() }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().axisDrag(sheetDrag(sheet, bar, list, onClose, onClose, haptics))) {
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(horizontal = 26.dp, vertical = 24.dp)) {
            item {
                Label("MindVault")
                Spacer(Modifier.height(14.dp))
                Text("Settings", style = Type.Hero, color = Palette.Ink)
            }

            item { Group("Privacy") }
            item {
                Toggle(
                    "Unlock with fingerprint",
                    when (availability) {
                        BiometricAvailability.Available -> "Instead of typing the password. The password always works."
                        BiometricAvailability.NotEnrolled -> "Add a fingerprint or face in the phone's settings first."
                        BiometricAvailability.Unsupported -> "This phone has no suitable sensor."
                    },
                    checked = bio,
                    enabled = availability == BiometricAvailability.Available || bio,
                ) { on ->
                    if (!on) { Vault.disableBiometric(ctx); bio = false }
                    else if (activity != null) {
                        try {
                            authenticateWithBiometric(
                                activity, "Unlock with fingerprint", "Confirm to turn it on", "Cancel", Vault.biometricEnrollCipher(),
                                onSuccess = { c -> scope.launch { withContext(Dispatchers.Default) { Vault.completeBiometricEnroll(ctx, c) }; bio = true } },
                                onError = { if (it.isNotEmpty()) notice.show(it) },
                            )
                        } catch (e: Exception) {
                            notice.show(e.message ?: "It could not be turned on.")
                        }
                    }
                }
            }
            item { Row2("Lock now", "Closes MindVault. The password or a fingerprint opens it again.", "Lock", onLock) }

            item { Group("Notifications") }
            item {
                if (notifications) Row2("Allowed", "You are told when a session has been processed.", null) {}
                else Row2("Off", "You will not be told when a session has been processed.", "Turn on") { openAppSettings(ctx) }
            }

            item { Group("Processing") }
            item {
                when (setup.state) {
                    SetupState.Ready -> Row2("Ready", "Everything runs on this phone.", null) {}
                    SetupState.Failed -> Row2("Setup paused", setup.message ?: "Check the connection and try again.", "Try again") { ModelSetup.retry(ctx) }
                    else -> {
                        val eta = setup.etaSeconds?.let { " · ${ModelSetup.formatEta(it)} left" } ?: ""
                        Column(Modifier.padding(vertical = 16.dp)) {
                            Text("Getting ready", style = Type.Title.copy(fontSize = 22.sp), color = Palette.Ink)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (setup.totalBytes > 0) "${ModelSetup.formatBytes(setup.doneBytes)} of ${ModelSetup.formatBytes(setup.totalBytes)}$eta. Sessions you record meanwhile are processed when this finishes."
                                else "Checking what is needed.",
                                style = Type.BodySmall, color = Palette.InkSoft,
                            )
                            Spacer(Modifier.height(12.dp))
                            Progress(if (setup.totalBytes > 0) setup.fraction else null)
                        }
                        Hairline()
                    }
                }
            }
            if (failedSessions > 0) item {
                Row2("${failedSessions} ${if (failedSessions == 1) "session" else "sessions"} could not be processed", "Try them again; nothing was lost.", "Retry", onRetryFailed)
            }

            item { Group("Live cues") }
            item { CueKnobs() }
            item { Group("Memory model") }
            item { ModelChoice(notice) }

            if (onDeveloper != null) {
                item { Group("Developer") }
                item { Row2("Developer tools", "Logs, raw chunks and diagnostics.", "Open", onDeveloper) }
            }

            item { Group("About") }
            item {
                Text(
                    "MindVault ${BuildConfig.VERSION_NAME}. Type set in Fraunces, Geist and Geist Mono, under the SIL Open Font License.",
                    style = Type.BodySmall, color = Palette.InkFaint, modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
        PillButton("Done", { haptics.confirm(); onClose() }, Modifier.padding(14.dp), color = Palette.Tonal, content = Palette.Ink)
    }
}

private fun openAppSettings(ctx: Context) {
    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun Group(title: String) {
    Column(Modifier.padding(top = 34.dp, bottom = 2.dp)) {
        Label(title)
        Spacer(Modifier.height(10.dp))
        Hairline()
    }
}

@Composable
private fun Row2(title: String, body: String, action: String?, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = Type.Title.copy(fontSize = 22.sp, lineHeight = 27.sp), color = Palette.Ink)
            Spacer(Modifier.height(4.dp))
            Text(body, style = Type.BodySmall, color = Palette.InkSoft)
        }
        if (action != null) TextAction(action, onAction)
    }
    Hairline()
}

@Composable
private fun Toggle(title: String, body: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = Type.Title.copy(fontSize = 22.sp, lineHeight = 27.sp), color = Palette.Ink)
            Spacer(Modifier.height(4.dp))
            Text(body, style = Type.BodySmall, color = Palette.InkSoft)
        }
        Switch(
            checked, onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.Ink, checkedThumbColor = Palette.Paper, uncheckedTrackColor = Palette.Tonal, uncheckedThumbColor = Palette.InkSoft, uncheckedBorderColor = Palette.Hairline),
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
    Hairline()
}

/** A hairline progress track. [fraction] null shows a slow sweep (unknown amount). */
@Composable
private fun Progress(fraction: Float?) {
    Box(Modifier.fillMaxWidth().height(3.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)).background(Palette.Hairline)) {
        Box(Modifier.fillMaxWidth(fraction ?: 0.25f).height(3.dp).background(Palette.Ink))
    }
}

@Composable
private fun CueKnobs() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var k by remember { mutableStateOf(prefs.liveCueSettings()) }
    val save = { prefs.saveLiveCueSettings(k) }
    Column(Modifier.padding(vertical = 10.dp)) {
        Text("How cues are picked. Changes apply from the next session.", style = Type.BodySmall, color = Palette.InkSoft)
        Knob("Listens back", "${k.lookbackS} s", k.lookbackS.toFloat(), LiveCueSettings.LOOKBACK_S.first.toFloat()..LiveCueSettings.LOOKBACK_S.last.toFloat(), 0,
            { k = LiveCueSettings.of(k.intervalMs, it.roundToInt(), k.smoothing, k.scoreFloor) }, save)
        Knob("Checks every", String.format(Locale.US, "%.1f s", k.intervalMs / 1000f), k.intervalMs.toFloat(), LiveCueSettings.INTERVAL_MS.first.toFloat()..LiveCueSettings.INTERVAL_MS.last.toFloat(),
            ((LiveCueSettings.INTERVAL_MS.last - LiveCueSettings.INTERVAL_MS.first) / 500 - 1).toInt(),
            { k = LiveCueSettings.of(it.roundToLong(), k.lookbackS, k.smoothing, k.scoreFloor) }, save)
        Knob("Weight of the newest check", String.format(Locale.US, "%.2f", k.smoothing), k.smoothing, LiveCueSettings.SMOOTHING, 18,
            { k = LiveCueSettings.of(k.intervalMs, k.lookbackS, it, k.scoreFloor) }, save)
        Knob("Minimum relevance", String.format(Locale.US, "%.2f", k.scoreFloor), k.scoreFloor, LiveCueSettings.SCORE_FLOOR, 19,
            { k = LiveCueSettings.of(k.intervalMs, k.lookbackS, k.smoothing, it) }, save)
        TextAction("Reset to defaults", { k = LiveCueSettings.DEFAULT; save() }, color = Palette.InkSoft)
    }
    Hairline()
}

@Composable
private fun Knob(title: String, value: String, position: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit, onDone: () -> Unit) {
    Column(Modifier.padding(top = 14.dp)) {
        Row {
            Text(title, style = Type.BodySmall, color = Palette.Ink, modifier = Modifier.weight(1f))
            Label(value, color = Palette.Ink)
        }
        Slider(
            position, onChange, valueRange = range, steps = steps, onValueChangeFinished = onDone,
            colors = SliderDefaults.colors(thumbColor = Palette.Ink, activeTrackColor = Palette.Ink, inactiveTrackColor = Palette.Hairline, activeTickColor = Palette.Paper, inactiveTickColor = Palette.InkFaint),
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}

@Composable
private fun ModelChoice(notice: NoticeState) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val variant = remember(tick) { ModelSetup.gemmaVariant(ctx) }
    val unused = remember(tick) { ModelSetup.unusedGemma(ctx) }
    val suggested = remember { ModelSetup.suggestedGemma(ctx) }
    var confirm by remember { mutableStateOf<GemmaVariant?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    Column(Modifier.padding(vertical = 10.dp)) {
        Text("The model that reads sessions. Suggested for this phone: ${suggested.title}.", style = Type.BodySmall, color = Palette.InkSoft)
        GemmaVariant.entries.forEach { v ->
            val spec = ModelRegistry.gemma(v)
            Row(
                Modifier.fillMaxWidth().pressable({ if (v != variant) { if (spec.missingBytes(ctx) > 0) confirm = v else { ModelSetup.selectGemma(ctx, v); tick++ } } }, label = v.title, depth = 0.015f)
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(22.dp)) { if (v == variant) Label("●", color = Palette.Ink) else Label("○", color = Palette.InkFaint) }
                Text(v.title, style = Type.Body, color = Palette.Ink, modifier = Modifier.weight(1f))
                Label(ModelSetup.formatBytes(spec.totalBytes))
            }
        }
        if (unused != null) TextAction("Remove the unused one (${unused.title}, ${ModelSetup.formatBytes(unused.totalBytes)})", { confirmRemove = true }, color = Palette.InkSoft)
    }
    Hairline()
    if (confirmRemove && unused != null) ConfirmDialog(
        "Remove ${unused.title}?", "${ModelSetup.formatBytes(unused.totalBytes)} is freed. Choosing it again downloads it again.", "Remove",
        onConfirm = { ModelSetup.removeUnusedGemma(ctx); confirmRemove = false; tick++; notice.show("${unused.title} removed") }, onDismiss = { confirmRemove = false },
    )
    confirm?.let { v ->
        val spec = ModelRegistry.gemma(v)
        ConfirmDialog(
            "Download ${v.title}?", "${ModelSetup.formatBytes(spec.missingBytes(ctx))} is downloaded. Sessions wait for it, then are processed with it.", "Download",
            onConfirm = { ModelSetup.selectGemma(ctx, v); confirm = null; tick++ }, onDismiss = { confirm = null }, destructive = false,
        )
    }
}

/**
 * Adding a client: a first name and, if you like, a surname, kept as an initial ("George M."). Only what is needed to recognise them.
 */
@Composable
fun AddClientSheet(sheet: SheetState, onCancel: () -> Unit, onAdd: (String) -> Unit) {
    val haptics = rememberHaptics()
    val bar = rememberChoiceState()
    var first by remember { mutableStateOf("") }
    var last by remember { mutableStateOf("") }
    val name = displayName(first, last)
    val error = ClientInput.validate(name)
    val focus = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(320); runCatching { focus.requestFocus() } }
    val add = { if (error == null) onAdd(name) else haptics.reject() }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().axisDrag(sheetDrag(sheet, bar, null, onCancel, { add() }, haptics, allowRight = { error == null }))) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 26.dp, vertical = 24.dp)) {
            Label("New client")
            Spacer(Modifier.height(14.dp))
            Text(if (name.isBlank()) "Who is it?" else name, style = Type.Hero, color = if (name.isBlank()) Palette.InkFaint else Palette.Ink, maxLines = 2)
            Spacer(Modifier.height(36.dp))
            PaperField(first, { first = it }, "First name", imeAction = ImeAction.Next, modifier = Modifier.focusRequester(focus), capitalization = KeyboardCapitalization.Words)
            Spacer(Modifier.height(22.dp))
            PaperField(last, { last = it }, "Surname · only the initial is kept", onIme = add, capitalization = KeyboardCapitalization.Words)
            Spacer(Modifier.height(14.dp))
            Text("Keep names minimal. Everything else MindVault learns comes from the sessions.", style = Type.BodySmall, color = Palette.InkFaint)
        }
        ChoiceBar(
            bar,
            left = Choice("Cancel", "Release to cancel", Palette.Tonal, Palette.Ink) { onCancel() },
            right = Choice("Add", "Release to add", Palette.Ink, Palette.Paper, enabled = error == null) { add() },
            modifier = Modifier.padding(14.dp),
        )
    }
}

/** "george", "miller" -> "George M."; the surname is optional. */
internal fun displayName(first: String, last: String): String {
    val f = first.trim().replaceFirstChar { it.titlecase(Locale.getDefault()) }
    val l = last.trim().firstOrNull()?.uppercaseChar()
    return if (l == null) f else "$f $l."
}
