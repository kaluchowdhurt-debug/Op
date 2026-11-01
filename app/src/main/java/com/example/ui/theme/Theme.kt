package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NovaBotDarkColorScheme = darkColorScheme(
    primary = NovaCyan,
    onPrimary = SpaceObsidian,
    primaryContainer = SpaceElevatedSurface,
    onPrimaryContainer = NovaCyan,
    secondary = EnergyViolet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF281E4A),
    onSecondaryContainer = Color(0xFFDCD0FF),
    tertiary = NeonEmerald,
    onTertiary = SpaceObsidian,
    background = SpaceObsidian,
    onBackground = TextPrimary,
    surface = SpaceDarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = SpaceCardSurface,
    onSurfaceVariant = TextSecondary,
    outline = BorderSubtle,
    error = CoralAlert,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = NovaBotDarkColorScheme,
        typography = Typography,
        content = content
    )
}
