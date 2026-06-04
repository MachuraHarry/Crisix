package com.messenger.crisix.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

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

fun createScaledTypography(fontScale: String, fontFamilyName: String): androidx.compose.material3.Typography {
    val scale = when (fontScale) {
        "large" -> 1.15f
        "xlarge" -> 1.3f
        else -> 1.0f
    }
    val family = when (fontFamilyName) {
        "monospace" -> FontFamily.Monospace
        else -> FontFamily.Default
    }

    fun TextStyle.scale(): TextStyle = this.copy(
        fontSize = fontSize * scale,
        fontFamily = family
    )

    return Typography.copy(
        displayLarge = Typography.displayLarge.scale(),
        displayMedium = Typography.displayMedium.scale(),
        displaySmall = Typography.displaySmall.scale(),
        headlineLarge = Typography.headlineLarge.scale(),
        headlineMedium = Typography.headlineMedium.scale(),
        headlineSmall = Typography.headlineSmall.scale(),
        titleLarge = Typography.titleLarge.scale(),
        titleMedium = Typography.titleMedium.scale(),
        titleSmall = Typography.titleSmall.scale(),
        bodyLarge = Typography.bodyLarge.scale(),
        bodyMedium = Typography.bodyMedium.scale(),
        bodySmall = Typography.bodySmall.scale(),
        labelLarge = Typography.labelLarge.scale(),
        labelMedium = Typography.labelMedium.scale(),
        labelSmall = Typography.labelSmall.scale(),
    )
}

@Composable
fun CrisixTheme(
    fontScale: String = "normal",
    fontFamilyName: String = "system",
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = NavyDarkColorScheme,
        typography = createScaledTypography(fontScale, fontFamilyName),
        content = content
    )
}
