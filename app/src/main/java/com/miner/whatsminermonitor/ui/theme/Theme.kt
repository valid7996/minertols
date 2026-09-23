package com.miner.whatsminermonitor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// ==================================================================================
// پالت «طلای دیجیتال»: زمینه‌ی سرمه‌ای عمیق + نارنجی بیت‌کوین + فیروزه‌ای + بنفش ملایم
// دارک تم برای حس داشبورد صنعتی ماینینگ، لایت تم تمیز و روشن با همان لهجه‌ها
// ==================================================================================

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB350),
    onPrimary = Color(0xFF2A1700),
    primaryContainer = Color(0xFF3D2B10),
    onPrimaryContainer = Color(0xFFFFD9A6),
    secondary = Color(0xFF5AD8E6),
    onSecondary = Color(0xFF00343B),
    secondaryContainer = Color(0xFF0E3E46),
    onSecondaryContainer = Color(0xFFB8F1FA),
    tertiary = Color(0xFFB69DFF),
    background = Color(0xFF0A0F16),
    onBackground = Color(0xFFE7EDF5),
    surface = Color(0xFF121924),
    onSurface = Color(0xFFE7EDF5),
    surfaceVariant = Color(0xFF1B2534),
    onSurfaceVariant = Color(0xFF96A3B6),
    outline = Color(0xFF4A586B),
    outlineVariant = Color(0xFF242F3F),
    error = Color(0xFFFF5C69),
    onError = Color(0xFF3B0006),
    errorContainer = Color(0xFF4C0A14),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFB45309),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE7CC),
    onPrimaryContainer = Color(0xFF4A2000),
    secondary = Color(0xFF0E7490),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3F2F8),
    onSecondaryContainer = Color(0xFF06333E),
    tertiary = Color(0xFF6D28D9),
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF17202B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF17202B),
    surfaceVariant = Color(0xFFE9EEF5),
    onSurfaceVariant = Color(0xFF5A6B7E),
    outline = Color(0xFF8FA0B3),
    outlineVariant = Color(0xFFDCE4EE),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDE3E3),
    onErrorContainer = Color(0xFF5C1010)
)

// تیترها پررنگ‌تر تا حس داشبورد بدهد؛ بدنه همان پیش‌فرض M3
private val AppTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.ExtraBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.Bold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold)
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun WhatsminerMonitorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
