package com.example.learning.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * TfNSW High-Contrast Material 3 Theme
 *
 * Primary: Metro Teal   | Secondary: Train Orange | Tertiary: Bus Blue
 *
 * Logic:
 * - Light Mode: Uses Tone 40 for core roles to ensure 4.5:1 contrast ratio.
 * - Dark Mode: Uses Tone 80 for core roles to ensure readability against dark surfaces.
 * - Surfaces: Neutral-Teal mix for brand cohesion.
 */

private val LightColorScheme = lightColorScheme(
    // Metro Teal (Tone 40 for accessibility)
    primary = Color(0xFF006A6D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF6FF6F9),
    onPrimaryContainer = Color(0xFF002021),

    // Train Orange (Tone 40 - darkened significantly for text contrast)
    secondary = Color(0xFF914D00),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDCC0),
    onSecondaryContainer = Color(0xFF2F1500),

    // Bus Blue (Tone 40)
    tertiary = Color(0xFF006684),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBFE9FF),
    onTertiaryContainer = Color(0xFF001F2A),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    // Neutrals: Teal-tinted whites/greys
    background = Color(0xFFF4FBFA),
    onBackground = Color(0xFF161D1D),
    surface = Color(0xFFF4FBFA),
    onSurface = Color(0xFF161D1D),
    surfaceVariant = Color(0xFFDAE5E4),
    onSurfaceVariant = Color(0xFF3F4949),
    outline = Color(0xFF6F7979),
    outlineVariant = Color(0xFFBEC9C8),
)

private val DarkColorScheme = darkColorScheme(
    // Metro Teal (Tone 80 - glowing but legible)
    primary = Color(0xFF4CD9DC),
    onPrimary = Color(0xFF003738),
    primaryContainer = Color(0xFF004F51),
    onPrimaryContainer = Color(0xFF6FF6F9),

    // Train Orange (Tone 80 - vibrant accent)
    secondary = Color(0xFFFFB87A),
    onSecondary = Color(0xFF4E2600),
    secondaryContainer = Color(0xFF6F3900),
    onSecondaryContainer = Color(0xFFFFDCC0),

    // Bus Blue (Tone 80)
    tertiary = Color(0xFF6AD3FF),
    onTertiary = Color(0xFF003546),
    tertiaryContainer = Color(0xFF004D64),
    onTertiaryContainer = Color(0xFFBFE9FF),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    // Neutrals: Deep tealy-greys
    background = Color(0xFF0E1515),
    onBackground = Color(0xFFDEE3E3),
    surface = Color(0xFF0E1515),
    onSurface = Color(0xFFDEE3E3),
    surfaceVariant = Color(0xFF3F4949),
    onSurfaceVariant = Color(0xFFBEC9C8),
    outline = Color(0xFF899392),
)

@Composable
fun TfNSWTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.surface.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()

            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
