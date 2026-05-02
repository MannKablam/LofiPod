package com.lofipod.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Lofi palette — cool, dusky, chillhop / 16-bit twilight.
// Deep navy backgrounds, warm amber/honey accent, muted teal secondary.
private val LofiBgDark      = Color(0xFF1A1B2E)   // deep night-sky navy
private val LofiSurface     = Color(0xFF25243A)   // dusky indigo
private val LofiSurfaceHi   = Color(0xFF2F2E48)   // muted purple-grey
private val LofiPrimary     = Color(0xFFE6B469)   // warm amber / honey
private val LofiSecondary   = Color(0xFF7BB4C4)   // muted teal
private val LofiOnPrimary   = Color(0xFF1A1B2E)   // navy on amber
private val LofiText        = Color(0xFFEDE5D6)   // warm cream
private val LofiTextMuted   = Color(0xFF9B9180)   // dusty taupe

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

// Light scheme: same dusky vibe, cream-tan backdrop, deeper amber primary.
private val LightScheme = lightColorScheme(
    primary = Color(0xFF8B6E3C),         // deeper bronze/amber
    onPrimary = Color(0xFFFAF4E8),
    secondary = Color(0xFF4F8A99),       // deeper teal
    background = Color(0xFFF2EBE0),      // warm cream
    surface = Color(0xFFE8DFD0),         // light tan
    onBackground = Color(0xFF25243A),    // dusky indigo text
    onSurface = Color(0xFF25243A)
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
