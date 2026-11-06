package com.therapylocal.app.ui.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import com.therapylocal.app.config.DisplayConfig
import com.therapylocal.app.i18n.LocaleStore
import com.therapylocal.app.i18n.Languages
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import com.therapylocal.app.R
import com.therapylocal.app.config.LiveCueSettings
import com.therapylocal.app.config.liveCueSettings
import com.therapylocal.app.config.saveLiveCueSettings
import com.therapylocal.app.models.GemmaVariant
import com.therapylocal.app.models.SpeechLanguage
import com.therapylocal.app.models.SpeechLanguages
import com.therapylocal.app.models.ModelRegistry
import com.therapylocal.app.models.ModelSetup
import com.therapylocal.app.models.SetupState
import com.therapylocal.app.ui.data.ClientInput
import com.therapylocal.app.ui.design.Choice
import com.therapylocal.app.ui.design.ChoiceBar
import com.therapylocal.app.ui.design.SwipeHints
import com.therapylocal.app.ui.design.hintsClearance
import com.therapylocal.app.ui.design.Way
import com.therapylocal.app.ui.design.backChoice
import com.therapylocal.app.ui.design.Palette
import com.therapylocal.app.ui.design.Type
import com.therapylocal.app.ui.design.rememberChoiceState
import com.therapylocal.app.ui.design.rememberHaptics
import com.therapylocal.app.ui.design.LocalReducedMotion
import com.therapylocal.app.ui.motion.PageShift
import com.therapylocal.app.ui.motion.SheetState
import com.therapylocal.app.ui.motion.axisDrag
import com.therapylocal.app.ui.motion.recede
import com.therapylocal.app.ui.motion.rememberSheetState
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

/** Settings, grown out of the gear. Everyday options for the therapist, with a dedicated door to technical tuning. */
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
    val technicalSheet = rememberSheetState()
    val keepScreenAwake by DisplayConfig.keepScreenAwake.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    var bio by remember(tick) { mutableStateOf(Vault.isBiometricEnabled(ctx)) }
    val availability = remember(tick) { Vault.biometricAvailability(ctx) }
    val notifications = remember(tick) { ctx.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled() }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .recede(listOf(technicalSheet), LocalReducedMotion.current)
                .statusBarsPadding()
                .navigationBarsPadding()
                .axisDrag(sheetDrag(sheet, bar, onBack = onClose))
        ) {
            LazyColumn(
                Modifier.weight(1f),
                state = list,
                contentPadding = PaddingValues(start = 26.dp, end = 26.dp, top = 24.dp, bottom = hintsClearance())
            ) {
                item {
                    Label(stringResource(R.string.app_name))
                    Spacer(Modifier.height(14.dp))
                    Text(stringResource(R.string.settings_title), style = Type.Hero, color = Palette.Ink)
                }

                item { Group(stringResource(R.string.settings_group_language)) }
                item { LanguageChoice(activity) }

                item { Group(stringResource(R.string.speech_default_group)) }
                item { DefaultLanguage() }

                item { Group(stringResource(R.string.settings_group_display)) }
                item {
                    Toggle(
                        stringResource(R.string.settings_keep_screen_on_title),
                        stringResource(R.string.settings_keep_screen_on_body, stringResource(R.string.app_name)),
                        checked = keepScreenAwake,
                        enabled = true,
                    ) { on ->
                        DisplayConfig.setKeepScreenAwake(ctx, on)
                    }
                }

                item { Group(stringResource(R.string.settings_group_privacy)) }
                item {
                    Toggle(
                        stringResource(R.string.settings_biometric_title),
                        stringResource(
                            when (availability) {
                                BiometricAvailability.Available -> R.string.settings_biometric_body_available
                                BiometricAvailability.NotEnrolled -> R.string.settings_biometric_body_not_enrolled
                                BiometricAvailability.Unsupported -> R.string.settings_biometric_body_unsupported
                            },
                        ),
                        checked = bio,
                        enabled = availability == BiometricAvailability.Available || bio,
                    ) { on ->
                        if (!on) { Vault.disableBiometric(ctx); bio = false }
                        else if (activity != null) {
                            try {
                                authenticateWithBiometric(
                                    activity, ctx.getString(R.string.settings_biometric_title), ctx.getString(R.string.settings_biometric_confirm), ctx.getString(R.string.settings_biometric_cancel), Vault.biometricEnrollCipher(),
                                    onSuccess = { c -> scope.launch { withContext(Dispatchers.Default) { Vault.completeBiometricEnroll(ctx, c) }; bio = true } },
                                    onError = { if (it.isNotEmpty()) notice.show(it) },
                                )
                            } catch (e: Exception) {
                                notice.show(e.message ?: ctx.getString(R.string.settings_biometric_failed))
                            }
                        }
                    }
                }
                item { Row2(stringResource(R.string.settings_lock_title), stringResource(R.string.settings_lock_body, stringResource(R.string.app_name)), stringResource(R.string.settings_lock_action), onLock) }

                item { Group(stringResource(R.string.settings_group_notifications)) }
                item {
                    if (notifications) Row2(stringResource(R.string.settings_notifications_allowed), stringResource(R.string.settings_notifications_allowed_body), null) {}
                    else Row2(stringResource(R.string.settings_notifications_off), stringResource(R.string.settings_notifications_off_body), stringResource(R.string.settings_notifications_turn_on)) { openAppSettings(ctx) }
                }

                if (failedSessions > 0) item {
                    Row2(pluralStringResource(R.plurals.settings_failed_sessions, failedSessions, failedSessions), stringResource(R.string.settings_failed_body), stringResource(R.string.settings_failed_retry), onRetryFailed)
                }

                item { Group(stringResource(R.string.settings_group_technical)) }
                item {
                    Row2(
                        stringResource(R.string.settings_technical_title),
                        stringResource(R.string.settings_technical_body),
                        stringResource(R.string.settings_technical_open),
                    ) {
                        haptics.confirm()
                        technicalSheet.open()
                    }
                }

                item { Group(stringResource(R.string.settings_group_about)) }
                item {
                    Text(
                        stringResource(R.string.settings_about_text, stringResource(R.string.app_name), BuildConfig.VERSION_NAME),
                        style = Type.BodySmall, color = Palette.InkFaint, modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
            SwipeHints(bar, back = Way(stringResource(R.string.settings_go_back)) { haptics.confirm(); onClose() }, forward = null, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp))
        }

        PageShift(technicalSheet, onBack = { technicalSheet.close() }) {
            TechnicalSettingsSheet(
                sheet = technicalSheet,
                failedSessions = failedSessions,
                onRetryFailed = onRetryFailed,
                onDeveloper = onDeveloper,
                onClose = { technicalSheet.close() },
                notice = notice,
            )
        }
    }
}

