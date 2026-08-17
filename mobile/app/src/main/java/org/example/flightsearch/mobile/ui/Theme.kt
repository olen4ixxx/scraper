package org.example.flightsearch.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// One family of blues over a near-black slate, rather than Material's default purple. Sky is
// the accent, Ink is the "black" - a very dark navy, not pure #000, so surfaces stacked on it
// stay distinguishable.
private val Sky300 = Color(0xFF7DD3FC)
private val Sky400 = Color(0xFF38BDF8)
private val Sky500 = Color(0xFF0EA5E9)
private val Sky600 = Color(0xFF0284C7)
private val Sky800 = Color(0xFF075985)
private val Sky900 = Color(0xFF0C4A6E)
private val Sky50 = Color(0xFFE0F2FE)

private val Ink900 = Color(0xFF0B1220)
private val Ink800 = Color(0xFF0F172A)
private val Ink700 = Color(0xFF1E293B)
private val Ink600 = Color(0xFF334155)
private val Ink500 = Color(0xFF475569)
private val Slate400 = Color(0xFF94A3B8)
private val Slate200 = Color(0xFFE2E8F0)
private val Slate300 = Color(0xFFCBD5E1)
private val Mist = Color(0xFFF4F9FD)
private val Frost = Color(0xFFEAF2F9)

private val LightColors = lightColorScheme(
    primary = Sky600,
    onPrimary = Color.White,
    primaryContainer = Sky50,
    onPrimaryContainer = Sky900,
    secondary = Ink800,
    onSecondary = Color.White,
    secondaryContainer = Frost,
    onSecondaryContainer = Sky900,
    background = Mist,
    onBackground = Ink800,
    surface = Color.White,
    onSurface = Ink800,
    surfaceVariant = Frost,
    onSurfaceVariant = Ink500,
    outline = Slate400,
    outlineVariant = Slate300,
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = Sky400,
    onPrimary = Color(0xFF00344A),
    primaryContainer = Sky800,
    onPrimaryContainer = Sky50,
    secondary = Sky300,
    onSecondary = Ink900,
    secondaryContainer = Ink700,
    onSecondaryContainer = Slate200,
    background = Ink900,
    onBackground = Color(0xFFE6EDF5),
    surface = Ink800,
    onSurface = Color(0xFFE6EDF5),
    surfaceVariant = Ink700,
    onSurfaceVariant = Slate400,
    outline = Ink500,
    outlineVariant = Ink600,
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

/** Accent gradient for the one primary action on screen. */
val accentBrush: Brush
    @Composable get() = Brush.horizontalGradient(
        if (isSystemInDarkTheme()) listOf(Sky500, Sky400) else listOf(Sky600, Sky500)
    )

/** Backdrop behind the app bar, so the top of the screen isn't a flat slab. */
val headerBrush: Brush
    @Composable get() = Brush.verticalGradient(
        if (isSystemInDarkTheme()) listOf(Ink800, Ink900) else listOf(Sky50, Mist)
    )

@Composable
fun FlightSearchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
