package com.therapylocal.app.ui.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.therapylocal.app.R
import com.therapylocal.app.i18n.Languages
import com.therapylocal.app.i18n.LocaleStore
import com.therapylocal.app.models.SpeechLanguage
import com.therapylocal.app.models.SpeechLanguages
import com.therapylocal.app.ui.design.Choice
import com.therapylocal.app.ui.design.ChoiceBar
import com.therapylocal.app.ui.design.Motion
import com.therapylocal.app.ui.design.Palette
import com.therapylocal.app.ui.design.Type
import com.therapylocal.app.ui.design.paper
import com.therapylocal.app.ui.design.rememberChoiceState
import com.therapylocal.app.ui.design.rememberHaptics
import com.therapylocal.app.ui.motion.Axis
import com.therapylocal.app.ui.motion.Side
import com.therapylocal.app.ui.motion.axisDrag
import com.therapylocal.app.ui.motion.axisDragHandler
import com.therapylocal.app.ui.security.authenticateWithBiometric
import com.therapylocal.app.ui.security.findFragmentActivity
import com.therapylocal.app.vault.BiometricAvailability
import com.therapylocal.app.vault.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val MIN_PASSWORD = 8

/**
 * Everything before the client list: first run (a short welcome, the two permissions, a password, optionally biometrics) or unlocking.
 * Calls [onOpen] once the vault is open.
 */
@Composable
fun Gate(onOpen: () -> Unit) {
    val ctx = LocalContext.current
    var step by rememberSaveable { mutableStateOf(if (Vault.isSetUp(ctx)) GateStep.Unlock else GateStep.Language) }
    // A process restart in the middle of setup lands here with the vault locked: biometrics needs it open, so unlock first.
    if (step == GateStep.Biometric && !Vault.isUnlocked) step = GateStep.Unlock

    Box(Modifier.fillMaxSize().paper()) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                // One page at a time on the same paper: the old one is gone before the new one shows, both moving the way of the swipe.
                val forward = targetState.ordinal > initialState.ordinal
                (slideInHorizontally(tween(320, delayMillis = 110)) { if (forward) it / 5 else -it / 5 } + fadeIn(tween(220, delayMillis = 130))) togetherWith
                    (slideOutHorizontally(tween(160)) { if (forward) -it / 8 else it / 8 } + fadeOut(tween(110)))
            },
            label = "gate",
        ) { s ->
            when (s) {
                GateStep.Language -> ChooseLanguage(onDone = { step = GateStep.Welcome })
                GateStep.Welcome -> Welcome(onBack = { step = GateStep.Language }, onDone = { step = GateStep.Speech })
                GateStep.Speech -> SpeechLanguageStep(onBack = { step = GateStep.Welcome }, onDone = { step = GateStep.Password })
                GateStep.Password -> CreatePassword(onBack = { step = GateStep.Speech }, onCreated = {
                    if (Vault.biometricAvailability(ctx) == BiometricAvailability.Unsupported) onOpen() else step = GateStep.Biometric
                })
                GateStep.Biometric -> OfferBiometric(onDone = onOpen)
                GateStep.Unlock -> Unlock(onOpen = onOpen, onErased = { step = GateStep.Password })
            }
        }
    }
}

enum class GateStep { Language, Welcome, Speech, Password, Biometric, Unlock }

private data class Page(@StringRes val kicker: Int, @StringRes val title: Int, @StringRes val body: Int)

private val PAGES = listOf(
    Page(R.string.app_name, R.string.gate_page1_title, R.string.gate_page1_body),
    Page(R.string.gate_page2_kicker, R.string.gate_page2_title, R.string.gate_page2_body),
    Page(R.string.gate_page3_kicker, R.string.gate_page3_title, R.string.gate_page3_body),
    Page(R.string.gate_page4_kicker, R.string.gate_page4_title, R.string.gate_page4_body),
)

