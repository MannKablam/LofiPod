package com.lofipod.app.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.lofipod.app.R

/**
 * Bundled body-font families. Both are OFL-licensed Garamond revivals shipped
 * as variable TTFs (single file covers Regular through Bold via the wght
 * axis). License files live at `assets/EBGaramond-OFL.txt` +
 * `assets/CormorantGaramond-OFL.txt` per OFL section 1 ("Copyright notices
 * must be preserved" — license text shipped with software, no UI surface
 * required).
 *
 * Usage: select via `Settings.bodyFontChoice`; resolved by [bodyFamilyFor]
 * into a [FontFamily] that gets folded into the active Material typography
 * by `LofiPodTheme`. Theme-default and Garamond options give the user a
 * substantial typography choice without bundling more than ~2 MB.
 */
val EBGaramond: FontFamily = FontFamily(Font(R.font.eb_garamond))

val CormorantGaramond: FontFamily = FontFamily(Font(R.font.cormorant_garamond))

/**
 * User-selectable body-font choice. SYSTEM_* maps to Compose's generic
 * families (no binary dependency, varies by OEM). EB_GARAMOND +
 * CORMORANT_GARAMOND map to the bundled OFL fonts (consistent rendering
 * across devices). [THEME_DEFAULT] keeps the active theme's `bodyFont`
 * untouched — the user hasn't expressed a preference, so the theme spec
 * picks what looks right for that direction.
 */
enum class BodyFontChoice {
    THEME_DEFAULT,
    SYSTEM_DEFAULT,
    SYSTEM_SERIF,
    SYSTEM_MONOSPACE,
    EB_GARAMOND,
    CORMORANT_GARAMOND;

    companion object {
        fun fromKey(key: String?): BodyFontChoice =
            entries.firstOrNull { it.name == key } ?: THEME_DEFAULT
    }
}

/**
 * Resolve a [BodyFontChoice] to a concrete [FontFamily]. Returns null for
 * [BodyFontChoice.THEME_DEFAULT] so the caller knows to leave the theme
 * spec's `bodyFont` in place rather than overriding.
 */
fun bodyFamilyFor(choice: BodyFontChoice): FontFamily? = when (choice) {
    BodyFontChoice.THEME_DEFAULT -> null
    BodyFontChoice.SYSTEM_DEFAULT -> FontFamily.Default
    BodyFontChoice.SYSTEM_SERIF -> FontFamily.Serif
    BodyFontChoice.SYSTEM_MONOSPACE -> FontFamily.Monospace
    BodyFontChoice.EB_GARAMOND -> EBGaramond
    BodyFontChoice.CORMORANT_GARAMOND -> CormorantGaramond
}

fun BodyFontChoice.displayLabel(): String = when (this) {
    BodyFontChoice.THEME_DEFAULT -> "Theme default"
    BodyFontChoice.SYSTEM_DEFAULT -> "System sans"
    BodyFontChoice.SYSTEM_SERIF -> "System serif"
    BodyFontChoice.SYSTEM_MONOSPACE -> "System monospace"
    BodyFontChoice.EB_GARAMOND -> "EB Garamond"
    BodyFontChoice.CORMORANT_GARAMOND -> "Cormorant Garamond"
}
