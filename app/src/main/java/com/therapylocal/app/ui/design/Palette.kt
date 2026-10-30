package com.therapylocal.app.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Paper and ink. Colour carries meaning only: [Rec] is recording (and the one action that starts it), [Rev] is something done or kept,
 * everything else is ink on paper at different strengths. No other hues.
 */
@Immutable
object Palette {
    val Paper = Color(0xFFEDE6D9)
    val Ink = Color(0xFF2B221B)
    val Tonal = Color(0xFFDED5C4)
    val Rec = Color(0xFFC9361F)
    val Rev = Color(0xFF2C7A62)
    val OnColor = Color.White

    /** Ink strengths, from body text down to hairlines. Contrast on [Paper]: Ink 13:1, Soft 6:1, Faint 3.4:1 (large text and icons only). */
    val InkSoft = Ink.copy(alpha = 0.72f)
    val InkFaint = Ink.copy(alpha = 0.55f)
    val Hairline = Ink.copy(alpha = 0.16f)
    val Scrim = Ink.copy(alpha = 0.22f)
}
