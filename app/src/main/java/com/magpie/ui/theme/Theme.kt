package com.magpie.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Teal accent to match the icon: white line-art magpie on near-black with a
// teal circuit-board background (§16). Dark-first, follows system.
val Teal = Color(0xFF2DD4BF)
val TealDim = Color(0xFF14B8A6)
val NearBlack = Color(0xFF0D1413)
val SurfaceDark = Color(0xFF16201E)
val SurfaceDarkHigh = Color(0xFF1D2A27)

private val DarkColors = darkColorScheme(
    primary = Teal,
    onPrimary = Color(0xFF00332C),
    primaryContainer = Color(0xFF0F3A34),
    onPrimaryContainer = Teal,
    secondary = Color(0xFF8ED1C8),
    onSecondary = Color(0xFF10302B),
    background = NearBlack,
    onBackground = Color(0xFFE2E8E6),
    surface = SurfaceDark,
    onSurface = Color(0xFFE2E8E6),
    surfaceVariant = SurfaceDarkHigh,
    onSurfaceVariant = Color(0xFFA8B8B4),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

private val LightColors = lightColorScheme(
    primary = TealDim,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC9F5EE),
    onPrimaryContainer = Color(0xFF063E37),
    secondary = Color(0xFF3D6660),
    background = Color(0xFFF2F5F4),
    onBackground = Color(0xFF141A19),
    surface = Color.White,
    onSurface = Color(0xFF141A19),
    surfaceVariant = Color(0xFFE3EAE8),
    onSurfaceVariant = Color(0xFF41504D),
)

val MagpieTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
)

@Composable
fun MagpieTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MagpieTypography,
        content = content,
    )
}
