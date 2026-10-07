package com.aliahad.ainewshot.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.aliahad.ainewshot.Settings

/** aihot.news's light theme: warm off-white paper, deep teal accent, amber highlights (its :root CSS variables). */
val WarmLight = lightColorScheme(
    primary = Color(0xFF176B75), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDEBEA), onPrimaryContainer = Color(0xFF145F68),
    secondary = Color(0xFF526F72), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6ECE8), onSecondaryContainer = Color(0xFF303C42),
    tertiary = Color(0xFFB8873A), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF4EAD6), onTertiaryContainer = Color(0xFF7C5A24),
    background = Color(0xFFFAF9F6), onBackground = Color(0xFF202A30),
    surface = Color(0xFFFAF9F6), onSurface = Color(0xFF202A30),
    surfaceVariant = Color(0xFFE9EDE9), onSurfaceVariant = Color(0xFF59656B),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFDFDFB),
    surfaceContainer = Color(0xFFF3F3EF), surfaceContainerHigh = Color(0xFFEEF1ED), surfaceContainerHighest = Color(0xFFE9EDE9),
    surfaceBright = Color(0xFFFDFDFB), surfaceDim = Color(0xFFF0F2EE),
    inverseSurface = Color(0xFF202A30), inverseOnSurface = Color(0xFFFAF9F6), inversePrimary = Color(0xFF12CCD8),
    outline = Color(0xFFB9C3BE), outlineVariant = Color(0xFFDFE4E1),
    error = Color(0xFFB3402A), onError = Color.White, errorContainer = Color(0xFFF6DFD8), onErrorContainer = Color(0xFF7A2A1B),
)

/** aihot.news's dark theme ([data-theme=dark]): slate, bright teal accent. */
val AihotDark = darkColorScheme(
    primary = Color(0xFF12CCD8), onPrimary = Color.Black,
    primaryContainer = Color(0xFF143A40), onPrimaryContainer = Color(0xFF2CE2E8),
    secondary = Color(0xFF8FB8A8), onSecondary = Color.Black,
    secondaryContainer = Color(0xFF24333A), onSecondaryContainer = Color(0xFFD2DADA),
    tertiary = Color(0xFFD3B26A), onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF3A3322), onTertiaryContainer = Color(0xFFD3B26A),
    background = Color(0xFF13191C), onBackground = Color(0xFFE7ECEB),
    surface = Color(0xFF13191C), onSurface = Color(0xFFE7ECEB),
    surfaceVariant = Color(0xFF202A2F), onSurfaceVariant = Color(0xFFA5B0B3),
    surfaceContainerLowest = Color(0xFF0F1416), surfaceContainerLow = Color(0xFF161E21),
    surfaceContainer = Color(0xFF1A2226), surfaceContainerHigh = Color(0xFF1D272B), surfaceContainerHighest = Color(0xFF202A2F),
    surfaceBright = Color(0xFF26323A), surfaceDim = Color(0xFF13191C),
    inverseSurface = Color(0xFFE7ECEB), inverseOnSurface = Color(0xFF13191C), inversePrimary = Color(0xFF176B75),
    outline = Color(0xFF3E4A50), outlineVariant = Color(0xFF2A3439),
    error = Color(0xFFD86A52), onError = Color.Black, errorContainer = Color(0xFF4A2620), onErrorContainer = Color(0xFFF1C0B4),
)

/** Settings › appearance: light (warm, the site's) | dark | system (follows the phone) | phone (Material You colours). */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val s = Settings.get(ctx)
    val v by s.version.collectAsState()
    val mode = if (v >= 0) s.theme else "system"
    val dark = when (mode) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    val scheme = when {
        mode == "phone" -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> AihotDark
        else -> WarmLight
    }
    // Status- and navigation-bar icons follow the app's theme, not the phone's.
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
