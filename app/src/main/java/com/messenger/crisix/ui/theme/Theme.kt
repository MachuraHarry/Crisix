package com.messenger.crisix.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val NavyDarkColorScheme = darkColorScheme(
    primary = NavyPrimary,
    onPrimary = NavyOnDark,
    primaryContainer = NavyAccentDark,
    onPrimaryContainer = NavyOnDark,
    secondary = NavySecondary,
    onSecondary = NavyOnDark,
    secondaryContainer = NavySurfaceVariant,
    onSecondaryContainer = NavyOnDark,
    tertiary = NavyAccent,
    onTertiary = NavyOnDark,
    tertiaryContainer = NavyAccentDark,
    onTertiaryContainer = NavyOnDark,
    background = NavyDark,
    surface = NavySurface,
    surfaceVariant = NavySurfaceVariant,
    onBackground = NavyOnDark,
    onSurface = NavyOnDark,
    onSurfaceVariant = NavyOnDarkMuted,
    outline = NavyDivider,
    error = NavyError,
    onError = NavyOnDark
)

@Composable
fun CrisixTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = NavyDarkColorScheme,
        typography = Typography,
        content = content
    )
}
