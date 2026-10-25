package com.therapylocal.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// MindVault Clinical Aesthetic Palette
val SageGreen = Color(0xFF4C6B5B)
val SageDark = Color(0xFF385043)
val SageLight = Color(0xFFEDF3F0)
val SageBorder = Color(0xFFD3E0D8)
val SagePill = Color(0xFFE2ECE7)

val WarmBg = Color(0xFFF9F8F5)
val SurfaceCard = Color(0xFFFFFFFF)
val SurfaceCardMuted = Color(0xFFF4F2ED)
val BorderSubtle = Color(0xFFEBE8E1)

val CharcoalText = Color(0xFF111713)
val MutedText = Color(0xFF26322B)
val SubtleText = Color(0xFF38463D)

val DangerRed = Color(0xFFC04B4B)
val DangerBg = Color(0xFFFDF0F0)
val SuccessGreen = Color(0xFF388E3C)

private val LightColorScheme = lightColorScheme(
    primary = SageGreen,
    onPrimary = Color.White,
    primaryContainer = SageLight,
    onPrimaryContainer = SageDark,
    secondary = SageDark,
    onSecondary = Color.White,
    background = WarmBg,
    onBackground = CharcoalText,
    surface = SurfaceCard,
    onSurface = CharcoalText,
    surfaceVariant = SurfaceCardMuted,
    onSurfaceVariant = MutedText,
    outline = BorderSubtle,
    error = DangerRed,
    onError = Color.White,
)

private val DarkColorScheme = darkColorScheme(
    primary = SageGreen,
    onPrimary = Color.White,
    primaryContainer = SageDark,
    onPrimaryContainer = SageLight,
    secondary = SageLight,
    onSecondary = CharcoalText,
    background = Color(0xFF141715),
    onBackground = Color(0xFFEBECEB),
    surface = Color(0xFF1C201E),
    onSurface = Color(0xFFEBECEB),
    surfaceVariant = Color(0xFF262C29),
    onSurfaceVariant = Color(0xFFA5ACA7),
    outline = Color(0xFF333B36),
    error = DangerRed,
    onError = Color.White,
)

@Composable
fun MindVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
