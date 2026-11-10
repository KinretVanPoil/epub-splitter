package com.epubsplitter.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.epubsplitter.data.AppTheme

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    primaryContainer = LightContainer,
    background = LightBackground,
    surface = LightSurface,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    onBackground = LightText,
    onSurface = LightText,
    secondary = LightSecondary
)

private val SepiaColorScheme = lightColorScheme(
    primary = SepiaPrimary,
    primaryContainer = SepiaContainer,
    background = SepiaBackground,
    surface = SepiaSurface,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    onBackground = SepiaText,
    onSurface = SepiaText,
    secondary = SepiaSecondary
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    primaryContainer = DarkContainer,
    background = DarkBackground,
    surface = DarkSurface,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    onBackground = DarkText,
    onSurface = DarkText,
    secondary = DarkSecondary
)

@Composable
fun EpubSplitterTheme(
    appTheme: AppTheme = AppTheme.LIGHT,
    content: @Composable () -> Unit
) {
    val colorScheme = when (appTheme) {
        AppTheme.LIGHT -> LightColorScheme
        AppTheme.SEPIA -> SepiaColorScheme
        AppTheme.DARK -> DarkColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
