package com.example.complicationprovider.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Typography

// Tamna paleta (force dark)
private val DarkColors = Colors(
    primary = Color(0xFFB59F3B),      // zlatna
    primaryVariant = Color(0xFF8C7A2F),
    secondary = Color(0xFF8AB4F8),
    background = Color.Black,
    surface = Color(0xFF111111),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color(0xFFECECEC),
    onSurface = Color(0xFFECECEC)
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    // Uvijek dark
    MaterialTheme(
        colors = DarkColors,
        typography = Typography(),
        content = content
    )
}