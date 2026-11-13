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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.therapylocal.app.BuildConfig
import com.therapylocal.app.R
import com.therapylocal.app.config.LiveCueSettings
import com.therapylocal.app.config.SummaryFormatSetting
import com.therapylocal.app.ui.data.SessionSummaryRepository
import com.therapylocal.app.config.liveCueSettings
import com.therapylocal.app.config.saveLiveCueSettings
import com.therapylocal.app.embed.Engines
import com.therapylocal.app.engine.EngineRole
import com.therapylocal.app.engine.EngineState
import com.therapylocal.app.engine.HostView
import com.therapylocal.app.models.BackendGuard
import com.therapylocal.app.models.Backends
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
import com.therapylocal.app.ui.logic.ClientDisambiguation
import com.therapylocal.app.ui.logic.DisambiguationTarget
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
    onReprocess: () -> Unit,
) {
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findFragmentActivity() }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val bar = rememberChoiceState()
    val list = rememberLazyListState()
    val technicalSheet = rememberSheetState()
    val changePasswordSheet = rememberSheetState()
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
                .recede(listOf(technicalSheet, changePasswordSheet), LocalReducedMotion.current)
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

                item { Group(stringResource(R.string.settings_group_summary_format)) }
                item { SummaryFormat() }

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
                item {
                    Row2(
                        stringResource(R.string.settings_change_password_title),
                        stringResource(R.string.settings_change_password_body, stringResource(R.string.app_name)),
                        stringResource(R.string.settings_change_password_action),
                    ) {
                        haptics.confirm()
                        changePasswordSheet.open()
                    }
                }

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
                item {
                    Spacer(Modifier.height(16.dp))
                    PillButton(
                        text = stringResource(R.string.settings_sign_out),
                        onClick = onLock,
                        color = Palette.Tonal,
                        content = Palette.Ink,
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
                onReprocess = onReprocess,
            )
        }

        PageShift(changePasswordSheet, onBack = { changePasswordSheet.close() }) {
            ChangePasswordSheet(
                sheet = changePasswordSheet,
                onClose = { changePasswordSheet.close() },
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
    onReprocess: () -> Unit,
) {
    val ctx = LocalContext.current
    val haptics = rememberHaptics()
    val bar = rememberChoiceState()
    val list = rememberLazyListState()
    val setup by ModelSetup.progress.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }

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
                    SetupState.WaitingForWifi -> Row2(stringResource(R.string.setup_waiting_wifi_title), stringResource(R.string.setup_waiting_wifi_body), stringResource(R.string.setup_use_mobile_data)) { ModelSetup.askMobileData(ctx) }
                    SetupState.Declined -> Row2(stringResource(R.string.settings_processing_paused), stringResource(R.string.setup_declined_body), stringResource(R.string.settings_processing_try_again)) { ModelSetup.retry(ctx) }
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

            item { Group(stringResource(R.string.settings_group_reprocess)) }
            item { Row2(stringResource(R.string.settings_reprocess_title), stringResource(R.string.settings_reprocess_body), stringResource(R.string.settings_reprocess_action), onReprocess) }

            // A plain functional control (AD-22). Its design, wording and place in onboarding belong to the UX owner.
            item { Group(stringResource(R.string.settings_group_workflow)) }
            item { WorkflowChoice() }
            item { Group(stringResource(R.string.settings_group_memory_model)) }
            item { ModelChoice(notice) }
            item { Group(stringResource(R.string.settings_group_hardware)) }
            item { HardwareStatus(tick, notice) }

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
    // Read so a switch redraws the marks: it arrives as a new configuration, not a new activity (#71), and nothing else here changes.
    LocalConfiguration.current
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
    val save = { prefs.saveLiveCueSettings(k); com.therapylocal.app.embed.Engines.onLiveCueSettingsChanged() }
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

/** The summary format of the vault. Moving the slider shows the example of each stop; leaving it on another one applies it (stale summaries, last sessions written again). */
@Composable
private fun SummaryFormat() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var format by remember { mutableStateOf(SummaryFormatSetting.get(ctx)) }
    SummaryFormatPicker(
        format, { format = it },
        onSettle = { id -> scope.launch(Dispatchers.IO) { SessionSummaryRepository.create(ctx).changeFormat(id) } },
    )
}

/** The language new clients start with. A client's own language is set in the client's profile. */
@Composable
private fun DefaultLanguage() {
    val ctx = LocalContext.current
    var language by remember { mutableStateOf(SpeechLanguages.default(ctx)) }
    LanguagePicker(language, { SpeechLanguages.setDefault(ctx, it); language = it })
}