/** The very first screen: the phone's language is preselected; tapping another one switches the whole app at once. */
@Composable
private fun ChooseLanguage(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findFragmentActivity() }
    val bar = rememberChoiceState()
    val selected = LocaleStore.current(ctx).tag
    val pick: (String) -> Unit = { tag -> if (tag != selected && activity != null) LocaleStore.choose(activity, tag) }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 26.dp), verticalArrangement = Arrangement.Center) {
            Spacer(Modifier.height(60.dp))
            Label(stringResource(R.string.gate_language_kicker))
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.gate_language_title), style = Type.Hero.copy(fontSize = Type.Hero.fontSize * 0.78f, lineHeight = Type.Hero.lineHeight * 0.8f), color = Palette.Ink)
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.gate_language_body), style = Type.Body, color = Palette.InkSoft)
            Spacer(Modifier.height(30.dp))
            Languages.all.forEach { language ->
                val on = language.tag == selected
                Row(
                    Modifier.fillMaxWidth()
                        .pressable({ pick(language.tag) }, role = Role.RadioButton)
                        .semantics { this.selected = on }
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(language.nativeName, style = Type.Title, color = if (on) Palette.Ink else Palette.InkSoft, modifier = Modifier.weight(1f))
                    Box(Modifier.size(12.dp).clip(CircleShape).background(if (on) Palette.Ink else Palette.Paper).border(1.dp, if (on) Palette.Ink else Palette.Hairline, CircleShape))
                }
                Hairline()
            }
            Spacer(Modifier.height(40.dp))
        }
        ChoiceBar(
            bar,
            left = null,
            right = Choice(stringResource(R.string.gate_continue), stringResource(R.string.gate_release_continue), Palette.Ink, Palette.Paper) { onDone() },
            modifier = Modifier.padding(14.dp).axisDrag(axisDragHandler(
                accepts = { a, _, _ -> a == Axis.Horizontal },
                onDrag = { _, total, _ -> bar.drag(total) { s -> s == Side.Right } },
                onEnd = { _, _, _ -> if (bar.release() == Side.Right) onDone() },
            )),
        )
    }
}

/** The welcome pages and the permission primers: swipe or use the bar, both always work. */
@Composable
private fun Welcome(onBack: () -> Unit, onDone: () -> Unit) {
    val count = PAGES.size + 1
    val pager = rememberPagerState { count }
    val scope = rememberCoroutineScope()
    val bar = rememberChoiceState()
    val go: (Int) -> Unit = { p -> if (p >= count) onDone() else if (p >= 0) scope.launch { pager.animateScrollToPage(p) } else onBack() }

    // One rule everywhere: a swipe anywhere is the bar's (right to left continues, left to right goes back), so the last page continues
    // into the password the same way the others turn, instead of the pager stopping dead at its end.
    val drag = remember(count) {
        axisDragHandler(
            accepts = { a, _, _ -> a == Axis.Horizontal },
            onDrag = { _, total, _ -> bar.drag(total) { _ -> true } },
            onEnd = { _, _, _ -> when (bar.release()) { Side.Left -> go(pager.currentPage - 1); Side.Right -> go(pager.currentPage + 1); null -> {} } },
        )
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().axisDrag(drag)) {
        Dots(pager.currentPage, count, Modifier.padding(start = 26.dp, top = 26.dp))
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), userScrollEnabled = false) { i ->
            val offset = (pager.currentPage - i + pager.currentPageOffsetFraction)
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - kotlin.math.abs(offset).coerceIn(0f, 1f) * 0.6f; translationX = offset * size.width * 0.25f }) {
                if (i < PAGES.size) WelcomePage(PAGES[i]) else PermissionsPage()
            }
        }
        ChoiceBar(
            bar,
            left = Choice(stringResource(R.string.gate_back), stringResource(R.string.gate_release_back), Palette.Tonal, Palette.Ink) { go(pager.currentPage - 1) },
            right = Choice(stringResource(R.string.gate_continue), stringResource(R.string.gate_release_continue), Palette.Ink, Palette.Paper) { go(pager.currentPage + 1) },
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
        )
    }
}