/** The knobs and dials for technical users: on-device model setup, memory model selection, cue hyperparameters, and developer diagnostics. */
@Composable
private fun TechnicalSettingsSheet(
    sheet: SheetState,
    failedSessions: Int,
    onRetryFailed: () -> Unit,
    onDeveloper: (() -> Unit)?,
    onClose: () -> Unit,
    notice: NoticeState,
) {
    val ctx = LocalContext.current
    val haptics = rememberHaptics()
    val bar = rememberChoiceState()
    val list = rememberLazyListState()
    val setup by ModelSetup.progress.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().axisDrag(sheetDrag(sheet, bar, onBack = onClose))) {
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(start = 26.dp, end = 26.dp, top = 24.dp, bottom = hintsClearance())) {
            item {
                Label(stringResource(R.string.settings_title))
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.settings_technical_title), style = Type.Hero, color = Palette.Ink)
            }

            item { Group(stringResource(R.string.settings_group_processing)) }
            item {
                when (setup.state) {
                    SetupState.Ready -> Row2(stringResource(R.string.settings_processing_ready), stringResource(R.string.settings_processing_ready_body), null) {}
                    SetupState.Failed -> Row2(stringResource(R.string.settings_processing_paused), setup.message ?: stringResource(R.string.settings_processing_paused_body), stringResource(R.string.settings_processing_try_again)) { ModelSetup.retry(ctx) }
                    else -> {
                        val eta = setup.etaSeconds?.let { stringResource(R.string.settings_processing_eta_left, ModelSetup.formatEta(ctx, it)) } ?: ""
                        Column(Modifier.padding(vertical = 16.dp)) {
                            Text(stringResource(R.string.settings_processing_getting_ready), style = Type.Title.copy(fontSize = 22.sp), color = Palette.Ink)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (setup.totalBytes > 0) stringResource(R.string.settings_processing_downloading, ModelSetup.formatBytes(ctx, setup.doneBytes), ModelSetup.formatBytes(ctx, setup.totalBytes), eta)
                                else stringResource(R.string.settings_processing_checking),
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
                Row2(pluralStringResource(R.plurals.settings_failed_sessions, failedSessions, failedSessions), stringResource(R.string.settings_failed_body), stringResource(R.string.settings_failed_retry), onRetryFailed)
            }

            item { Group(stringResource(R.string.settings_group_memory_model)) }
            item { ModelChoice(notice) }

            item { Group(stringResource(R.string.settings_group_live_cues)) }
            item { CueKnobs() }

            if (onDeveloper != null) {
                item { Group(stringResource(R.string.settings_group_developer)) }
                item { Row2(stringResource(R.string.settings_developer_title), stringResource(R.string.settings_developer_body), stringResource(R.string.settings_developer_open), onDeveloper) }
            }
        }
        SwipeHints(bar, back = Way(stringResource(R.string.settings_go_back)) { haptics.confirm(); onClose() }, forward = null, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp))
    }
}