/** Fast, Quality or the Canary control (docs/AD/AD-22.md). Applies to sessions processed afterwards; the models it needs are fetched if missing. */
@Composable
private fun WorkflowChoice() {
    val ctx = LocalContext.current
    var selected by remember { mutableStateOf(com.therapylocal.app.config.WorkflowSetting.get(ctx)) }
    Column(Modifier.padding(vertical = 10.dp)) {
        Text(stringResource(R.string.settings_workflow_intro), style = Type.BodySmall, color = Palette.InkSoft)
        com.therapylocal.app.config.Workflow.entries.forEach { w ->
            val (title, body) = when (w) {
                com.therapylocal.app.config.Workflow.Fast -> R.string.settings_workflow_fast to R.string.settings_workflow_fast_body
                com.therapylocal.app.config.Workflow.Quality -> R.string.settings_workflow_quality to R.string.settings_workflow_quality_body
                com.therapylocal.app.config.Workflow.QualityCs -> R.string.settings_workflow_quality_cs to R.string.settings_workflow_quality_cs_body
                com.therapylocal.app.config.Workflow.Canary -> R.string.settings_workflow_canary to R.string.settings_workflow_canary_body
            }
            Row(
                Modifier.fillMaxWidth().pressable({
                    if (w != selected) { com.therapylocal.app.config.WorkflowSetting.set(ctx, w); selected = w; ModelSetup.retry(ctx) }
                }, label = stringResource(title), depth = 0.015f).padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(22.dp)) { if (w == selected) Label("●", color = Palette.Ink) else Label("○", color = Palette.InkFaint) }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(title), style = Type.Body, color = Palette.Ink)
                    Text(stringResource(body), style = Type.BodySmall, color = Palette.InkSoft)
                }
            }
        }
    }
    Hairline()
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
                Box(Modifier.width(22.dp)) { if (v == variant) Label("●", color = Palette.Ink) else Label("○", color = Palette.InkFaint) }
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
 * What each model runs on here (NPU, GPU or CPU) and what every part of the phone has shown: proven on this device, failed, or turned off
 * after it kept failing or the app was stopped while using it. Read from [Backends] each time the screen is shown.
 */
@Composable
private fun HardwareStatus(resumeTick: Int, notice: NoticeState) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var confirmReset by remember { mutableStateOf(false) }
    val models = remember(resumeTick, tick) { Backends.overview(ctx) }
    Column(Modifier.padding(vertical = 10.dp)) {
        Text(stringResource(R.string.settings_hardware_intro, stringResource(R.string.app_name)), style = Type.BodySmall, color = Palette.InkSoft)
        if (models.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.settings_hardware_none), style = Type.BodySmall, color = Palette.InkFaint)
        }
        models.forEach { m -> HardwareModel(m) }
        val unexpected by produceState(0, resumeTick, tick) {
            value = withContext(Dispatchers.IO) { runCatching { com.therapylocal.app.models.ModelsInventory.scan(ctx).unknown.size }.getOrDefault(0) }
        }
        if (unexpected > 0) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.settings_models_unexpected, unexpected), style = Type.BodySmall, color = Palette.InkFaint)
        }
        if (models.isNotEmpty()) TextAction(stringResource(R.string.settings_hardware_reset), { confirmReset = true }, color = Palette.InkSoft)
        EngineProcesses(resumeTick)
    }
    Hairline()
    if (confirmReset) ConfirmDialog(
        stringResource(R.string.settings_hardware_reset_title), stringResource(R.string.settings_hardware_reset_body), stringResource(R.string.settings_hardware_reset_confirm),
        onConfirm = {
            Backends.reset(ctx)
            confirmReset = false
            tick++
            notice.show(ctx.getString(R.string.settings_hardware_reset_done))
        },
        onDismiss = { confirmReset = false }, destructive = false,
    )
}

/**
 * One row per engine process, read from [Engines.state] (published by the connections). Showing it never binds a host. The memory figure is
 * refreshed once per resume, off the main thread, and only for a host that is already bound.
 */
