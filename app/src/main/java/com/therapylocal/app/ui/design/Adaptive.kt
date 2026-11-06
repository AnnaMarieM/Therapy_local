package com.therapylocal.app.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How much room the window has, by its current width: it follows split-screen and free-form resizing, not the physical screen. */
enum class LayoutClass { Compact, Medium, Expanded }

object Adaptive {
    const val MEDIUM_DP = 600
    const val EXPANDED_DP = 840

    fun classOf(widthDp: Int): LayoutClass = when {
        widthDp >= EXPANDED_DP -> LayoutClass.Expanded
        widthDp >= MEDIUM_DP -> LayoutClass.Medium
        else -> LayoutClass.Compact
    }

    /** Client list on the left, the open page on the right: wide windows, or medium ones that are wider than tall. */
    fun twoPane(widthDp: Int, heightDp: Int): Boolean =
        classOf(widthDp) == LayoutClass.Expanded || (classOf(widthDp) == LayoutClass.Medium && widthDp > heightDp)

    /** Width of the client list in two-pane mode. */
    fun listPaneDp(widthDp: Int): Int = (widthDp * 0.36f).toInt().coerceIn(320, 440)

    /** Widest a column of reading text or a form gets, so lines stay short on large windows. */
    val ReadingWidth: Dp = 720.dp
}

/** Tests set this to pretend the window is another size; null means the real one. */
val LocalWindowSizeOverride: ProvidableCompositionLocal<Pair<Int, Int>?> = compositionLocalOf { null }

@Composable
fun windowWidthDp(): Int = LocalWindowSizeOverride.current?.first ?: LocalConfiguration.current.screenWidthDp

@Composable
fun windowHeightDp(): Int = LocalWindowSizeOverride.current?.second ?: LocalConfiguration.current.screenHeightDp

@Composable
fun rememberLayoutClass(): LayoutClass = Adaptive.classOf(windowWidthDp())

@Composable
fun rememberTwoPane(): Boolean = Adaptive.twoPane(windowWidthDp(), windowHeightDp())

/** The seam between the client list and the open page: a hairline that fades out at both ends, so it reads as a fold in the paper, not a rule. */
@Composable
fun PaneDivider(modifier: Modifier = Modifier) {
    Box(
        modifier.width(1.dp).fillMaxHeight().drawBehind {
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent, 0.14f to Palette.Hairline, 0.86f to Palette.Hairline, 1f to Color.Transparent,
                ),
            )
        },
    )
}
