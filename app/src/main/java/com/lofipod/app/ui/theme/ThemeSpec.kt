package com.lofipod.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.lofipod.app.data.LofiTheme

/**
 * Direction-specific tokens read by screens that want to adopt a theme's flavor
 * (display font choice, accent color, chrome kind for the artwork fallback, etc).
 *
 * Material's [ColorScheme] still drives the bulk of the UI — this just layers on
 * the bits Material doesn't model directly: a "display" font that overrides
 * the wordmark/title family per direction, a chip color for stamps, and a
 * [kind] enum that decorative composables (placeholder art, mini-player accent)
 * dispatch on.
 */
data class LofiThemeSpec(
    val theme: LofiTheme,
    val colors: ColorScheme,
    val displayFont: FontFamily,
    val bodyFont: FontFamily,
    val accent: Color,
    val onAccent: Color,
    val artworkPlaceholderFill: Color,
    val artworkPlaceholderInk: Color,
    val kind: Kind,
) {
    enum class Kind { Cassette, Reel, Ticker }
}

val LocalLofiThemeSpec = compositionLocalOf<LofiThemeSpec> {
    error("LocalLofiThemeSpec not provided — wrap content in LofiPodTheme {}")
}

/** Convenience accessor for screens. */
val lofiTheme: LofiThemeSpec
    @Composable @ReadOnlyComposable
    get() = LocalLofiThemeSpec.current

// ---------------------------------------------------------------------------
// Color schemes.
//
// Every scheme explicitly fills in `surfaceContainerLowest/Low/Container/
// High/Highest` because M3 dialogs, dropdown menus, and the bottom sheet
// palette pull their backgrounds from those slots — not from `surface`. If
// they're left to the factory's auto-derivation, a light-palette theme built
// with `darkColorScheme(...)` (or vice versa) ends up with popups that look
// nothing like the rest of the app. This was the "popup window background
// looks dark/inconsistent" symptom on Daylight. Each theme picks the factory
// (`light` or `dark`) that matches the dominant content surface, then nails
// the container ladder by hand so popups land on the right shade.
// ---------------------------------------------------------------------------

// ---------- Cassette · Direction B (the original look) ----------
private val CassetteScheme: ColorScheme = darkColorScheme(
    primary            = Color(0xFFE6B469),  // amber
    onPrimary          = Color(0xFF1A1B2E),
    primaryContainer   = Color(0xFF4A3B1F),
    onPrimaryContainer = Color(0xFFFFE3B1),
    secondary          = Color(0xFF7BB4C4),  // teal
    background         = Color(0xFF1A1B2E),  // navy
    surface            = Color(0xFF25243A),
    surfaceVariant     = Color(0xFF2F2E48),
    onBackground       = Color(0xFFEDE5D6),
    onSurface          = Color(0xFFEDE5D6),
    onSurfaceVariant   = Color(0xFF9B9180),
    surfaceContainerLowest  = Color(0xFF15162A),
    surfaceContainerLow     = Color(0xFF1F1F36),
    surfaceContainer        = Color(0xFF25243A),
    surfaceContainerHigh    = Color(0xFF2F2E48),
    surfaceContainerHighest = Color(0xFF3A3A56),
)

// ---------- Reel-to-Reel · Direction D ----------
// Cream-on-walnut studio look. Content surfaces are cream (light), so the
// popup ladder lives on the light side; the walnut background frames it.
private val ReelScheme: ColorScheme = lightColorScheme(
    primary            = Color(0xFF7A2E22),  // oxblood — the spot accent
    onPrimary          = Color(0xFFE8DCC0),
    primaryContainer   = Color(0xFFD8C9A6),
    onPrimaryContainer = Color(0xFF2A1F18),
    secondary          = Color(0xFFB8893E),  // brass
    background         = Color(0xFF1B1410),  // walnut chassis
    surface            = Color(0xFFE8DCC0),  // cream faceplate
    surfaceVariant     = Color(0xFFD8C9A6),
    onBackground       = Color(0xFFE8DCC0),
    onSurface          = Color(0xFF2A1F18),  // ink on cream
    onSurfaceVariant   = Color(0xFF5C4A38),
    outline            = Color(0xFFA89472),
    surfaceContainerLowest  = Color(0xFFF2E9D6),
    surfaceContainerLow     = Color(0xFFEDE2C8),
    surfaceContainer        = Color(0xFFE8DCC0),
    surfaceContainerHigh    = Color(0xFFE0D2B0),
    surfaceContainerHighest = Color(0xFFD8C9A6),
)

// ---------- Ticker Tape · Direction F ----------
// Newsroom paper. Light surfaces throughout, red is the only spot color.
private val TickerScheme: ColorScheme = lightColorScheme(
    primary            = Color(0xFF9E2A1B),  // red spot
    onPrimary          = Color(0xFFF4EFE2),
    primaryContainer   = Color(0xFFE5DCC2),
    onPrimaryContainer = Color(0xFF1B1A18),
    secondary          = Color(0xFF1B1A18),  // ink as secondary
    background         = Color(0xFFF4EFE2),  // paper
    surface            = Color(0xFFEAE2CC),
    surfaceVariant     = Color(0xFFE5DCC2),
    onBackground       = Color(0xFF1B1A18),
    onSurface          = Color(0xFF1B1A18),
    onSurfaceVariant   = Color(0xFF5A554A),
    outline            = Color(0xFFB8AE93),
    surfaceContainerLowest  = Color(0xFFFAF5E8),
    surfaceContainerLow     = Color(0xFFF0EAD8),
    surfaceContainer        = Color(0xFFEAE2CC),
    surfaceContainerHigh    = Color(0xFFE5DCC2),
    surfaceContainerHighest = Color(0xFFDDD2B5),
)

