package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Deep Space Dark Theme Palette
val SpaceNavyBackground = Color(0xFF060913)
val SpaceSurfaceCard = Color(0xFF10172A)
val SpaceCyanAccent = Color(0xFF38BDF8)
val StarlightGold = Color(0xFFFBBF24)
val AstroEmerald = Color(0xFF34D399)
val RecordingCrimson = Color(0xFFEF4444)

// Astronomical Red Night Mode Palette (Preserves human rhodopsin dark adaptation)
val RedNightBackground = Color(0xFF090101)
val RedNightSurface = Color(0xFF1A0404)
val RedNightPrimary = Color(0xFFFF2A2A)
val RedNightSecondary = Color(0xFFB91C1C)
val RedNightText = Color(0xFFFF8080)

private val DeepSpaceColorScheme = darkColorScheme(
    primary = SpaceCyanAccent,
    onPrimary = Color(0xFF031525),
    secondary = StarlightGold,
    onSecondary = Color(0xFF1F1600),
    tertiary = AstroEmerald,
    background = SpaceNavyBackground,
    onBackground = Color(0xFFE2E8F0),
    surface = SpaceSurfaceCard,
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF94A3B8),
    error = RecordingCrimson
)

private val AstronomicalRedColorScheme = darkColorScheme(
    primary = RedNightPrimary,
    onPrimary = Color(0xFF1F0000),
    secondary = RedNightSecondary,
    onSecondary = Color(0xFFFFB4B4),
    tertiary = Color(0xFFDC2626),
    background = RedNightBackground,
    onBackground = RedNightText,
    surface = RedNightSurface,
    onSurface = RedNightText,
    surfaceVariant = Color(0xFF2B0707),
    onSurfaceVariant = Color(0xFFEF4444),
    error = Color(0xFFFF4D4D)
)

@Composable
fun AstroNightCamTheme(
    isRedNightMode: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (isRedNightMode) AstronomicalRedColorScheme else DeepSpaceColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
