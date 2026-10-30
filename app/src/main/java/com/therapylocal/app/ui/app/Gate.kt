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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
private const val RESET_NOTICE = "Fingerprint unlock was reset because the phone's biometrics changed. Use your password, then turn it on again in Settings."

/**
 * Everything before the client list: first run (a short welcome, the two permissions, a password, optionally biometrics) or unlocking.
 * Calls [onOpen] once the vault is open.
 */
@Composable
fun Gate(onOpen: () -> Unit) {
    val ctx = LocalContext.current
    var step by rememberSaveable { mutableStateOf(if (Vault.isSetUp(ctx)) GateStep.Unlock else GateStep.Welcome) }
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
                GateStep.Welcome -> Welcome(onDone = { step = GateStep.Password })
                GateStep.Password -> CreatePassword(onBack = { step = GateStep.Welcome }, onCreated = {
                    if (Vault.biometricAvailability(ctx) == BiometricAvailability.Unsupported) onOpen() else step = GateStep.Biometric
                })
                GateStep.Biometric -> OfferBiometric(onDone = onOpen)
                GateStep.Unlock -> Unlock(onOpen = onOpen, onErased = { step = GateStep.Password })
            }
        }
    }
}

enum class GateStep { Welcome, Password, Biometric, Unlock }

private data class Page(val kicker: String, val title: String, val body: String)

private val PAGES = listOf(
    Page("MindVault", "A quiet memory\nfor your sessions.", "It listens while you work, remembers what matters, and hands it back when it is useful. Nothing else."),
    Page("Private", "Everything stays\non this phone.", "Recordings, transcripts and what MindVault learns are encrypted with a password only you know. Nothing is sent anywhere."),
    Page("After a session", "It listens,\nthen remembers.", "When a session ends, the phone writes it down and notes the important things, in the background. You are told when it is done."),
    Page("During a session", "Earlier sessions,\nwhen they matter.", "While you talk, things the client said before come up quietly on the screen. Glance, or ignore them."),
)