@Composable
private fun EngineProcesses(resumeTick: Int) {
    val views by Engines.state.collectAsState()
    LaunchedEffect(resumeTick) {
        for (role in listOf(EngineRole.LIVE, EngineRole.BATCH)) runCatching { Engines.refreshPss(role) }
    }
    // In-process builds publish nothing: there is no process to show.
    if (views.isEmpty() && com.therapylocal.app.config.EngineConfig.IN_PROCESS) return
    Spacer(Modifier.height(20.dp))
    Text(stringResource(R.string.settings_engine_title), style = Type.Body, color = Palette.Ink)
    Text(stringResource(R.string.settings_engine_intro), style = Type.BodySmall, color = Palette.InkSoft)
    listOf(EngineRole.LIVE to R.string.settings_engine_role_live, EngineRole.BATCH to R.string.settings_engine_role_batch).forEach { (role, title) ->
        EngineRow(stringResource(title), views[role])
    }
}

@Composable
private fun EngineRow(title: String, v: HostView?) {
    val ctx = LocalContext.current
    val shown = EngineState.shown(v, Engines.tuning.stormDeaths)
    val running = shown == EngineState.Shown.RUNNING
    val state = stringResource(
        when (shown) {
            EngineState.Shown.STOPPED -> R.string.settings_engine_state_stopped
            EngineState.Shown.STARTING -> R.string.settings_engine_state_starting
            EngineState.Shown.RUNNING -> R.string.settings_engine_state_running
            EngineState.Shown.STOPPING -> R.string.settings_engine_state_stopping
            EngineState.Shown.RESTARTING -> R.string.settings_engine_state_restarting
            EngineState.Shown.UNAVAILABLE -> R.string.settings_engine_state_unavailable
        },
    )
    Column(Modifier.fillMaxWidth().padding(top = 14.dp).semantics(mergeDescendants = true) {}) {
        Text(title, style = Type.Body, color = Palette.Ink)
        Text(state, style = Type.BodySmall, color = if (shown == EngineState.Shown.UNAVAILABLE) Palette.Ink else Palette.InkSoft)
        if (v != null && (running || shown == EngineState.Shown.STARTING)) {
            val up = ((System.nanoTime() / 1_000_000 - v.sinceMs) / 1000).coerceAtLeast(0)
            Text(stringResource(R.string.settings_engine_process, v.pid, v.gen, android.text.format.DateUtils.formatElapsedTime(up)), style = Type.BodySmall, color = Palette.InkFaint)
        }
        if (v != null && running) {
            Text(
                if (v.loaded.isEmpty()) stringResource(R.string.settings_engine_loaded_none) else stringResource(R.string.settings_engine_loaded, v.loaded.joinToString(", ") { it.replace("@", " on ") }),
                style = Type.BodySmall, color = Palette.InkFaint,
            )
            v.pssKb?.let { Text(stringResource(R.string.settings_engine_memory, android.text.format.Formatter.formatShortFileSize(ctx, it * 1024)), style = Type.BodySmall, color = Palette.InkFaint) }
        }
        if (v != null && v.deaths > 0) Text(stringResource(R.string.settings_engine_deaths, v.deaths), style = Type.BodySmall, color = Palette.InkFaint)
        v?.lastExit?.let { e ->
            val kind = stringResource(
                when (e.kind) {
                    BackendGuard.ExitKind.KILLED_FOR_MEMORY -> R.string.settings_engine_exit_memory
                    BackendGuard.ExitKind.NATIVE_CRASH -> R.string.settings_engine_exit_native
                    BackendGuard.ExitKind.CRASH -> R.string.settings_engine_exit_crash
                    BackendGuard.ExitKind.ANR -> R.string.settings_engine_exit_anr
                    BackendGuard.ExitKind.BENIGN -> R.string.settings_engine_exit_benign
                    BackendGuard.ExitKind.UNKNOWN -> R.string.settings_engine_exit_unknown
                },
            )
            val at = if (v.lastExitAtMs > 0) android.text.format.DateUtils.getRelativeTimeSpanString(minOf(v.lastExitAtMs, System.currentTimeMillis())).toString() else ""
            Text(stringResource(R.string.settings_engine_last_exit, kind, at).trim().trimEnd('·', ' '), style = Type.BodySmall, color = Palette.InkFaint)
        }
    }
}

