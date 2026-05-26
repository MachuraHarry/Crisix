package com.messenger.crisix.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val NavyDarkColorScheme = darkColorScheme(
    primary = NavyPrimary,
    secondary = NavySecondary,
    tertiary = NavyGreen,
    background = NavyDark,
    surface = NavySurface,
    surfaceVariant = NavySurfaceVariant,
    onPrimary = NavyOnDark,
    onSecondary = NavyOnDark,
    onTertiary = NavyOnDark,
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
