package dev.c0redev.volter.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val VolterDarkColors = darkColorScheme(
    primary = Color(0xFF8DA8FF),
    onPrimary = Color(0xFF111A38),
    primaryContainer = Color(0xFF253262),
    onPrimaryContainer = Color(0xFFE1E8FF),
    secondary = Color(0xFF67D6C2),
    onSecondary = Color(0xFF003932),
    tertiary = Color(0xFF9EBCFF),
    onTertiary = Color(0xFF172347),
    background = Color(0xFF090D16),
    onBackground = Color(0xFFEAF0FF),
    surface = Color(0xFF0F1524),
    onSurface = Color(0xFFEAF0FF),
    surfaceVariant = Color(0xFF1A2438),
    onSurfaceVariant = Color(0xFFB8C7E6),
    surfaceDim = Color(0xFF070B12),
    surfaceBright = Color(0xFF1B2435),
    surfaceContainerLowest = Color(0xFF0C1220),
    surfaceContainerLow = Color(0xFF111A2B),
    surfaceContainer = Color(0xFF162236),
    surfaceContainerHigh = Color(0xFF1D2B43),
    surfaceContainerHighest = Color(0xFF263653),
    error = Color(0xFFFF8FA2),
    onError = Color(0xFF410002),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF3C485F),
    outlineVariant = Color(0xFF2C3547),
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
    MaterialTheme(
        colorScheme = VolterDarkColors,
        typography = VolterTypography,
        content = content,
    )
}