@Composable
private fun HardwareModel(m: BackendGuard.ModelView) {
    val base = m.model.substringBefore('+')
    val audio = m.model.endsWith("+audio")
    val title = remember(base) { runCatching { ModelRegistry.byId(base).title.substringBefore(" (") }.getOrDefault(base) }
    val role = when {
        base.startsWith("gemma") -> R.string.settings_hardware_role_llm
        base == ModelRegistry.EMBED.id -> if (audio) R.string.settings_hardware_role_embed_audio else R.string.settings_hardware_role_embed
        else -> R.string.settings_hardware_role_asr
    }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).semantics(mergeDescendants = true) {}) {
        Text(title, style = Type.Body, color = Palette.Ink)
        Text(stringResource(role), style = Type.BodySmall, color = Palette.InkFaint)
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                m.selected == null -> stringResource(R.string.settings_hardware_not_loaded)
                m.selected == BackendGuard.NONE -> stringResource(R.string.settings_hardware_none_loaded)
                // A clock set back would read "in 3 hours".
                m.selectedAt != null -> stringResource(R.string.settings_hardware_running_on_since, m.selected, android.text.format.DateUtils.getRelativeTimeSpanString(minOf(m.selectedAt, System.currentTimeMillis())).toString())
                else -> stringResource(R.string.settings_hardware_running_on, m.selected)
            },
            style = Type.BodySmall, color = Palette.Ink,
        )
        m.backends.forEach { (backend, v) ->
            val (record, skip) = v
            val state = when (record?.state) {
                BackendGuard.State.PROVEN -> stringResource(R.string.settings_hardware_state_proven)
                BackendGuard.State.ATTEMPTING -> stringResource(R.string.settings_hardware_state_attempting)
                BackendGuard.State.FAILED -> stringResource(R.string.settings_hardware_state_failed)
                BackendGuard.State.DIED -> stringResource(R.string.settings_hardware_state_died)
                BackendGuard.State.BLOCKED -> stringResource(R.string.settings_hardware_state_blocked)
                null -> stringResource(R.string.settings_hardware_state_skipped)
            }
            val strikes = record?.strikes?.takeIf { it > 0 }?.let { " · " + stringResource(R.string.settings_hardware_failures, it) } ?: ""
            // Said in words, not only in colour.
            val current = if (backend == m.selected) " · " + stringResource(R.string.settings_hardware_current) else ""
            // The technical reason stays in English, as in the logs: it is what a support request quotes.
            val detail = when {
                skip != null && record?.state != BackendGuard.State.PROVEN -> skip.reason
                record != null && record.state != BackendGuard.State.PROVEN -> record.reason
                else -> null
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Label(backend, color = if (backend == m.selected) Palette.Ink else Palette.InkFaint, modifier = Modifier.widthIn(min = 44.dp).padding(end = 8.dp))
                Column(Modifier.weight(1f)) {
                    Text(state + current + strikes, style = Type.BodySmall, color = if (record?.state == BackendGuard.State.BLOCKED) Palette.Ink else Palette.InkSoft)
                    if (detail != null) Text(detail, style = Type.BodySmall.copy(fontSize = 12.sp, lineHeight = 15.sp), color = Palette.InkFaint)
                }
            }
        }
    }
}

/**
 * Changing the password from Settings while authenticated.
 */
