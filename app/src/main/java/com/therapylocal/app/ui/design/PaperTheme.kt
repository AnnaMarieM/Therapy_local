package com.therapylocal.app.ui.design

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.Bitmap
import kotlin.random.Random

/**
 * The app's theme: paper and ink, the three type voices, reduced motion. Material components still in use (text fields, dialogs) read the
 * same colours through [MaterialTheme], so nothing falls back to Material's defaults.
 */
@Composable
fun PaperTheme(content: @Composable () -> Unit) {
    val scheme = lightColorScheme(
        primary = Palette.Ink, onPrimary = Palette.Paper,
        secondary = Palette.Rev, onSecondary = Palette.OnColor,
        tertiary = Palette.Rec, onTertiary = Palette.OnColor,
        error = Palette.Rec, onError = Palette.OnColor,
        background = Palette.Paper, onBackground = Palette.Ink,
        surface = Palette.Paper, onSurface = Palette.Ink,
        surfaceVariant = Palette.Tonal, onSurfaceVariant = Palette.InkSoft,
        surfaceContainer = Palette.Paper, surfaceContainerHigh = Palette.Paper, surfaceContainerHighest = Palette.Tonal,
        surfaceContainerLow = Palette.Paper, surfaceContainerLowest = Palette.Paper,
        outline = Palette.Hairline, outlineVariant = Palette.Hairline,
        inverseSurface = Palette.Ink, inverseOnSurface = Palette.Paper,
    )
    val type = Typography(
        displayLarge = Type.Hero, displayMedium = Type.Statement, headlineMedium = Type.Title, titleLarge = Type.Title,
        bodyLarge = Type.Body, bodyMedium = Type.BodySmall, labelLarge = Type.Action, labelMedium = Type.Label, labelSmall = Type.Label,
    )
    MaterialTheme(colorScheme = scheme, typography = type) {
        CompositionLocalProvider(
            LocalReducedMotion provides rememberReducedMotion(),
            LocalContentColor provides Palette.Ink,
            LocalTextSelectionColors provides TextSelectionColors(Palette.Ink, Palette.Ink.copy(alpha = 0.18f)),
            content = content,
        )
    }
}

/**
 * Paper: the background colour with a faint fixed grain, so large empty areas read as a surface rather than a screen. The grain is one
 * small tile made once and repeated by the GPU; it costs nothing per frame.
 */
fun Modifier.paper(): Modifier = drawBehind {
    drawRect(Palette.Paper)
    drawRect(ShaderBrush(ImageShader(Grain.tile, TileMode.Repeated, TileMode.Repeated)))
}

private object Grain {
    val tile: ImageBitmap by lazy {
        val n = 160
        val r = Random(7)
        val px = IntArray(n * n) { val v = r.nextInt(256); (9 shl 24) or (v shl 16) or (v shl 8) or v }
        Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}

@Composable
internal fun rememberGrain(): ImageBitmap = remember { Grain.tile }
