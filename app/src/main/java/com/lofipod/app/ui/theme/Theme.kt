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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import com.lofipod.app.data.LofiTheme
import com.lofipod.app.data.Settings

/**
 * Builds a Typography that swaps the body font in for whichever direction is
 * active. Display sizes get a slightly tighter line-height because pixel and
 * monospace fonts are visually wider than sans.
 */
private fun typographyFor(spec: LofiThemeSpec): Typography {
    val base = Typography()
    fun TextStyle.withFamily(f: FontFamily) = copy(fontFamily = f)
    return Typography(
        displayLarge   = base.displayLarge.withFamily(spec.displayFont),
        displayMedium  = base.displayMedium.withFamily(spec.displayFont),
        displaySmall   = base.displaySmall.withFamily(spec.displayFont),
        headlineLarge  = base.headlineLarge.withFamily(spec.displayFont),
        headlineMedium = base.headlineMedium.withFamily(spec.displayFont),
        headlineSmall  = base.headlineSmall.withFamily(spec.displayFont),
        titleLarge     = base.titleLarge.withFamily(spec.bodyFont),
        titleMedium    = base.titleMedium.withFamily(spec.bodyFont),
        titleSmall     = base.titleSmall.withFamily(spec.bodyFont),
        bodyLarge      = base.bodyLarge.withFamily(spec.bodyFont),
        bodyMedium     = base.bodyMedium.withFamily(spec.bodyFont),
        bodySmall      = base.bodySmall.withFamily(spec.bodyFont),
        labelLarge     = base.labelLarge.withFamily(spec.bodyFont),
        labelMedium    = base.labelMedium.withFamily(spec.bodyFont),
        labelSmall     = base.labelSmall.withFamily(spec.bodyFont),
    )
}

@Composable
fun LofiPodTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val selectedTheme by settings.theme.collectAsState(initial = LofiTheme.CASSETTE)
    val spec = specFor(selectedTheme)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = spec.colors.background.toArgb()
            window.navigationBarColor = spec.colors.background.toArgb()
        }
    }

    CompositionLocalProvider(LocalLofiThemeSpec provides spec) {
        MaterialTheme(
            colorScheme = spec.colors,
            typography = typographyFor(spec),
            content = content
        )
    }
}