@Composable
fun ChangePasswordSheet(
    sheet: SheetState,
    onClose: () -> Unit,
    notice: NoticeState,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val bar = rememberChoiceState()
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val longEnough = password.length >= MIN_PASSWORD
    val matches = password == confirm
    val valid = longEnough && matches && confirm.isNotEmpty()
    val (shake, kick) = rememberShake()
    val focus = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(320); runCatching { focus.requestFocus() } }

    val save: () -> Unit = save@{
        if (busy) return@save
        if (!valid) {
            haptics.reject()
            scope.launch { kick() }
            error = if (!longEnough) ctx.resources.getQuantityString(R.plurals.gate_pw_min, MIN_PASSWORD, MIN_PASSWORD) else ctx.getString(R.string.gate_pw_mismatch)
            return@save
        }
        busy = true
        val chars = password.toCharArray()
        scope.launch {
            try {
                withContext(Dispatchers.Default) { Vault.changePassword(ctx, chars) }
                haptics.confirm()
                notice.show(ctx.getString(R.string.settings_password_changed))
                onClose()
            } catch (e: Exception) {
                error = ctx.getString(R.string.gate_change_pw_failed)
                busy = false
            } finally {
                chars.fill('\u0000')
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .axisDrag(sheetDrag(sheet, bar, onBack = onClose, onForward = { save() }, canForward = { valid && !busy }))
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 26.dp, end = 26.dp, top = 24.dp, bottom = hintsClearance())
        ) {
            Label(stringResource(R.string.settings_change_password_kicker))
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.settings_change_password_title), style = Type.Hero, color = Palette.Ink)
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.settings_change_password_body, stringResource(R.string.app_name)), style = Type.Body, color = Palette.InkSoft)
            Spacer(Modifier.height(30.dp))
            Column(Modifier.offset { IntOffset(shake.value.roundToInt(), 0) }) {
                PaperField(
                    password,
                    { password = it; error = null },
                    pluralStringResource(R.plurals.gate_pw_hint, MIN_PASSWORD, MIN_PASSWORD),
                    secret = true,
                    enabled = !busy,
                    imeAction = ImeAction.Next,
                    modifier = Modifier.focusRequester(focus),
                )
                Spacer(Modifier.height(22.dp))
                PaperField(
                    confirm,
                    { confirm = it; error = null },
                    stringResource(R.string.gate_pw_confirm_hint),
                    secret = true,
                    enabled = !busy,
                    error = confirm.isNotEmpty() && !matches && confirm.length >= password.length,
                    onIme = save,
                )
            }
            Spacer(Modifier.height(12.dp))
            Label(error ?: if (busy) stringResource(R.string.gate_securing) else " ", color = if (error != null) Palette.Rec else Palette.InkSoft)
        }
        SwipeHints(
            bar,
            back = Way(stringResource(R.string.settings_go_back)) { onClose() },
            forward = Way(stringResource(R.string.settings_change_password_save), enabled = !busy) { save() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/**
 * Adding a client: a first name and, if you like, a surname, shortened automatically to distinguish clients. The Add button under the form
 * is always there; the swipe from right to left (and its hint, which only shows after a pause) is a shortcut to the same action.
 */
@Composable
fun AddClientSheet(
    sheet: SheetState,
    onCancel: () -> Unit,
    onAdd: (String, String, SpeechLanguage) -> Unit,
    existingClients: List<DisambiguationTarget> = emptyList(),
) {
    val ctx = LocalContext.current
    val haptics = rememberHaptics()
    val bar = rememberChoiceState()
    var first by remember { mutableStateOf("") }
    var last by remember { mutableStateOf("") }
    var language by remember { mutableStateOf(SpeechLanguages.default(ctx)) }
    val name = ClientDisambiguation.preview(first, last, existingClients)
    val error = ClientInput.validate(ctx, first)
    val focus = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(320); runCatching { focus.requestFocus() } }
    val add = { if (error == null) onAdd(first.trim(), last.trim(), language) else haptics.reject() }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().axisDrag(sheetDrag(sheet, bar, onBack = onCancel, onForward = { add() }, canForward = { error == null }))) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 26.dp, end = 26.dp, top = 24.dp, bottom = hintsClearance())) {
            Text(if (name.isBlank()) stringResource(R.string.settings_addclient_label) else name, style = Type.Hero, color = Palette.Ink, maxLines = 2)
            Spacer(Modifier.height(36.dp))
            PaperField(first, { first = it }, stringResource(R.string.settings_addclient_first_name), imeAction = ImeAction.Next, modifier = Modifier.focusRequester(focus), capitalization = KeyboardCapitalization.Words)
            Spacer(Modifier.height(22.dp))
            PaperField(last, { last = it }, stringResource(R.string.settings_addclient_surname), onIme = add, capitalization = KeyboardCapitalization.Words)
            Spacer(Modifier.height(30.dp))
            Label(stringResource(R.string.speech_speaks))
            LanguagePicker(language, { language = it })
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.settings_addclient_note, stringResource(R.string.app_name)), style = Type.BodySmall, color = Palette.InkFaint)
            Spacer(Modifier.height(30.dp))
            // A gesture is never the only way: the hint below shows only after a pause and takes taps only while it shows, so the action has a
            // button that is always there (#74).
            PillButton(
                text = if (error == null) stringResource(R.string.settings_addclient_button_named, name) else stringResource(R.string.settings_addclient_button),
                onClick = add,
                enabled = error == null,
            )
        }
        SwipeHints(
            bar,
            back = Way(stringResource(R.string.settings_go_back)) { onCancel() },
            forward = Way(if (error == null) stringResource(R.string.settings_addclient_add_named, name) else stringResource(R.string.settings_addclient_add), enabled = error == null) { add() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
fun AddClientSheet(
    sheet: SheetState,
    onCancel: () -> Unit,
    onAdd: (String, SpeechLanguage) -> Unit,
) {
    AddClientSheet(
        sheet = sheet,
        onCancel = onCancel,
        onAdd = { first, last, lang ->
            val preview = ClientDisambiguation.preview(first, last, emptyList())
            onAdd(preview, lang)
        },
    )
}
