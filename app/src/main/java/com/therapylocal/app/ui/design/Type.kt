package com.therapylocal.app.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.therapylocal.app.R

/**
 * Three voices. Fraunces (display): names and the one thing that matters on a screen, light and slightly soft. Geist (ui): buttons and body.
 * Geist Mono (label): small uppercase metadata, the quiet voice.
 *
 * Every face is a STATIC instance in res/font (cut from the variable masters in tools/fonts-src by tools/make-static-fonts.py; OFL, see
 * assets/licenses), so no axis is applied at run time. A variable font whose axes the device ignores falls back to the master's default,
 * which for Fraunces is Black at optical size 9: that is what HyperOS drew (AD-05). Weight and style are stated for each face, and
 * [TypeScanTest] fails the build if a text style uses a system family. Glyph coverage: Latin, Latin Extended (Czech) and general
 * punctuation in all three families; the geometric shapes the UI needs (radio dots, stop square) are drawn, not typed.
 */
@Immutable
object Type {
    private fun face(res: Int, weight: Int, italic: Boolean = false) =
        Font(res, weight = FontWeight(weight), style = if (italic) FontStyle.Italic else FontStyle.Normal)

    /** Large sizes use the display optical size (144, finer contrast); small sizes the text size (24), which stays readable. */
    val DisplayFamily = FontFamily(
        face(R.font.fraunces_display_260, 260), face(R.font.fraunces_display_italic_260, 260, true),
        face(R.font.fraunces_display_380, 380), face(R.font.fraunces_display_italic_380, 380, true),
    )
    val TextSerif = FontFamily(face(R.font.fraunces_text_340, 340), face(R.font.fraunces_text_italic_340, 340, true))

    val UiFamily = FontFamily(face(R.font.geist_400, 400), face(R.font.geist_500, 500), face(R.font.geist_600, 600))
    val MonoFamily = FontFamily(face(R.font.geist_mono_400, 400))

    /** Every family the app may draw with. Anything else is a system font, which differs per ROM. */
    val Bundled: Set<FontFamily> = setOf(DisplayFamily, TextSerif, UiFamily, MonoFamily)

    private val tight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

    /** The selected name on the reel, the profile title. */
    val Hero = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight(260), fontSize = 64.sp, lineHeight = 60.sp, letterSpacing = (-0.04).em, lineHeightStyle = tight)

    /** A live cue; a screen's single statement. */
    val Statement = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight(260), fontSize = 40.sp, lineHeight = 42.sp, letterSpacing = (-0.03).em)

    /** Names on the reel away from the centre; section titles. */
    val Title = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight(260), fontSize = 26.sp, lineHeight = 31.sp, letterSpacing = (-0.02).em)

    /** Greeting and other one-line serif text. */
    val Lead = TextStyle(fontFamily = TextSerif, fontWeight = FontWeight(340), fontStyle = FontStyle.Italic, fontSize = 18.sp, lineHeight = 24.sp)

    /** Reading text: facts, transcripts. */
    val Body = TextStyle(fontFamily = UiFamily, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 26.sp, letterSpacing = (-0.005).em)
    val BodySmall = TextStyle(fontFamily = UiFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp)

    /** Buttons. */
    val Action = TextStyle(fontFamily = UiFamily, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 20.sp, letterSpacing = (-0.005).em)

    /** Small uppercase metadata: dates, counts, states. */
    val Label = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.05.em)
}