private fun openAppSettings(ctx: Context) {
    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun LanguageChoice(activity: androidx.fragment.app.FragmentActivity?) {
    val selected = LocaleStore.current(LocalContext.current).tag
    Languages.all.forEach { language ->
        val on = language.tag == selected
        Row(
            Modifier.fillMaxWidth()
                .pressable({ if (!on && activity != null) LocaleStore.choose(activity, language.tag) }, role = Role.RadioButton)
                .semantics { this.selected = on }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(language.nativeName, style = Type.Title.copy(fontSize = 22.sp, lineHeight = 27.sp), color = if (on) Palette.Ink else Palette.InkSoft, modifier = Modifier.weight(1f))
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (on) Palette.Ink else Palette.Paper).border(1.dp, if (on) Palette.Ink else Palette.Hairline, CircleShape))
        }
        Hairline()
    }
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
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (action != null) Modifier.pressable(onAction) else Modifier)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        Text(stringResource(R.string.settings_cues_intro), style = Type.BodySmall, color = Palette.InkSoft)
        Knob(stringResource(R.string.settings_cues_lookback), stringResource(R.string.settings_value_seconds, k.lookbackS), k.lookbackS.toFloat(), LiveCueSettings.LOOKBACK_S.first.toFloat()..LiveCueSettings.LOOKBACK_S.last.toFloat(), 0,
            { k = LiveCueSettings.of(k.intervalMs, it.roundToInt(), k.smoothing, k.scoreFloor) }, save)
        Knob(stringResource(R.string.settings_cues_interval), stringResource(R.string.settings_value_seconds_decimal, k.intervalMs / 1000f), k.intervalMs.toFloat(), LiveCueSettings.INTERVAL_MS.first.toFloat()..LiveCueSettings.INTERVAL_MS.last.toFloat(),
            ((LiveCueSettings.INTERVAL_MS.last - LiveCueSettings.INTERVAL_MS.first) / 500 - 1).toInt(),
            { k = LiveCueSettings.of(it.roundToLong(), k.lookbackS, k.smoothing, k.scoreFloor) }, save)
        Knob(stringResource(R.string.settings_cues_smoothing), stringResource(R.string.settings_value_decimal, k.smoothing), k.smoothing, LiveCueSettings.SMOOTHING, 18,
            { k = LiveCueSettings.of(k.intervalMs, k.lookbackS, it, k.scoreFloor) }, save)
        Knob(stringResource(R.string.settings_cues_floor), stringResource(R.string.settings_value_decimal, k.scoreFloor), k.scoreFloor, LiveCueSettings.SCORE_FLOOR, 19,
            { k = LiveCueSettings.of(k.intervalMs, k.lookbackS, k.smoothing, it) }, save)
        TextAction(stringResource(R.string.settings_cues_reset), { k = LiveCueSettings.DEFAULT; save() }, color = Palette.InkSoft)
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

