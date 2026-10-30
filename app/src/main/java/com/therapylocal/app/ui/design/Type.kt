package com.therapylocal.app.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.therapylocal.app.R

/**
 * Three voices. Fraunces (display): names and the one thing that matters on a screen, light and slightly soft. Geist (ui): buttons and body.
 * Geist Mono (label): small uppercase metadata, the quiet voice. All three are variable fonts bundled in res/font (OFL, see assets/licenses).
 */
@OptIn(ExperimentalTextApi::class)
@Immutable
object Type {
    private fun fraunces(weight: Int, italic: Boolean, opsz: Float) = Font(
        if (italic) R.font.fraunces_italic else R.font.fraunces,
        weight = FontWeight(weight),
        style = if (italic) FontStyle.Italic else FontStyle.Normal,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(weight),
            FontVariation.Setting("opsz", opsz),
            FontVariation.Setting("SOFT", 40f),
            FontVariation.Setting("WONK", 0f),
        ),
    )

    /** Large sizes use the display optical size (finer contrast); small sizes the text size, which stays readable. */
    val DisplayFamily = FontFamily(fraunces(260, false, 144f), fraunces(260, true, 144f), fraunces(380, false, 144f), fraunces(380, true, 144f))
    val TextSerif = FontFamily(fraunces(340, false, 24f), fraunces(340, true, 24f))

    val UiFamily = FontFamily(
        Font(R.font.geist, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(R.font.geist, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
        Font(R.font.geist, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    )
    val MonoFamily = FontFamily(Font(R.font.geist_mono, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))))

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
