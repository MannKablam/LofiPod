package com.lofipod.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import com.lofipod.app.data.LofiTheme
import com.lofipod.app.data.Settings

/**
 * Builds a Typography that swaps the body font in for whichever direction is
 * active. Display sizes get a slightly tighter line-height because pixel and
 * monospace fonts are visually wider than sans.
 *
 * If [bodyOverride] is non-null, it replaces `spec.bodyFont` for every
 * non-display slot — the user has explicitly picked a font on the Text
 * settings screen, so their preference outranks the theme spec's default.
 * The display slots (titles + headlines + display) stay on `spec.displayFont`
 * because that's the theme-direction's character.
 */
private fun typographyFor(spec: LofiThemeSpec, bodyOverride: FontFamily?): Typography {
    val base = Typography()
    val bodyFamily = bodyOverride ?: spec.bodyFont
    fun TextStyle.withFamily(f: FontFamily) = copy(fontFamily = f)
    return Typography(
        displayLarge   = base.displayLarge.withFamily(spec.displayFont),
        displayMedium  = base.displayMedium.withFamily(spec.displayFont),
        displaySmall   = base.displaySmall.withFamily(spec.displayFont),
        headlineLarge  = base.headlineLarge.withFamily(spec.displayFont),
        headlineMedium = base.headlineMedium.withFamily(spec.displayFont),
        headlineSmall  = base.headlineSmall.withFamily(spec.displayFont),
        titleLarge     = base.titleLarge.withFamily(bodyFamily),
        titleMedium    = base.titleMedium.withFamily(bodyFamily),
        titleSmall     = base.titleSmall.withFamily(bodyFamily),
        bodyLarge      = base.bodyLarge.withFamily(bodyFamily),
        bodyMedium     = base.bodyMedium.withFamily(bodyFamily),
        bodySmall      = base.bodySmall.withFamily(bodyFamily),
        labelLarge     = base.labelLarge.withFamily(bodyFamily),
        labelMedium    = base.labelMedium.withFamily(bodyFamily),
        labelSmall     = base.labelSmall.withFamily(bodyFamily),
    )
}

@Composable
fun LofiPodTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val selectedTheme by settings.theme.collectAsState(initial = LofiTheme.LOWLIGHT)
    val textScale by settings.textScale.collectAsState(initial = 1.0f)
    val bodyFontKey by settings.bodyFontChoiceKey.collectAsState(initial = "THEME_DEFAULT")
    val bodyFontOverride = remember(bodyFontKey) {
        bodyFamilyFor(BodyFontChoice.fromKey(bodyFontKey))
    }
    val spec = specFor(selectedTheme)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = spec.colors.background.toArgb()
            window.navigationBarColor = spec.colors.background.toArgb()
        }
    }

    // Override the ambient density so every sp-defined text style scales by
    // settings.textScale. Layout density (dp) stays untouched — only fontScale
    // changes — so text grows/shrinks but icons and spacing stay put.
    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity, textScale) {
        Density(density = baseDensity.density, fontScale = baseDensity.fontScale * textScale)
    }

    CompositionLocalProvider(
        LocalLofiThemeSpec provides spec,
        LocalDensity provides scaledDensity
    ) {
        MaterialTheme(
            colorScheme = spec.colors,
            typography = typographyFor(spec, bodyFontOverride),
            content = content
        )
    }
}
