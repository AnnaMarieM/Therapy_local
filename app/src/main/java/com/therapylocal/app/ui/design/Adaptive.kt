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

/** What a window of a given size shows. The one answer every breakpoint comes from. */
data class AdaptiveLayout(val layoutClass: LayoutClass, val twoPane: Boolean, val listPaneDp: Int)

/**
 * The single entry point for every layout decision (AD-05). Input: the window's width and height in dp, measured against the PINNED
 * density ([DisplayPin]), so the user's font-size and display-size settings never move a breakpoint. Output depends on nothing else: not on
 * the OEM, the font scale, the locale or the data. Rule, by width:
 *
 * | width (dp)  | class    | portrait (w <= h)              | landscape (w > h)               |
 * |-------------|----------|--------------------------------|---------------------------------|
 * | below 600   | Compact  | one pane (reel, pages on top)  | one pane                        |
 * | 600 to 839  | Medium   | one pane                       | two panes (list 36%, 320..440)  |
 * | 840 and up  | Expanded | two panes                      | two panes                       |
 *
 * Phones (portrait lock, `R.bool.portrait_lock`) are always Compact and one pane. The alphabet letters on the right edge of the reel are
 * not a layout: they show whenever the client list has two or more first letters, at every width.
 */
object Adaptive {
    const val MEDIUM_DP = 600
    const val EXPANDED_DP = 840

    fun layoutFor(widthDp: Int, heightDp: Int): AdaptiveLayout {
        val cls = classOf(widthDp)
        val two = cls == LayoutClass.Expanded || (cls == LayoutClass.Medium && widthDp > heightDp)
        return AdaptiveLayout(cls, two, listPaneDp(widthDp))
    }

    fun classOf(widthDp: Int): LayoutClass = when {
        widthDp >= EXPANDED_DP -> LayoutClass.Expanded
        widthDp >= MEDIUM_DP -> LayoutClass.Medium
        else -> LayoutClass.Compact
    }

    /** Client list on the left, the open page on the right. See the table on [Adaptive]. */
    fun twoPane(widthDp: Int, heightDp: Int): Boolean = layoutFor(widthDp, heightDp).twoPane

    /** Width of the client list in two-pane mode. */
    fun listPaneDp(widthDp: Int): Int = (widthDp * 0.36f).toInt().coerceIn(320, 440)

    /** Widest a column of reading text or a form gets, so lines stay short on large windows. */
    val ReadingWidth: Dp = 720.dp
}

/** Tests set this to pretend the window is another size; null means the real one. */
val LocalWindowSizeOverride: ProvidableCompositionLocal<Pair<Int, Int>?> = compositionLocalOf { null }

@Composable
fun windowWidthDp(): Int = LocalWindowSizeOverride.current?.first ?: measuredWindow().first

@Composable
fun windowHeightDp(): Int = LocalWindowSizeOverride.current?.second ?: measuredWindow().second

/**
 * The window in dp at the pinned density. The Configuration's dp were computed by the system at the user's density (the global one); the
 * density Compose draws with is the pinned one, so the same pixels are converted.
 */
@Composable
private fun measuredWindow(): Pair<Int, Int> {
    val c = LocalConfiguration.current
    val system = android.content.res.Resources.getSystem().configuration.densityDpi
    return Pair(DisplayPin.pinnedDp(c.screenWidthDp, system, c.densityDpi), DisplayPin.pinnedDp(c.screenHeightDp, system, c.densityDpi))
}

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
