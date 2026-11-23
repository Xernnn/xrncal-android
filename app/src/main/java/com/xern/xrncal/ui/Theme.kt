package com.xern.xrncal.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.xern.xrncal.R

/**
 * The colours: One Calendar's phone themes (light: #F5F5F5 bars on a white page; dark: #36373B
 * bars on #202125), its red for today, and xrncal's tile blue as the accent.
 */
@Immutable
data class Ink(
    val page: Color,
    val bar: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val line: Color,
    val todayCell: Color,
    val today: Color,
    val accent: Color,
    val accentTint: Color,
    val sheet: Color,
    val field: Color,
    val lunar: Color,
    val warn: Color,
) {
    companion object {
        val Accent = Color(0xFF308FFE)
        val Light = Ink(
            page = Color(0xFFFFFFFF), bar = Color(0xFFF5F5F5), text = Color(0xFF1B1B1B), muted = Color(0xFF6E6E6E), faint = Color(0xFFBDBDBD),
            line = Color(0xFFE6E6E6), todayCell = Color(0xFFF7F7F7), today = Color(0xFFDB090C), accent = Accent, accentTint = Color(0xFFE6EFFF),
            sheet = Color(0xFFFFFFFF), field = Color(0xFFF0F0F0), lunar = Color(0xFF9A9A9A), warn = Color(0xFFD93025),
        )
        val Dark = Ink(
            page = Color(0xFF202125), bar = Color(0xFF36373B), text = Color(0xFFF1F1F1), muted = Color(0xFFA8A8AC), faint = Color(0xFF5C5D61),
            line = Color(0xFF34353A), todayCell = Color(0xFF2A2B30), today = Color(0xFFFF4A4D), accent = Color(0xFF5AA6FF), accentTint = Color(0xFF263A55),
            sheet = Color(0xFF2B2C31), field = Color(0xFF36373B), lunar = Color(0xFF85868B), warn = Color(0xFFFF6B5E),
        )
    }
}

val LocalInk = staticCompositionLocalOf { Ink.Light }

/** White on a calendar colour, or near-black on a light one. */
fun onColor(c: Color): Color = if (c.luminance() > 0.6f) Color(0xFF1B1B1B) else Color.White

/**
 * MiSans Latin for every text and figure. Bold and heavier come from the variable font at weights
 * 630 and 700, since the static Bold draws "@" as "&".
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val MiSans = FontFamily(
    Font(R.font.misans_regular, FontWeight.Normal),
    Font(R.font.misans_medium, FontWeight.Medium),
    Font(R.font.misans_demibold, FontWeight.SemiBold),
    Font(R.font.misans_vf, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(630))),
    Font(R.font.misans_vf, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

fun type(size: Int, weight: FontWeight = FontWeight.Normal, color: Color = Color.Unspecified, lineHeight: TextUnit = 1.3.em) = TextStyle(
    fontFamily = MiSans,
    fontSize = size.sp,
    fontWeight = weight,
    color = color,
    lineHeight = lineHeight,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

@Composable
fun XrnTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val ink = if (dark) Ink.Dark else Ink.Light
    val scheme = if (dark) {
        darkColorScheme(primary = ink.accent, onPrimary = Color.White, background = ink.page, surface = ink.sheet, onSurface = ink.text, surfaceVariant = ink.field, onSurfaceVariant = ink.muted, outline = ink.line, surfaceContainerHigh = ink.sheet, surfaceContainerHighest = ink.field, surfaceContainer = ink.sheet, surfaceContainerLow = ink.sheet, secondaryContainer = ink.accentTint)
    } else {
        lightColorScheme(primary = ink.accent, onPrimary = Color.White, background = ink.page, surface = ink.sheet, onSurface = ink.text, surfaceVariant = ink.field, onSurfaceVariant = ink.muted, outline = ink.line, surfaceContainerHigh = ink.sheet, surfaceContainerHighest = ink.field, surfaceContainer = ink.sheet, surfaceContainerLow = ink.sheet, secondaryContainer = ink.accentTint)
    }
    val base = Typography()
    val typography = Typography(
        bodyLarge = base.bodyLarge.copy(fontFamily = MiSans),
        bodyMedium = base.bodyMedium.copy(fontFamily = MiSans),
        bodySmall = base.bodySmall.copy(fontFamily = MiSans),
        labelLarge = base.labelLarge.copy(fontFamily = MiSans),
        labelMedium = base.labelMedium.copy(fontFamily = MiSans),
        labelSmall = base.labelSmall.copy(fontFamily = MiSans),
        titleLarge = base.titleLarge.copy(fontFamily = MiSans),
        titleMedium = base.titleMedium.copy(fontFamily = MiSans),
        titleSmall = base.titleSmall.copy(fontFamily = MiSans),
        headlineSmall = base.headlineSmall.copy(fontFamily = MiSans),
    )
    CompositionLocalProvider(LocalInk provides ink) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
