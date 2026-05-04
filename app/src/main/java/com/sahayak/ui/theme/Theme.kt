package com.sahayak.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val SahayakColorScheme = lightColorScheme(
    background = Background,
    primary = Primary,
    onPrimary = OnPrimary,
    secondary = Secondary,
    onSecondary = OnSecondary,
    tertiary = Success,
    surface = Surface,
    onSurface = OnSurface,
    onSurfaceVariant = OnSurfaceVariant,
    onBackground = OnSurface,
)

@Composable
fun SahayakTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SahayakColorScheme,
        content = content
    )
}
