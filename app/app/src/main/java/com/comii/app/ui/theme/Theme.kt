package com.comii.app.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Apple-ish flat palette: grouped background, white cards, hairline separators. */
data class ComiiPalette(
    val background: Color,
    val elevated: Color,
    val card: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val separator: Color,
    val accent: Color,
    val danger: Color,
    val success: Color,
    val isDark: Boolean,
)

val LightPalette = ComiiPalette(
    background = Color(0xFFF2F2F7),
    elevated = Color(0xFFFFFFFF),
    card = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF101014),
    textSecondary = Color(0xFF8E8E93),
    textTertiary = Color(0xFFAEAEB2),
    separator = Color(0xFFE5E5EA),
    accent = Color(0xFF0A84FF),
    danger = Color(0xFFFF3B30),
    success = Color(0xFF34C759),
    isDark = false,
)

val DarkPalette = ComiiPalette(
    background = Color(0xFF000000),
    elevated = Color(0xFF1C1C1E),
    card = Color(0xFF1C1C1E),
    textPrimary = Color(0xFFF5F5F7),
    textSecondary = Color(0xFF98989F),
    textTertiary = Color(0xFF6C6C70),
    separator = Color(0xFF2C2C2E),
    accent = Color(0xFF0A84FF),
    danger = Color(0xFFFF453A),
    success = Color(0xFF30D158),
    isDark = true,
)

val LocalComiiPalette = staticCompositionLocalOf { LightPalette }

object ComiiTheme {
    val palette: ComiiPalette
        @Composable get() = LocalComiiPalette.current
}

private fun typography(): Typography {
    val base = Typography()
    return base.copy(
        displaySmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 34.sp,
            lineHeight = 41.sp,
            letterSpacing = (-0.4).sp,
        ),
        headlineMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 24.sp,
            lineHeight = 30.sp,
            letterSpacing = (-0.2).sp,
        ),
        titleLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
            lineHeight = 26.sp,
        ),
        titleMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            lineHeight = 21.sp,
        ),
        bodyLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 22.sp,
        ),
        bodyMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 19.sp,
        ),
        labelLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 18.sp,
        ),
        labelMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        ),
    )
}

val ComiiShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun ComiiTheme(dark: Boolean, content: @Composable () -> Unit) {
    val target = if (dark) DarkPalette else LightPalette
    // Smoothly cross-fade every palette colour so switching light/dark does not
    // flash the screen (the old implementation swapped colours in one frame).
    val spec = tween<Color>(durationMillis = 450, easing = FastOutSlowInEasing)
    val palette = ComiiPalette(
        background = animateColorAsState(target.background, spec, label = "bg").value,
        elevated = animateColorAsState(target.elevated, spec, label = "elevated").value,
        card = animateColorAsState(target.card, spec, label = "card").value,
        textPrimary = animateColorAsState(target.textPrimary, spec, label = "text").value,
        textSecondary = animateColorAsState(target.textSecondary, spec, label = "text2").value,
        textTertiary = animateColorAsState(target.textTertiary, spec, label = "text3").value,
        separator = animateColorAsState(target.separator, spec, label = "sep").value,
        accent = animateColorAsState(target.accent, spec, label = "accent").value,
        danger = animateColorAsState(target.danger, spec, label = "danger").value,
        success = animateColorAsState(target.success, spec, label = "success").value,
        isDark = dark,
    )
    val scheme = if (dark) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = Color.White,
            background = palette.background,
            onBackground = palette.textPrimary,
            surface = palette.card,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.elevated,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.separator,
            error = palette.danger,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = Color.White,
            background = palette.background,
            onBackground = palette.textPrimary,
            surface = palette.card,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.elevated,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.separator,
            error = palette.danger,
        )
    }
    CompositionLocalProvider(LocalComiiPalette provides palette) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography(),
            shapes = ComiiShapes,
            content = content,
        )
    }
}