/** First run, after the welcome pages: the language new clients are assumed to speak. Each client can still have their own. */
@Composable
private fun SpeechLanguageStep(onBack: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val bar = rememberChoiceState()
    var language by rememberSaveable { mutableStateOf(SpeechLanguages.default(ctx).code) }
    val selected = SpeechLanguage.of(language)
    val done = { SpeechLanguages.setDefault(ctx, selected); onDone() }
    val drag = remember {
        axisDragHandler(
            accepts = { a, _, _ -> a == Axis.Horizontal },
            onDrag = { _, total, _ -> bar.drag(total) { true } },
            onEnd = { _, _, _ -> when (bar.release()) { Side.Left -> onBack(); Side.Right -> done(); null -> {} } },
        )
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().axisDrag(drag)) {
        Column(Modifier.weight(1f).padding(horizontal = 26.dp), verticalArrangement = Arrangement.Center) {
            Label(stringResource(R.string.speech_kicker))
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.speech_title), style = Type.Hero.copy(fontSize = Type.Hero.fontSize * 0.78f, lineHeight = Type.Hero.lineHeight * 0.8f), color = Palette.Ink)
            Spacer(Modifier.height(22.dp))
            Text(stringResource(R.string.speech_body), style = Type.Body, color = Palette.InkSoft, modifier = Modifier.width(340.dp))
            Spacer(Modifier.height(28.dp))
            LanguagePicker(selected, { language = it.code })
            Spacer(Modifier.height(80.dp))
        }
        ChoiceBar(
            bar,
            left = Choice(stringResource(R.string.gate_back), stringResource(R.string.gate_release_back), Palette.Tonal, Palette.Ink) { onBack() },
            right = Choice(stringResource(R.string.gate_set_password), stringResource(R.string.gate_release_continue), Palette.Ink, Palette.Paper) { done() },
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun Dots(current: Int, count: Int, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            Box(Modifier.size(if (i == current) 18.dp else 6.dp, 6.dp).clip(CircleShape).background(if (i == current) Palette.Ink else Palette.Hairline))
        }
    }
}

@Composable
private fun WelcomePage(p: Page) {
    Column(Modifier.fillMaxSize().padding(horizontal = 26.dp), verticalArrangement = Arrangement.Center) {
        Label(stringResource(p.kicker))
        Spacer(Modifier.height(18.dp))
        Text(stringResource(p.title), style = Type.Hero.copy(fontSize = Type.Hero.fontSize * 0.78f, lineHeight = Type.Hero.lineHeight * 0.8f), color = Palette.Ink)
        Spacer(Modifier.height(22.dp))
        Text(stringResource(p.body, stringResource(R.string.app_name)), style = Type.Body, color = Palette.InkSoft, modifier = Modifier.width(340.dp))
        Spacer(Modifier.height(80.dp))
    }
}

/** Asks for each permission with its reason in view, never in a batch at launch. Both can be granted later too. */
@Composable
private fun PermissionsPage() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val mic = remember(tick) { granted(ctx, Manifest.permission.RECORD_AUDIO) }
    val notif = remember(tick) { Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    Column(Modifier.fillMaxSize().padding(horizontal = 26.dp), verticalArrangement = Arrangement.Center) {
        Label(stringResource(R.string.gate_perm_kicker))
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.gate_perm_title), style = Type.Hero.copy(fontSize = Type.Hero.fontSize * 0.78f, lineHeight = Type.Hero.lineHeight * 0.8f), color = Palette.Ink)
        Spacer(Modifier.height(30.dp))
        PermissionRow(stringResource(R.string.gate_perm_mic), stringResource(R.string.gate_perm_mic_why), mic) { ask.launch(Manifest.permission.RECORD_AUDIO) }
        Hairline()
        PermissionRow(stringResource(R.string.gate_perm_notif), stringResource(R.string.gate_perm_notif_why), notif) {
            if (Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun PermissionRow(title: String, why: String, granted: Boolean, onAllow: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = Type.Title, color = Palette.Ink)
            Spacer(Modifier.height(4.dp))
            Text(why, style = Type.BodySmall, color = Palette.InkSoft)
        }
        if (granted) Label(stringResource(R.string.gate_allowed), color = Palette.Rev) else TextAction(stringResource(R.string.gate_allow), onAllow)
    }
}

