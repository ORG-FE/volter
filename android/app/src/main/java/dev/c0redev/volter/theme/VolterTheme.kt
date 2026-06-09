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

private val Bg = Color(0xFF15181B)
private val Panel = Color(0xFF1C2024)
private val Panel2 = Color(0xFF21262B)
private val Border = Color(0xFF333A40)
private val Border2 = Color(0xFF444D54)
private val Txt = Color(0xFFC4C9CE)
private val Dim = Color(0xFF818A91)
private val Accent = Color(0xFF5B93B8)
private val Good = Color(0xFF6FAE6F)
private val Warn = Color(0xFFC9A14A)
private val Bad = Color(0xFFCD6A5A)
private val Bright = Color(0xFFE7EAEC)

private val VolterColors = darkColorScheme(
    primary = Accent,
    onPrimary = Bg,
    primaryContainer = Panel2,
    onPrimaryContainer = Bright,
    secondary = Accent,
    onSecondary = Bg,
    secondaryContainer = Panel2,
    onSecondaryContainer = Bright,
    tertiary = Warn,
    onTertiary = Bg,
    tertiaryContainer = Panel2,
    onTertiaryContainer = Warn,
    background = Bg,
    onBackground = Txt,
    surface = Panel,
    onSurface = Txt,
    surfaceVariant = Panel2,
    onSurfaceVariant = Dim,
    surfaceDim = Bg,
    surfaceBright = Panel2,
    surfaceContainerLowest = Bg,
    surfaceContainerLow = Panel,
    surfaceContainer = Panel,
    surfaceContainerHigh = Panel2,
    surfaceContainerHighest = Panel2,
    inverseSurface = Txt,
    inverseOnSurface = Bg,
    error = Bad,
    onError = Bg,
    errorContainer = Panel2,
    onErrorContainer = Bad,
    outline = Border2,
    outlineVariant = Border,
    scrim = Color(0xFF000000),
)

private val Mono = FontFamily.Monospace

private val VolterTypography = Typography(
    displayLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    displayMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    displaySmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    headlineLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp),
    headlineMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp),
    headlineSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp),
    titleLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp),
    titleMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 19.sp),
    titleSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.5.sp),
    bodyLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
    bodyMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    bodySmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.3.sp),
)

@Composable
fun VolterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VolterColors,
        typography = VolterTypography,
        content = content,
    )
}