// ---------- Daylight ----------
// Plain, bright, maximum-contrast theme for outdoor / direct-sunlight reading.
// Built on `lightColorScheme` (was darkColorScheme — that's why menus/dialogs
// ended up dark) and the surfaceContainer ladder is white-leaning so popups
// stay clean.
private val DaylightScheme: ColorScheme = lightColorScheme(
    primary            = Color(0xFF0E63C8),  // crisp blue accent
    onPrimary          = Color(0xFFFFFFFF),
    primaryContainer   = Color(0xFFD3E4FC),
    onPrimaryContainer = Color(0xFF062E5F),
    secondary          = Color(0xFF3D6075),
    background         = Color(0xFFFFFFFF),
    surface            = Color(0xFFF5F5F7),
    surfaceVariant     = Color(0xFFE6E6EA),
    surfaceTint        = Color.Transparent,
    onBackground       = Color(0xFF101114),
    onSurface          = Color(0xFF101114),
    onSurfaceVariant   = Color(0xFF44464C),
    outline            = Color(0xFF74767C),
    surfaceContainerLowest  = Color(0xFFFFFFFF),
    surfaceContainerLow     = Color(0xFFFAFAFC),
    surfaceContainer        = Color(0xFFF5F5F7),
    surfaceContainerHigh    = Color(0xFFEFEFF2),
    surfaceContainerHighest = Color(0xFFE6E6EA),
)

// ---------- Lowlight (default) ----------
// Plain, dim, eye-friendly theme for nighttime / dark-room use. Background is
// near-black charcoal (not pure #000 — true black on OLED can produce smear
// and halo with bright accents). Text is warm off-white. Accent is a
// desaturated amber so the screen emits less blue light at the wavelengths
// that interfere with sleep.
private val LowlightScheme: ColorScheme = darkColorScheme(
    primary            = Color(0xFFD7A45C),  // warm amber, low blue
    onPrimary          = Color(0xFF1A130A),
    primaryContainer   = Color(0xFF3A2A12),
    onPrimaryContainer = Color(0xFFEFD4A6),
    secondary          = Color(0xFF8A7252),
    background         = Color(0xFF0E0E10),  // near-black charcoal
    surface            = Color(0xFF17171A),
    surfaceVariant     = Color(0xFF202024),
    onBackground       = Color(0xFFE8DDC9),  // warm off-white
    onSurface          = Color(0xFFE8DDC9),
    onSurfaceVariant   = Color(0xFF9A9286),
    outline            = Color(0xFF4A4640),
    surfaceContainerLowest  = Color(0xFF0A0A0C),
    surfaceContainerLow     = Color(0xFF131316),
    surfaceContainer        = Color(0xFF17171A),
    surfaceContainerHigh    = Color(0xFF202024),
    surfaceContainerHighest = Color(0xFF2A2A30),
)

fun specFor(theme: LofiTheme): LofiThemeSpec = when (theme) {
    LofiTheme.CASSETTE -> LofiThemeSpec(
        theme = theme,
        colors = CassetteScheme,
        displayFont = PressStart2P,
        bodyFont = FontFamily.Default,
        accent = Color(0xFFE6B469),
        onAccent = Color(0xFF1A1B2E),
        artworkPlaceholderFill = Color(0xFF2F2E48),
        artworkPlaceholderInk = Color(0xFFE6B469),
        kind = LofiThemeSpec.Kind.Cassette,
    )
    LofiTheme.REEL -> LofiThemeSpec(
        theme = theme,
        colors = ReelScheme,
        displayFont = FontFamily.Monospace,
        bodyFont = FontFamily.Default,
        accent = Color(0xFF7A2E22),
        onAccent = Color(0xFFE8DCC0),
        artworkPlaceholderFill = Color(0xFFE8DCC0),
        artworkPlaceholderInk = Color(0xFF2A1F18),
        kind = LofiThemeSpec.Kind.Reel,
    )
    LofiTheme.TICKER -> LofiThemeSpec(
        theme = theme,
        colors = TickerScheme,
        displayFont = FontFamily.Monospace,
        bodyFont = FontFamily.Default,
        accent = Color(0xFF9E2A1B),
        onAccent = Color(0xFFF4EFE2),
        artworkPlaceholderFill = Color(0xFFE5DCC2),
        artworkPlaceholderInk = Color(0xFF1B1A18),
        kind = LofiThemeSpec.Kind.Ticker,
    )
    LofiTheme.DAYLIGHT -> LofiThemeSpec(
        theme = theme,
        colors = DaylightScheme,
        // Plain themes use the system default sans for both display and body —
        // these are about readability, not visual character. Reuse the Ticker
        // placeholder kind (paper-stamp look) since it reads cleanly on a
        // light surface; tinted to the Daylight palette via the placeholder
        // fill/ink fields below.
        displayFont = FontFamily.Default,
        bodyFont = FontFamily.Default,
        accent = Color(0xFF0E63C8),
        onAccent = Color(0xFFFFFFFF),
        artworkPlaceholderFill = Color(0xFFE6E6EA),
        artworkPlaceholderInk = Color(0xFF101114),
        kind = LofiThemeSpec.Kind.Ticker,
    )
    LofiTheme.LOWLIGHT -> LofiThemeSpec(
        theme = theme,
        colors = LowlightScheme,
        displayFont = FontFamily.Default,
        bodyFont = FontFamily.Default,
        accent = Color(0xFFD7A45C),
        onAccent = Color(0xFF1A130A),
        // Reuse the Cassette spool placeholder — its filled-square look reads
        // well against the near-black chassis at low brightness.
        artworkPlaceholderFill = Color(0xFF202024),
        artworkPlaceholderInk = Color(0xFFD7A45C),
        kind = LofiThemeSpec.Kind.Cassette,
    )
}
