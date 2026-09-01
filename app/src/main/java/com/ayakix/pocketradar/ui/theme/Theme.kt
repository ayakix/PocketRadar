package com.ayakix.pocketradar.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Material 3 theme with a fixed aviation-radar palette (see Color.kt).
 * Dynamic Color is intentionally not used: the map markers, trails, and the
 * console-style debug sheet are designed around cyan/amber accents, and a
 * wallpaper-derived scheme would break that visual identity per device.
 */

private val DarkScheme = darkColorScheme(
    primary = RadarCyan,
    onPrimary = Color(0xFF00363E),
    primaryContainer = RadarCyanDim,
    onPrimaryContainer = Color(0xFFB2EBF5),
    secondary = RadarAmber,
    onSecondary = Color(0xFF452B00),
    secondaryContainer = RadarAmberDim,
    onSecondaryContainer = Color(0xFFFFDCBE),
    background = NightBackground,
    onBackground = NightOnSurface,
    surface = NightSurface,
    onSurface = NightOnSurface,
    surfaceVariant = NightSurfaceHigh,
    onSurfaceVariant = NightOnSurfaceVariant,
    surfaceContainer = NightSurfaceHigh,
    surfaceContainerHigh = NightSurfaceHigh,
    surfaceContainerLow = NightSurface,
    outline = NightOutline,
    error = Color(0xFFFF6B6B),
)

private val LightScheme = lightColorScheme(
    primary = DayPrimary,
    onPrimary = Color.White,
    primaryContainer = DayPrimaryContainer,
    onPrimaryContainer = Color(0xFF001F26),
    secondary = DaySecondary,
    onSecondary = Color.White,
    secondaryContainer = DaySecondaryContainer,
    onSecondaryContainer = Color(0xFF2C1600),
    background = DayBackground,
    onBackground = DayOnSurface,
    surface = DaySurface,
    onSurface = DayOnSurface,
    surfaceVariant = DaySurfaceHigh,
    onSurfaceVariant = DayOnSurfaceVariant,
    surfaceContainer = DaySurfaceHigh,
    surfaceContainerHigh = DaySurfaceHigh,
    surfaceContainerLow = DaySurface,
    outline = DayOutline,
)

@Composable
fun PocketRadarTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
