package com.therapylocal.app.ui.design

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/** One line that never wraps: if [text] is wider than the space it gets, the size steps down (6% at a time, never below [minSp]) until it fits. Hidden until it does, so there is no jump. */
@Composable
fun OneLine(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, minSp: Float = 24f) {
    val start = style.fontSize.value
    var size by remember(text, start) { mutableStateOf(start) }
    var fitted by remember(text, start) { mutableStateOf(false) }
    val k = size / start
    BasicText(
        text,
        modifier = modifier.alpha(if (fitted) 1f else 0f),
        style = style.copy(color = color, fontSize = size.sp, lineHeight = style.lineHeight * k),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = {
            if (it.hasVisualOverflow && size > minSp) size = maxOf(minSp, size * 0.94f) else fitted = true
        },
    )
}
