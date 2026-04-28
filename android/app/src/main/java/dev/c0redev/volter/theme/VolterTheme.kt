package dev.c0redev.volter.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import android.os.Build

private val VolterDarkColors = darkColorScheme(
    primary = Color(0xFF7CE7D2),
    onPrimary = Color(0xFF002D28),
    primaryContainer = Color(0xFF123E45),
    onPrimaryContainer = Color(0xFFD3FFF5),
    secondary = Color(0xFFA9B8FF),
    onSecondary = Color(0xFF171D48),
    tertiary = Color(0xFFFFB86B),
    onTertiary = Color(0xFF3D2200),
    background = Color(0xFF070B12),
    onBackground = Color(0xFFEAF0FF),
    surface = Color(0xFF0C1320),
    onSurface = Color(0xFFEAF0FF),
    surfaceVariant = Color(0xFF182334),
    onSurfaceVariant = Color(0xFFC3CBE0),
    surfaceDim = Color(0xFF05080E),
    surfaceBright = Color(0xFF1D293C),
    surfaceContainerLowest = Color(0xFF090F19),
    surfaceContainerLow = Color(0xFF0F1726),
    surfaceContainer = Color(0xFF142033),
    surfaceContainerHigh = Color(0xFF1A2940),
    surfaceContainerHighest = Color(0xFF263852),
    error = Color(0xFFFF8FA2),
    onError = Color(0xFF410002),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF3C485F),
    outlineVariant = Color(0xFF2C3547),
)

private val VolterLightColors = lightColorScheme(
    primary = Color(0xFF006A68),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF8EF2EC),
    onPrimaryContainer = Color(0xFF00201F),
    secondary = Color(0xFF4A5E9E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDAE2FF),
    onSecondaryContainer = Color(0xFF04174F),
    tertiary = Color(0xFF8A5000),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDDBD),
    onTertiaryContainer = Color(0xFF2C1600),
    background = Color(0xFFF5FAFF),
    onBackground = Color(0xFF0E1726),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0E1726),
    surfaceVariant = Color(0xFFDCE4F3),
    onSurfaceVariant = Color(0xFF414C61),
)

private val Mono = FontFamily.Monospace

private val VolterTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 56.sp, lineHeight = 62.sp),
    displayMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 42.sp, lineHeight = 48.sp),
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 34.sp, lineHeight = 40.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 14.sp),
)

@Composable
fun VolterTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) VolterDarkColors else VolterLightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = VolterTypography,
        content = content,
    )
}