/** The welcome pages and the permission primers: swipe or use the bar, both always work. */
@Composable
private fun Welcome(onDone: () -> Unit) {
    val count = PAGES.size + 1
    val pager = rememberPagerState { count }
    val scope = rememberCoroutineScope()
    val bar = rememberChoiceState()
    val last = pager.currentPage == count - 1
    val go: (Int) -> Unit = { p -> if (p >= count) onDone() else if (p >= 0) scope.launch { pager.animateScrollToPage(p) } }

    // One rule everywhere: a swipe anywhere is the bar's (right to left continues, left to right goes back), so the last page continues
    // into the password the same way the others turn, instead of the pager stopping dead at its end.
    val drag = remember(count) {
        axisDragHandler(
            accepts = { a, _, _ -> a == Axis.Horizontal },
            onDrag = { _, total, _ -> bar.drag(total) { s -> s == Side.Right || pager.currentPage > 0 } },
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
            left = Choice("Back", "Release to go back", Palette.Tonal, Palette.Ink, enabled = pager.currentPage > 0) { go(pager.currentPage - 1) },
            right = Choice(if (last) "Set a password" else "Continue", "Release to continue", Palette.Ink, Palette.Paper) { go(pager.currentPage + 1) },
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
        Label(p.kicker)
        Spacer(Modifier.height(18.dp))
        Text(p.title, style = Type.Hero.copy(fontSize = Type.Hero.fontSize * 0.78f, lineHeight = Type.Hero.lineHeight * 0.8f), color = Palette.Ink)
        Spacer(Modifier.height(22.dp))
        Text(p.body, style = Type.Body, color = Palette.InkSoft, modifier = Modifier.width(340.dp))
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
        Label("Two permissions")
        Spacer(Modifier.height(18.dp))
        Text("What it needs,\nand why.", style = Type.Hero.copy(fontSize = Type.Hero.fontSize * 0.78f, lineHeight = Type.Hero.lineHeight * 0.8f), color = Palette.Ink)
        Spacer(Modifier.height(30.dp))
        PermissionRow("Microphone", "To record sessions and to search by voice. Audio never leaves the phone.", mic) { ask.launch(Manifest.permission.RECORD_AUDIO) }
        Hairline()
        PermissionRow("Notifications", "To tell you when a session has been processed, and to show that a recording is running.", notif) {
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
        if (granted) Label("Allowed", color = Palette.Rev) else TextAction("Allow", onAllow)
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
            error = if (!longEnough) "At least $MIN_PASSWORD characters" else "The two do not match"
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
                error = e.message ?: "Setup failed. Please try again."
                busy = false
            } finally {
                chars.fill('\u0000')
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 26.dp), verticalArrangement = Arrangement.Center) {
            Spacer(Modifier.height(60.dp))
            Label("Your password")
            Spacer(Modifier.height(18.dp))
            Text("Only you\ncan open it.", style = Type.Hero.copy(fontSize = Type.Hero.fontSize * 0.78f, lineHeight = Type.Hero.lineHeight * 0.8f), color = Palette.Ink)
            Spacer(Modifier.height(14.dp))
            Text("It encrypts everything on this phone. It cannot be recovered if you forget it, so choose one you will remember.", style = Type.Body, color = Palette.InkSoft)
            Spacer(Modifier.height(30.dp))
            Column(Modifier.offset { IntOffset(shake.value.roundToInt(), 0) }) {
                PaperField(password, { password = it; error = null }, "Password · at least $MIN_PASSWORD characters", secret = true, enabled = !busy, imeAction = ImeAction.Next)
                Spacer(Modifier.height(22.dp))
                PaperField(confirm, { confirm = it; error = null }, "The same again", secret = true, enabled = !busy, error = confirm.isNotEmpty() && !matches && confirm.length >= password.length, onIme = create)
            }
            Spacer(Modifier.height(12.dp))
            Label(error ?: if (busy) "Securing…" else " ", color = if (error != null) Palette.Rec else Palette.InkSoft)
            Spacer(Modifier.height(40.dp))
        }
        ChoiceBar(
            bar,
            left = Choice("Back", "Release to go back", Palette.Tonal, Palette.Ink, enabled = !busy) { onBack() },
            right = Choice(if (busy) "Securing…" else "Create", "Release to create", Palette.Ink, Palette.Paper, enabled = !busy) { create() },
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
                a, "Unlock with fingerprint", "Confirm to turn it on", "Not now", Vault.biometricEnrollCipher(),
                onSuccess = { c -> scope.launch { try { withContext(Dispatchers.Default) { Vault.completeBiometricEnroll(ctx, c) }; onDone() } catch (e: Exception) { error = e.message ?: "It could not be turned on." } } },
                onError = { if (it.isNotEmpty()) error = it },
            )
        } catch (e: Exception) {
            error = e.message ?: "It could not be turned on."
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.weight(1f).padding(horizontal = 26.dp), verticalArrangement = Arrangement.Center) {
            Label("Unlocking")
            Spacer(Modifier.height(18.dp))
            Text(if (available) "Open it with\na touch." else "Fingerprint\nlater, maybe.", style = Type.Hero.copy(fontSize = Type.Hero.fontSize * 0.78f, lineHeight = Type.Hero.lineHeight * 0.8f), color = Palette.Ink)
            Spacer(Modifier.height(14.dp))
            Text(
                if (available) "Use your fingerprint or face instead of typing the password each time. The password always works too."
                else "No fingerprint or face is set up on this phone. Add one in the phone's settings, then turn it on in MindVault's settings.",
                style = Type.Body, color = Palette.InkSoft,
            )
            Spacer(Modifier.height(12.dp))
            Label(error ?: " ", color = Palette.Rec)
        }
        ChoiceBar(
            bar,
            left = Choice(if (available) "Not now" else "Skip", "Release to skip", Palette.Tonal, Palette.Ink) { onDone() },
            right = Choice(if (available) "Turn on" else "Continue", "Release to continue", Palette.Ink, Palette.Paper) { if (available) enable() else onDone() },
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
                if (ok) { haptics.confirm(); onOpen() } else { error = "That is not the password"; haptics.reject(); kick() }
            } catch (e: Exception) {
                error = e.message ?: "Could not unlock. Please try again."
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
            if (cipher == null) { error = RESET_NOTICE; return@withBiometric }
            authenticateWithBiometric(
                a, "Unlock MindVault", "Confirm it's you", "Use password", cipher,
                onSuccess = { c ->
                    busy = true
                    scope.launch {
                        try {
                            val ok = withContext(Dispatchers.Default) { Vault.completeBiometricUnlock(ctx, c) }
                            if (ok) { haptics.confirm(); onOpen() } else error = "That did not work. Use your password."
                        } catch (e: Exception) {
                            error = e.message ?: "That did not work. Use your password."
                        } finally {
                            busy = false
                        }
                    }
                },
                onError = { if (it.isNotEmpty()) error = it },
            )
        } catch (e: Exception) {
            error = e.message ?: "That did not work. Use your password."
        }
    }
    LaunchedEffect(Unit) { if (biometric) withBiometric() }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 26.dp), verticalArrangement = Arrangement.Center) {
            Spacer(Modifier.height(60.dp))
            Label("Locked")
            Spacer(Modifier.height(18.dp))
            Text("MindVault", style = Type.Hero, color = Palette.Ink)
            Spacer(Modifier.height(40.dp))
            Box(Modifier.offset { IntOffset(shake.value.roundToInt(), 0) }) {
                PaperField(password, { password = it; error = null }, "Password", secret = true, enabled = !busy, error = error != null, onIme = withPassword)
            }
            Spacer(Modifier.height(12.dp))
            Label(error ?: if (busy) "Opening…" else " ", color = if (error != null) Palette.Rec else Palette.InkSoft)
            Spacer(Modifier.height(18.dp))
            if (!forgot) {
                TextAction("Forgot password", { forgot = true }, color = Palette.InkSoft)
            } else {
                Text("It cannot be recovered. You can erase MindVault and start again; every client, recording and fact on this phone is deleted.", style = Type.BodySmall, color = Palette.InkSoft)
                Row {
                    TextAction("Erase and start again…", { confirmErase = true }, color = Palette.Rec)
                    Spacer(Modifier.width(18.dp))
                    TextAction("Never mind", { forgot = false }, color = Palette.InkSoft)
                }
            }
            Spacer(Modifier.height(40.dp))
        }
        ChoiceBar(
            bar,
            // No fingerprint set up (or none in this build): just Unlock, not a greyed-out button that does nothing.
            left = if (biometric) Choice("Fingerprint", "Release for fingerprint", Palette.Tonal, Palette.Ink, enabled = !busy) { withBiometric() } else null,
            right = Choice(if (busy) "Opening…" else "Unlock", "Release to unlock", Palette.Ink, Palette.Paper, enabled = !busy) { withPassword() },
            modifier = Modifier.padding(14.dp).axisDrag(axisDragHandler(
                accepts = { a, _, _ -> a == Axis.Horizontal && !busy },
                onDrag = { _, t, _ -> bar.drag(t) { s -> s == Side.Right || biometric } },
                onEnd = { _, _, _ -> when (bar.release()) { Side.Left -> withBiometric(); Side.Right -> withPassword(); null -> {} } },
            )),
        )
    }

    if (confirmErase) ConfirmDialog(
        title = "Erase everything?",
        text = "Every client, recording, transcript and fact on this phone is permanently deleted. This cannot be undone.",
        confirm = "Erase everything",
        onDismiss = { confirmErase = false },
        onConfirm = {
            confirmErase = false
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { Vault.erase(ctx) }
                    password = ""
                    onErased()
                } catch (e: Exception) {
                    error = e.message ?: "Could not erase. Please try again."
                }
            }
        },
    )
}
