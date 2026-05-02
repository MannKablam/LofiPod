package com.lofipod.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.lofipod.app.data.LofiTheme
import com.lofipod.app.data.Settings

// ---------- Lofi Twilight (default) ----------
private val TwilightDark = darkColorScheme(
    primary           = Color(0xFFE6B469),    // amber
    onPrimary         = Color(0xFF1A1B2E),
    primaryContainer  = Color(0xFF4A3B1F),
    onPrimaryContainer= Color(0xFFFFE3B1),
    secondary         = Color(0xFF7BB4C4),    // teal
    background        = Color(0xFF1A1B2E),    // navy
    surface           = Color(0xFF25243A),
    surfaceVariant    = Color(0xFF2F2E48),
    onBackground      = Color(0xFFEDE5D6),
    onSurface         = Color(0xFFEDE5D6),
    onSurfaceVariant  = Color(0xFF9B9180)
)

// ---------- Forest Floor ----------
private val ForestDark = darkColorScheme(
    primary           = Color(0xFFC7B07A),    // wheat
    onPrimary         = Color(0xFF1A2418),
    primaryContainer  = Color(0xFF3F3923),
    onPrimaryContainer= Color(0xFFEDDDB6),
    secondary         = Color(0xFF8FA67E),    // sage
    background        = Color(0xFF1A2418),    // deep forest
    surface           = Color(0xFF243023),
    surfaceVariant    = Color(0xFF2E3D2D),
    onBackground      = Color(0xFFE5E0D6),
    onSurface         = Color(0xFFE5E0D6),
    onSurfaceVariant  = Color(0xFF9B9985)
)

// ---------- Coral Reef ----------
private val CoralDark = darkColorScheme(
    primary           = Color(0xFFE89479),    // coral
    onPrimary         = Color(0xFF1A2832),
    primaryContainer  = Color(0xFF4A3026),
    onPrimaryContainer= Color(0xFFFFD4C2),
    secondary         = Color(0xFF6FB4B0),    // reef teal
    background        = Color(0xFF1A2832),    // deep sea
    surface           = Color(0xFF253848),
    surfaceVariant    = Color(0xFF2F4456),
    onBackground      = Color(0xFFE5DFD6),
    onSurface         = Color(0xFFE5DFD6),
    onSurfaceVariant  = Color(0xFF8E9DA6)
)

// ---------- Game Boy (DMG palette riff) ----------
private val GameBoyDark = darkColorScheme(
    primary           = Color(0xFF9BBC0F),    // DMG bright green
    onPrimary         = Color(0xFF0F1A0E),
    primaryContainer  = Color(0xFF4A6B47),
    onPrimaryContainer= Color(0xFFC7E89A),
    secondary         = Color(0xFFC7E89A),    // DMG light green
    background        = Color(0xFF0F1A0E),    // very dark green
    surface           = Color(0xFF2C3E2A),
    surfaceVariant    = Color(0xFF4A6B47),
    onBackground      = Color(0xFFE0F0D8),
    onSurface         = Color(0xFFE0F0D8),
    onSurfaceVariant  = Color(0xFF84A07F)
)

// Light mode is shared across themes for now — same warm cream backdrop.
private val LightScheme = lightColorScheme(
    primary           = Color(0xFF8B6E3C),
    onPrimary         = Color(0xFFFAF4E8),
    secondary         = Color(0xFF4F8A99),
    background        = Color(0xFFF2EBE0),
    surface           = Color(0xFFE8DFD0),
    surfaceVariant    = Color(0xFFDED4C2),
    onBackground      = Color(0xFF25243A),
    onSurface         = Color(0xFF25243A)
)

private fun darkSchemeFor(theme: LofiTheme): ColorScheme = when (theme) {
    LofiTheme.TWILIGHT -> TwilightDark
    LofiTheme.FOREST   -> ForestDark
    LofiTheme.CORAL    -> CoralDark
    LofiTheme.GAMEBOY  -> GameBoyDark
}

@Composable
fun LofiPodTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val selectedTheme by settings.theme.collectAsState(initial = LofiTheme.TWILIGHT)

    val scheme = if (darkTheme) darkSchemeFor(selectedTheme) else LightScheme

    // Push the chosen background into the system bars so the chrome matches.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = scheme.background.toArgb()
            window.navigationBarColor = scheme.background.toArgb()
        }
    }

    MaterialTheme(colorScheme = scheme, content = content)
}