private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

/** A wrong entry shakes the field like a head: a spring kicked sideways, no fixed keyframes. */
@Composable
private fun rememberShake(): Pair<Animatable<Float, *>, suspend () -> Unit> {
    val a = remember { Animatable(0f) }
    return a to { a.snapTo(0f); a.animateTo(0f, Motion.Pop, initialVelocity = 700f) }
}

@Composable
private fun CreatePassword(onBack: () -> Unit, onCreated: () -> Unit) {
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

    val create = create@{
        if (busy) return@create
        if (!valid) {
            haptics.reject()
            scope.launch { kick() }
            error = if (!longEnough) ctx.resources.getQuantityString(R.plurals.gate_pw_min, MIN_PASSWORD, MIN_PASSWORD) else ctx.getString(R.string.gate_pw_mismatch)
            return@create
        }
        busy = true
        val chars = password.toCharArray()
        scope.launch {
            try {
                withContext(Dispatchers.Default) { Vault.create(ctx, chars) }
                haptics.confirm()
                onCreated()
            } catch (e: Exception) {
                error = ctx.getString(R.string.gate_setup_failed)
                busy = false
            } finally {
                chars.fill('\u0000')
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 26.dp), verticalArrangement = Arrangement.Center) {
            Spacer(Modifier.height(60.dp))
            Label(stringResource(R.string.gate_pw_kicker))
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.gate_pw_title), style = Type.Hero.copy(fontSize = Type.Hero.fontSize * 0.78f, lineHeight = Type.Hero.lineHeight * 0.8f), color = Palette.Ink)
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.gate_pw_body), style = Type.Body, color = Palette.InkSoft)
            Spacer(Modifier.height(30.dp))
            Column(Modifier.offset { IntOffset(shake.value.roundToInt(), 0) }) {
                PaperField(password, { password = it; error = null }, pluralStringResource(R.plurals.gate_pw_hint, MIN_PASSWORD, MIN_PASSWORD), secret = true, enabled = !busy, imeAction = ImeAction.Next)
                Spacer(Modifier.height(22.dp))
                PaperField(confirm, { confirm = it; error = null }, stringResource(R.string.gate_pw_confirm_hint), secret = true, enabled = !busy, error = confirm.isNotEmpty() && !matches && confirm.length >= password.length, onIme = create)
            }
            Spacer(Modifier.height(12.dp))
            Label(error ?: if (busy) stringResource(R.string.gate_securing) else " ", color = if (error != null) Palette.Rec else Palette.InkSoft)
            Spacer(Modifier.height(40.dp))
        }
        ChoiceBar(
            bar,
            left = Choice(stringResource(R.string.gate_back), stringResource(R.string.gate_release_back), Palette.Tonal, Palette.Ink, enabled = !busy) { onBack() },
            right = Choice(stringResource(if (busy) R.string.gate_securing else R.string.gate_create), stringResource(R.string.gate_release_create), Palette.Ink, Palette.Paper, enabled = !busy) { create() },
            modifier = Modifier.padding(14.dp).axisDrag(axisDragHandler(
                accepts = { a, _, _ -> a == Axis.Horizontal && !busy },
                onDrag = { _, t, _ -> bar.drag(t) },
                onEnd = { _, _, _ -> when (bar.release()) { Side.Left -> onBack(); Side.Right -> create(); null -> {} } },
            )),
        )
    }
}

