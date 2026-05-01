package com.lofipod.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Lofi palette — warm, dusty, slightly washed out
private val LofiBgDark      = Color(0xFF14110F)
private val LofiSurface     = Color(0xFF1E1A17)
private val LofiSurfaceHi   = Color(0xFF2A2421)
private val LofiPrimary     = Color(0xFFD4A373)   // warm tan
private val LofiSecondary   = Color(0xFF8FA67E)   // muted sage
private val LofiOnPrimary   = Color(0xFF1B1410)
private val LofiText        = Color(0xFFEDE0D4)
private val LofiTextMuted   = Color(0xFF9C8E81)

private val DarkScheme = darkColorScheme(
    primary = LofiPrimary,
    onPrimary = LofiOnPrimary,
    secondary = LofiSecondary,
    background = LofiBgDark,
    surface = LofiSurface,
    surfaceVariant = LofiSurfaceHi,
    onBackground = LofiText,
    onSurface = LofiText,
    onSurfaceVariant = LofiTextMuted
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF8B5E3C),
    secondary = LofiSecondary,
    background = Color(0xFFF5EFE6),
    surface = Color(0xFFEFE7DA),
    onBackground = Color(0xFF2A2421),
    onSurface = Color(0xFF2A2421)
)

@Composable
fun LofiPodTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content
    )
}
