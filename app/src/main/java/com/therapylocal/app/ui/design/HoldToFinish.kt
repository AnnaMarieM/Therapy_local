package com.therapylocal.app.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Ending a session is a hold, never a tap: a ring fills around the stop square over [holdMs]; letting go early drains it back (with a
 * reject tick and [onTooShort], so the screen can say "hold to finish"). Filling completes with a firm confirm. TalkBack gets a direct
 * "Finish session" action instead of having to hold.
 */
@Composable
fun HoldToFinish(onComplete: () -> Unit, onTooShort: () -> Unit, modifier: Modifier = Modifier, holdMs: Int = 700) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val fill = remember { Animatable(0f) }
    val press = remember { Animatable(0f) }
    Box(
        modifier
            .size(60.dp)
            .graphicsLayer { val s = 1f - 0.05f * press.value; scaleX = s; scaleY = s }
            .clip(CircleShape)
            .background(Palette.Tonal)
            .semantics {
                contentDescription = "Hold to finish the session"
                onClick(label = "Hold to finish") { onTooShort(); true }
                customActions = listOf(CustomAccessibilityAction("Finish session") { onComplete(); true })
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    haptics.press()
                    scope.launch { press.animateTo(1f, Motion.Pull) }
                    val run = scope.launch {
                        fill.animateTo(1f, tween((holdMs * (1f - fill.value)).toInt(), easing = LinearEasing))
                        haptics.confirm()
                        onComplete()
                    }
                    val released = tryAwaitRelease()
                    scope.launch { press.animateTo(0f, Motion.Pull) }
                    if (fill.value < 1f) {
                        run.cancel()
                        if (released) {
                            haptics.reject()
                            onTooShort()
                        }
                        scope.launch { fill.animateTo(0f, Motion.Pull) }
                    } else {
                        scope.launch { fill.snapTo(0f) }
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 3.dp.toPx()
            val p = fill.value
            if (p > 0f) {
                drawArc(
                    Palette.Rec, startAngle = -90f, sweepAngle = 360f * p, useCenter = false,
                    topLeft = Offset(w, w), size = Size(size.width - 2 * w, size.height - 2 * w), style = Stroke(w, cap = StrokeCap.Round),
                )
            }
        }
        Box(Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(Palette.Ink))
    }
}