@Composable
private fun OfferBiometric(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findFragmentActivity() }
    val scope = rememberCoroutineScope()
    val bar = rememberChoiceState()
    val available = remember { Vault.biometricAvailability(ctx) == BiometricAvailability.Available }
    var error by remember { mutableStateOf<String?>(null) }
    val enable = enable@{
        val a = activity ?: return@enable
        error = null
        try {
            authenticateWithBiometric(
                a, ctx.getString(R.string.gate_bio_enroll_title), ctx.getString(R.string.gate_bio_enroll_subtitle), ctx.getString(R.string.gate_bio_not_now), Vault.biometricEnrollCipher(),
                onSuccess = { c -> scope.launch { try { withContext(Dispatchers.Default) { Vault.completeBiometricEnroll(ctx, c) }; onDone() } catch (e: Exception) { error = ctx.getString(R.string.gate_bio_enroll_failed) } } },
                onError = { if (it.isNotEmpty()) error = it },
            )
        } catch (e: Exception) {
            error = ctx.getString(R.string.gate_bio_enroll_failed)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.weight(1f).padding(horizontal = 26.dp), verticalArrangement = Arrangement.Center) {
            Label(stringResource(R.string.gate_bio_kicker))
            Spacer(Modifier.height(18.dp))
            Text(stringResource(if (available) R.string.gate_bio_title_on else R.string.gate_bio_title_off), style = Type.Hero.copy(fontSize = Type.Hero.fontSize * 0.78f, lineHeight = Type.Hero.lineHeight * 0.8f), color = Palette.Ink)
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(if (available) R.string.gate_bio_body_on else R.string.gate_bio_body_off, stringResource(R.string.app_name)),
                style = Type.Body, color = Palette.InkSoft,
            )
            Spacer(Modifier.height(12.dp))
            Label(error ?: " ", color = Palette.Rec)
        }
        ChoiceBar(
            bar,
            left = Choice(stringResource(if (available) R.string.gate_bio_not_now else R.string.gate_bio_skip), stringResource(R.string.gate_release_skip), Palette.Tonal, Palette.Ink) { onDone() },
            right = Choice(stringResource(if (available) R.string.gate_bio_turn_on else R.string.gate_continue), stringResource(R.string.gate_release_continue), Palette.Ink, Palette.Paper) { if (available) enable() else onDone() },
            modifier = Modifier.padding(14.dp).axisDrag(axisDragHandler(
                accepts = { a, _, _ -> a == Axis.Horizontal },
                onDrag = { _, t, _ -> bar.drag(t) },
                onEnd = { _, _, _ -> when (bar.release()) { Side.Left -> onDone(); Side.Right -> if (available) enable() else onDone(); null -> {} } },
            )),
        )
    }
}