/** The language new clients start with. A client's own language is set in the client's profile. */
@Composable
private fun DefaultLanguage() {
    val ctx = LocalContext.current
    var language by remember { mutableStateOf(SpeechLanguages.default(ctx)) }
    Column(Modifier.padding(top = 10.dp)) {
        Text(stringResource(R.string.speech_default_body), style = Type.BodySmall, color = Palette.InkSoft)
        LanguagePicker(language, { SpeechLanguages.setDefault(ctx, it); language = it })
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
        Text(stringResource(R.string.settings_model_intro, suggested.title), style = Type.BodySmall, color = Palette.InkSoft)
        GemmaVariant.entries.forEach { v ->
            val spec = ModelRegistry.gemma(v)
            Row(
                Modifier.fillMaxWidth().pressable({ if (v != variant) { if (spec.missingBytes(ctx) > 0) confirm = v else { ModelSetup.selectGemma(ctx, v); tick++ } } }, label = v.title, depth = 0.015f)
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(22.dp)) { if (v == variant) Label("â—", color = Palette.Ink) else Label("â—‹", color = Palette.InkFaint) }
                Text(v.title, style = Type.Body, color = Palette.Ink, modifier = Modifier.weight(1f))
                Label(ModelSetup.formatBytes(ctx, spec.totalBytes))
            }
        }
        if (unused != null) TextAction(stringResource(R.string.settings_model_remove_unused, unused.title, ModelSetup.formatBytes(ctx, unused.totalBytes)), { confirmRemove = true }, color = Palette.InkSoft)
    }
    Hairline()
    if (confirmRemove && unused != null) ConfirmDialog(
        stringResource(R.string.settings_model_remove_title, unused.title), stringResource(R.string.settings_model_remove_body, ModelSetup.formatBytes(ctx, unused.totalBytes)), stringResource(R.string.settings_model_remove_confirm),
        onConfirm = { ModelSetup.removeUnusedGemma(ctx); confirmRemove = false; tick++; notice.show(ctx.getString(R.string.settings_model_removed, unused.title)) }, onDismiss = { confirmRemove = false },
    )
    confirm?.let { v ->
        val spec = ModelRegistry.gemma(v)
        ConfirmDialog(
            stringResource(R.string.settings_model_download_title, v.title), stringResource(R.string.settings_model_download_body, ModelSetup.formatBytes(ctx, spec.missingBytes(ctx))), stringResource(R.string.settings_model_download_confirm),
            onConfirm = { ModelSetup.selectGemma(ctx, v); confirm = null; tick++ }, onDismiss = { confirm = null }, destructive = false,
        )
    }
}

/**
 * Adding a client: a first name and, if you like, a surname, kept as an initial ("George M."). Only what is needed to recognise them.
 */
@Composable
fun AddClientSheet(sheet: SheetState, onCancel: () -> Unit, onAdd: (String, SpeechLanguage) -> Unit) {
    val ctx = LocalContext.current
    val haptics = rememberHaptics()
    val bar = rememberChoiceState()
    var first by remember { mutableStateOf("") }
    var last by remember { mutableStateOf("") }
    var language by remember { mutableStateOf(SpeechLanguages.default(ctx)) }
    val name = displayName(first, last)
    val error = ClientInput.validate(ctx, name)
    val focus = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(320); runCatching { focus.requestFocus() } }
    val add = { if (error == null) onAdd(name, language) else haptics.reject() }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().axisDrag(sheetDrag(sheet, bar, onBack = onCancel, onForward = { add() }, canForward = { error == null }))) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 26.dp, end = 26.dp, top = 24.dp, bottom = hintsClearance())) {
            Label(stringResource(R.string.settings_addclient_label))
            Spacer(Modifier.height(14.dp))
            Text(if (name.isBlank()) stringResource(R.string.settings_addclient_who) else name, style = Type.Hero, color = if (name.isBlank()) Palette.InkFaint else Palette.Ink, maxLines = 2)
            Spacer(Modifier.height(36.dp))
            PaperField(first, { first = it }, stringResource(R.string.settings_addclient_first_name), imeAction = ImeAction.Next, modifier = Modifier.focusRequester(focus), capitalization = KeyboardCapitalization.Words)
            Spacer(Modifier.height(22.dp))
            PaperField(last, { last = it }, stringResource(R.string.settings_addclient_surname), onIme = add, capitalization = KeyboardCapitalization.Words)
            Spacer(Modifier.height(30.dp))
            Label(stringResource(R.string.speech_speaks))
            LanguagePicker(language, { language = it })
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.settings_addclient_note, stringResource(R.string.app_name)), style = Type.BodySmall, color = Palette.InkFaint)
        }
        SwipeHints(
            bar,
            back = Way(stringResource(R.string.settings_go_back)) { onCancel() },
            forward = Way(if (error == null) stringResource(R.string.settings_addclient_add_named, name) else stringResource(R.string.settings_addclient_add), enabled = error == null) { add() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/** "george", "miller" -> "George M."; the surname is optional. */
internal fun displayName(first: String, last: String): String {
    val f = first.trim().replaceFirstChar { it.titlecase(Locale.getDefault()) }
    val l = last.trim().firstOrNull()?.uppercaseChar()
    return if (l == null) f else "$f $l."
}
