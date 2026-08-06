package com.magpie.filer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// Both schemes are finished pieces of work, and the app follows the system
// setting between them. There is deliberately no Material You path: the palette
// is fixed so the app always matches its own icon.

private val MagpieDark = darkColorScheme(
    primary = Silver,
    onPrimary = OnSilver,
    primaryContainer = SilverContainer,
    onPrimaryContainer = SilverBright,
    secondary = SilverDim,
    onSecondary = OnSilver,
    secondaryContainer = Steel,
    onSecondaryContainer = SilverBright,
    tertiary = Silver,
    onTertiary = OnSilver,
    tertiaryContainer = SilverContainer,
    onTertiaryContainer = SilverBright,
    background = Ink,
    onBackground = SilverBright,
    surface = Ink,
    onSurface = SilverBright,
    surfaceVariant = Steel,
    onSurfaceVariant = SilverDim,
    surfaceContainerLowest = InkLow,
    surfaceContainerLow = Slate,
    surfaceContainer = SlateHigh,
    surfaceContainerHigh = SlateHigher,
    surfaceContainerHighest = SlateHighest,
    outline = SteelEdge,
    outlineVariant = SteelEdgeSoft,
    error = RustDark,
    onError = OnRustDark,
    errorContainer = RustContainerDark,
    onErrorContainer = OnRustContainerDark,
)

private val MagpieLight = lightColorScheme(
    primary = GraphiteSoft,
    onPrimary = PaperPure,
    primaryContainer = SilverContainerLight,
    onPrimaryContainer = Graphite,
    secondary = GraphiteSofter,
    onSecondary = PaperPure,
    secondaryContainer = SteelContainerLight,
    onSecondaryContainer = Graphite,
    tertiary = GraphiteSoft,
    onTertiary = PaperPure,
    tertiaryContainer = SilverContainerLight,
    onTertiaryContainer = Graphite,
    background = Paper,
    onBackground = Graphite,
    surface = Paper,
    onSurface = Graphite,
    surfaceVariant = PaperVariant,
    onSurfaceVariant = GraphiteSoft,
    surfaceContainerLowest = PaperPure,
    surfaceContainerLow = PaperLow,
    surfaceContainer = PaperContainer,
    surfaceContainerHigh = PaperContainerHigh,
    surfaceContainerHighest = PaperContainerHighest,
    outline = EdgeLight,
    outlineVariant = EdgeLightSoft,
    error = RustLight,
    onError = PaperPure,
    errorContainer = RustContainerLight,
    onErrorContainer = OnRustContainerLight,
)

@Composable
fun MagpieTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) MagpieDark else MagpieLight,
        typography = MagpieTypography,
        content = content,
    )
}