@Composable
private fun Unlock(onOpen: () -> Unit, onErased: () -> Unit) {
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findFragmentActivity() }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val bar = rememberChoiceState()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var forgot by remember { mutableStateOf(false) }
    var confirmErase by remember { mutableStateOf(false) }
    val biometric = remember { Vault.isBiometricEnabled(ctx) && Vault.biometricAvailability(ctx) == BiometricAvailability.Available }
    val (shake, kick) = rememberShake()

    val withPassword = withPassword@{
        if (busy) return@withPassword
        if (password.isEmpty()) { haptics.reject(); scope.launch { kick() }; return@withPassword }
        busy = true
        error = null
        val chars = password.toCharArray()
        scope.launch {
            try {
                val ok = withContext(Dispatchers.Default) { Vault.unlock(ctx, chars) }
                if (ok) { haptics.confirm(); onOpen() } else { error = ctx.getString(R.string.gate_wrong_password); haptics.reject(); kick() }
            } catch (e: Exception) {
                error = ctx.getString(R.string.gate_unlock_failed)
            } finally {
                chars.fill('\u0000')
                busy = false
            }
        }
    }
    val withBiometric = withBiometric@{
        val a = activity ?: return@withBiometric
        if (busy) return@withBiometric
        error = null
        try {
            val cipher = Vault.biometricUnlockCipher(ctx)
            if (cipher == null) { error = ctx.getString(R.string.gate_bio_reset); return@withBiometric }
            authenticateWithBiometric(
                a, ctx.getString(R.string.gate_bio_unlock_title, ctx.getString(R.string.app_name)), ctx.getString(R.string.gate_bio_unlock_subtitle), ctx.getString(R.string.gate_bio_use_password), cipher,
                onSuccess = { c ->
                    busy = true
                    scope.launch {
                        try {
                            val ok = withContext(Dispatchers.Default) { Vault.completeBiometricUnlock(ctx, c) }
                            if (ok) { haptics.confirm(); onOpen() } else error = ctx.getString(R.string.gate_bio_failed)
                        } catch (e: Exception) {
                            error = ctx.getString(R.string.gate_bio_failed)
                        } finally {
                            busy = false
                        }
                    }
                },
                onError = { if (it.isNotEmpty()) error = it },
            )
        } catch (e: Exception) {
            error = ctx.getString(R.string.gate_bio_failed)
        }
    }
    LaunchedEffect(Unit) { if (biometric) withBiometric() }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 26.dp), verticalArrangement = Arrangement.Center) {
            Spacer(Modifier.height(60.dp))
            Label(stringResource(R.string.gate_locked_kicker))
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.app_name), style = Type.Hero, color = Palette.Ink)
            Spacer(Modifier.height(40.dp))
            Box(Modifier.offset { IntOffset(shake.value.roundToInt(), 0) }) {
                PaperField(password, { password = it; error = null }, stringResource(R.string.gate_password_hint), secret = true, enabled = !busy, error = error != null, onIme = withPassword)
            }
            Spacer(Modifier.height(12.dp))
            Label(error ?: if (busy) stringResource(R.string.gate_opening) else " ", color = if (error != null) Palette.Rec else Palette.InkSoft)
            Spacer(Modifier.height(18.dp))
            if (!forgot) {
                TextAction(stringResource(R.string.gate_forgot), { forgot = true }, color = Palette.InkSoft)
            } else {
                Text(stringResource(R.string.gate_forgot_body, stringResource(R.string.app_name)), style = Type.BodySmall, color = Palette.InkSoft)
                Row {
                    TextAction(stringResource(R.string.gate_erase_start), { confirmErase = true }, color = Palette.Rec)
                    Spacer(Modifier.width(18.dp))
                    TextAction(stringResource(R.string.gate_never_mind), { forgot = false }, color = Palette.InkSoft)
                }
            }
            Spacer(Modifier.height(40.dp))
        }
        ChoiceBar(
            bar,
            // No fingerprint set up (or none in this build): just Unlock, not a greyed-out button that does nothing.
            left = if (biometric) Choice(stringResource(R.string.gate_fingerprint), stringResource(R.string.gate_release_fingerprint), Palette.Tonal, Palette.Ink, enabled = !busy) { withBiometric() } else null,
            right = Choice(stringResource(if (busy) R.string.gate_opening else R.string.gate_unlock), stringResource(R.string.gate_release_unlock), Palette.Ink, Palette.Paper, enabled = !busy) { withPassword() },
            modifier = Modifier.padding(14.dp).axisDrag(axisDragHandler(
                accepts = { a, _, _ -> a == Axis.Horizontal && !busy },
                onDrag = { _, t, _ -> bar.drag(t) { s -> s == Side.Right || biometric } },
                onEnd = { _, _, _ -> when (bar.release()) { Side.Left -> withBiometric(); Side.Right -> withPassword(); null -> {} } },
            )),
        )
    }

    if (confirmErase) ConfirmDialog(
        title = stringResource(R.string.gate_erase_title),
        text = stringResource(R.string.gate_erase_text),
        confirm = stringResource(R.string.gate_erase_confirm),
        onDismiss = { confirmErase = false },
        onConfirm = {
            confirmErase = false
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { Vault.erase(ctx) }
                    password = ""
                    onErased()
                } catch (e: Exception) {
                    error = ctx.getString(R.string.gate_erase_failed)
                }
            }
        },
    )
}
