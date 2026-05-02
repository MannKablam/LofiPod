package com.lofipod.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
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
    enum class Kind { Cassette, Reel, Dmg, Ticker }
}

val LocalLofiThemeSpec = compositionLocalOf<LofiThemeSpec> {
    error("LocalLofiThemeSpec not provided — wrap content in LofiPodTheme {}")
}

/** Convenience accessor for screens. */
val lofiTheme: LofiThemeSpec
    @Composable @ReadOnlyComposable
    get() = LocalLofiThemeSpec.current

// ---------- Cassette · Direction B (current look, kept as default) ----------
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
    onSurfaceVariant   = Color(0xFF9B9180)
)

// ---------- Reel-to-Reel · Direction D ----------
// Studio tape machine. Cream faceplate is the dominant *surface*, walnut chassis
// is the background. Oxblood is the lone accent.
private val ReelScheme: ColorScheme = darkColorScheme(
    primary            = Color(0xFF7A2E22),  // oxblood — the spot accent
    onPrimary          = Color(0xFFE8DCC0),
    primaryContainer   = Color(0xFF4A1B14),
    onPrimaryContainer = Color(0xFFE8DCC0),
    secondary          = Color(0xFFB8893E),  // brass
    background         = Color(0xFF1B1410),  // walnut chassis
    surface            = Color(0xFFE8DCC0),  // cream faceplate
    surfaceVariant     = Color(0xFFD8C9A6),
    onBackground       = Color(0xFFE8DCC0),
    onSurface          = Color(0xFF2A1F18),  // ink on cream
    onSurfaceVariant   = Color(0xFF5C4A38),
    outline            = Color(0xFFA89472),
)

// ---------- DMG Handheld · Direction E ----------
// Four-ink LCD. Pixel font everywhere on screen. Magenta is hardware-only — we
// route it to onPrimary so it never lands on body text.
private val DmgScheme: ColorScheme = darkColorScheme(
    primary            = Color(0xFF0F380F),  // darkest ink
    onPrimary          = Color(0xFF9BBC0F),  // lightest screen
    primaryContainer   = Color(0xFF8BAC0F),  // selected fill
    onPrimaryContainer = Color(0xFF0F380F),
    secondary          = Color(0xFF306230),  // mid-dark
    background         = Color(0xFF9BBC0F),  // LCD lightest
    surface            = Color(0xFF8BAC0F),  // LCD light
    surfaceVariant     = Color(0xFF8BAC0F),
    onBackground       = Color(0xFF0F380F),
    onSurface          = Color(0xFF0F380F),
    onSurfaceVariant   = Color(0xFF306230),
    outline            = Color(0xFF0F380F),
    error              = Color(0xFF7A2E62),  // magenta — chrome-only spot
)

// ---------- Ticker Tape · Direction F ----------
// Monochrome newsroom paper. Red is the only spot color.
private val TickerScheme: ColorScheme = darkColorScheme(
    primary            = Color(0xFF9E2A1B),  // red spot
    onPrimary          = Color(0xFFF4EFE2),
    primaryContainer   = Color(0xFF5C1A11),
    onPrimaryContainer = Color(0xFFF4EFE2),
    secondary          = Color(0xFF1B1A18),  // ink as secondary
    background         = Color(0xFFF4EFE2),  // paper
    surface            = Color(0xFFE5DCC2),  // shaded paper
    surfaceVariant     = Color(0xFFE5DCC2),
    onBackground       = Color(0xFF1B1A18),
    onSurface          = Color(0xFF1B1A18),
    onSurfaceVariant   = Color(0xFF5A554A),
    outline            = Color(0xFFB8AE93),
)

// ---------- Daylight ----------
// Plain, bright, maximum-contrast theme for outdoor / direct-sunlight reading.
// White background, near-black text, saturated-but-not-screaming blue accent.
// No decorative fonts — sans-serif everywhere for legibility on a phone screen
// you can barely see in noon sun.
private val DaylightScheme: ColorScheme = darkColorScheme(
    primary            = Color(0xFF0E63C8),  // crisp blue accent
    onPrimary          = Color(0xFFFFFFFF),
    primaryContainer   = Color(0xFFD3E4FC),
    onPrimaryContainer = Color(0xFF062E5F),
    secondary          = Color(0xFF3D6075),
    background         = Color(0xFFFFFFFF),
    surface            = Color(0xFFF5F5F7),
    surfaceVariant     = Color(0xFFE6E6EA),
    onBackground       = Color(0xFF101114),
    onSurface          = Color(0xFF101114),
    onSurfaceVariant   = Color(0xFF44464C),
    outline            = Color(0xFF74767C),
)

// ---------- Lowlight ----------
// Plain, dim, eye-friendly theme for nighttime / dark-room use. Background is a
// near-black charcoal (not pure #000 — true black on OLED can produce smear and
// halo with bright accents). Text is a warm off-white. Accent is a desaturated
// amber so the screen emits less blue light at the wavelengths that interfere
// with sleep.
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
    LofiTheme.DMG -> LofiThemeSpec(
        theme = theme,
        // The pixel font is wide — using it for display only keeps the body
        // legible while still announcing the theme on the wordmark.
        colors = DmgScheme,
        displayFont = PressStart2P,
        bodyFont = FontFamily.Default,
        accent = Color(0xFF0F380F),
        onAccent = Color(0xFF9BBC0F),
        artworkPlaceholderFill = Color(0xFF9BBC0F),
        artworkPlaceholderInk = Color(0xFF0F380F),
        kind = LofiThemeSpec.Kind.Dmg,
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
