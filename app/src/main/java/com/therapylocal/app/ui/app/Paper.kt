package com.therapylocal.app.ui.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.therapylocal.app.ui.design.Motion
import com.therapylocal.app.ui.design.Palette
import com.therapylocal.app.ui.design.Type
import com.therapylocal.app.ui.design.rememberHaptics
import kotlinx.coroutines.delay

/** Small uppercase metadata, the app's quiet voice. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = Palette.InkSoft) {
    Text(text.uppercase(), style = Type.Label, color = color, modifier = modifier)
}

/**
 * Press feedback for anything tappable: it gives under the finger at once (scale), ticks on release, then acts. No ripple: on paper a
 * ripple reads as a screen, a press reads as an object.
 */
fun Modifier.pressable(onClick: () -> Unit, enabled: Boolean = true, label: String? = null, role: Role = Role.Button, depth: Float = 0.03f): Modifier =
    composed {
        val haptics = rememberHaptics()
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val press = remember { Animatable(0f) }
        LaunchedEffect(pressed) { press.animateTo(if (pressed) 1f else 0f, Motion.Pull) }
        graphicsLayer { val s = 1f - depth * press.value; scaleX = s; scaleY = s }
            .clickable(interaction, indication = null, enabled = enabled, onClickLabel = label, role = role) { haptics.press(); onClick() }
    }

/** A text-only action ("Forgot password", "Delete client…"). 48 dp tall so it is easy to hit, however short the word. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Palette.Ink, enabled: Boolean = true) {
    Box(modifier.heightIn(min = 48.dp).pressable(onClick, enabled).padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
        Text(text, style = Type.Action, color = if (enabled) color else Palette.InkFaint)
    }
}

/** A full-width pill button, for the few screens with a single action. */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Palette.Ink, content: Color = Palette.Paper, enabled: Boolean = true) {
    Box(
        modifier.fillMaxWidth().height(60.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.42f }
            .clip(RoundedCornerShape(30.dp)).background(color)
            .pressable(onClick, enabled),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.Action, color = content) }
}

/** The recording dot: a slow breath, still when [still] (paused) or with reduced motion. */
@Composable
fun PulseDot(color: Color, modifier: Modifier = Modifier, size: Int = 8, still: Boolean = false) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(1f, 0.3f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "a")
    val reduced = com.therapylocal.app.ui.design.LocalReducedMotion.current
    Box(modifier.size(size.dp).graphicsLayer { alpha = if (still || reduced) 1f else a }.clip(CircleShape).background(color))
}

/**
 * Paper text field: no box, a hairline that darkens when focused, the label above. [secret] adds a show/hide toggle.
 */
@Composable
fun PaperField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    enabled: Boolean = true,
    error: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onIme: () -> Unit = {},
    style: TextStyle = Type.Title,
    keyboardType: KeyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
) {
    var visible by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    Column(modifier) {
        Label(label, color = if (error) Palette.Rec else Palette.InkSoft)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = style.copy(color = Palette.Ink),
                cursorBrush = SolidColor(Palette.Ink),
                visualTransformation = if (secret && !visible) PasswordVisualTransformation('•') else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(capitalization = capitalization, keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = !secret),
                keyboardActions = KeyboardActions(onAny = { onIme() }),
                modifier = Modifier.weight(1f).padding(vertical = 8.dp)
                    .onFocusChanged { focused = it.isFocused }
                    .semantics { contentDescription = label },
            )
            if (secret) {
                Box(Modifier.size(48.dp).pressable({ visible = !visible }, label = if (visible) "Hide" else "Show"), contentAlignment = Alignment.Center) {
                    Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = if (visible) "Hide password" else "Show password", tint = Palette.InkSoft)
                }
            }
        }
        val line = when {
            error -> Palette.Rec
            focused -> Palette.Ink
            else -> Palette.Hairline
        }
        Box(Modifier.fillMaxWidth().height(if (focused || error) 1.5.dp else 1.dp).background(line))
    }
}

/** A destructive or important confirmation, in paper colours. [confirm] is the verb ("Delete"), never "OK". */
@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = true) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Paper,
        shape = RoundedCornerShape(28.dp),
        title = { Text(title, style = Type.Title, color = Palette.Ink) },
        text = { Text(text, style = Type.BodySmall, color = Palette.InkSoft) },
        confirmButton = { TextAction(confirm, { onConfirm() }, color = if (destructive) Palette.Rec else Palette.Ink) },
        dismissButton = { TextAction("Cancel", onDismiss, color = Palette.InkSoft) },
    )
}

/** A brief line at the top of the screen ("Saved to George's record"), with at most one action. One at a time; a new one replaces it. */
@Stable
class NoticeState {
    var text by mutableStateOf<String?>(null)
        private set
    var action by mutableStateOf<Pair<String, () -> Unit>?>(null)
        private set
    internal var serial by mutableIntStateOf(0)
        private set
    internal var durationMs = 2_400L

    fun show(text: String, action: Pair<String, () -> Unit>? = null, durationMs: Long = if (action != null) 6_000L else 2_400L) {
        this.text = text
        this.action = action
        this.durationMs = durationMs
        serial++
    }

    fun hide() {
        text = null
        action = null
    }
}

@Composable
fun NoticeHost(state: NoticeState, modifier: Modifier = Modifier) {
    val alpha = remember { Animatable(0f) }
    var shown by remember { mutableStateOf<Pair<String, Pair<String, () -> Unit>?>?>(null) }
    LaunchedEffect(state.serial) {
        val t = state.text ?: return@LaunchedEffect
        shown = t to state.action
        alpha.animateTo(1f, Motion.Fade)
        delay(state.durationMs)
        alpha.animateTo(0f, Motion.Fade)
        state.hide()
        shown = null
    }
    val s = shown ?: return
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)
            .graphicsLayer { this.alpha = alpha.value; translationY = (1f - alpha.value) * -12.dp.toPx() }
            .clip(RoundedCornerShape(22.dp)).background(Palette.Ink)
            .padding(start = 18.dp, end = 6.dp)
            .heightIn(min = 44.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(s.first, style = Type.BodySmall, color = Palette.Paper, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
        s.second?.let { (label, act) ->
            TextAction(label, { act(); state.hide() }, color = Palette.Paper, modifier = Modifier.padding(start = 8.dp, end = 10.dp))
        }
    }
}

/** A hairline divider. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).drawBehind { drawLine(Palette.Hairline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) })
}
