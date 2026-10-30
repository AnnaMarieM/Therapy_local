package com.therapylocal.app.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints

/**
 * Room for a line that changes what it says (a status turning into its evidence, a hint saying "Release to …") without the page around
 * it moving: it is as tall as the tallest of [messages] would be at this width, and, while [key] stays the same, never gets shorter again.
 * [content] draws whichever message is current inside that room, at the top.
 */
@Composable
fun SteadyText(messages: List<String>, style: TextStyle, modifier: Modifier = Modifier, key: Any? = Unit, content: @Composable () -> Unit) {
    val measurer = rememberTextMeasurer()
    val tallest = remember(key) { IntArray(1) }
    Layout(content, modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val need = messages.maxOfOrNull { m ->
            measurer.measure(m, style, constraints = if (width == Constraints.Infinity) Constraints() else Constraints(maxWidth = width)).size.height
        } ?: 0
        tallest[0] = maxOf(tallest[0], need)
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val h = maxOf(tallest[0], placeables.maxOfOrNull { it.height } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
        val w = (placeables.maxOfOrNull { it.width } ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(w, h) { placeables.forEach { it.place(0, 0) } }
    }
}
