package com.miner.whatsminermonitor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FD1C5),
    secondary = Color(0xFF81E6D9),
    background = Color(0xFF10161C),
    surface = Color(0xFF1B2733),
    error = Color(0xFFEF5350)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F766E),
    secondary = Color(0xFF14B8A6),
    background = Color(0xFFF5F7FA),
    surface = Color(0xFFFFFFFF),
    error = Color(0xFFD32F2F)
)

@Composable
fun WhatsminerMonitorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}
