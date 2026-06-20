package com.example.sonara.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Force a consistent premium dark theme layout for Sonara matching Spotify colors
private val DarkColorScheme = darkColorScheme(
    primary = SpotifyGreen,
    background = SpotifyBlack,
    surface = SpotifyDarkGray,
    onPrimary = SpotifyWhite,
    onBackground = SpotifyWhite,
    onSurface = SpotifyWhite,
    secondary = SpotifyLightGray
)

@Composable
fun SonaraTheme(
    content: @Composable () -> Unit
) {
    // We explicitly use our DarkColorScheme to ensure the app is private, modern, and dark by design
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}