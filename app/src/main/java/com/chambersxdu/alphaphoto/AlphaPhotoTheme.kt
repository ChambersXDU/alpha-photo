package com.chambersxdu.alphaphoto

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal val AlphaInk = Color(0xFF111111)
internal val AlphaMuted = Color(0xFF6D6D6A)
internal val AlphaBackground = Color(0xFFF1F1EF)
internal val AlphaSurface = Color(0xFFFFFFFF)
internal val AlphaLine = Color(0xFFE3E3E0)
internal val AlphaConnected = Color(0xFF1F8A5B)

private val AlphaColors = lightColorScheme(
    primary = AlphaInk,
    onPrimary = Color.White,
    background = AlphaBackground,
    onBackground = AlphaInk,
    surface = AlphaSurface,
    onSurface = AlphaInk,
    surfaceVariant = Color(0xFFEAEAE7),
    onSurfaceVariant = AlphaMuted,
    outline = AlphaLine,
    error = Color(0xFFB3261E),
)

private val AlphaTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 38.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 19.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 21.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
)

@Composable
internal fun AlphaPhotoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AlphaColors,
        typography = AlphaTypography,
        content = content,
    )
}
