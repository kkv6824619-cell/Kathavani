package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val KathaDarkColorScheme = darkColorScheme(
    primary = SaffronGold,
    onPrimary = OnSaffronDark,
    primaryContainer = DeepGoldContainer,
    onPrimaryContainer = LuminousAmber,
    secondary = CelestialTeal,
    onSecondary = ObsidianCanvas,
    secondaryContainer = DeepTealContainer,
    onSecondaryContainer = CelestialTeal,
    tertiary = VermilionCoral,
    onTertiary = ObsidianCanvas,
    tertiaryContainer = DeepCoralContainer,
    onTertiaryContainer = VermilionCoral,
    background = ObsidianCanvas,
    onBackground = WarmIvory,
    surface = DeepIndigoSurface,
    onSurface = WarmIvory,
    surfaceVariant = PlumCharcoalCard,
    onSurfaceVariant = SoftLavenderText,
    surfaceContainerLowest = ObsidianCanvas,
    surfaceContainerLow = DeepIndigoSurface,
    surfaceContainer = PlumCharcoalCard,
    surfaceContainerHigh = ElevatedVelvetCard,
    outline = SubtlePlumOutline,
    outlineVariant = SubtlePlumOutline.copy(alpha = 0.55f),
    error = ErrorCrimson,
    onError = ObsidianCanvas,
    errorContainer = ErrorContainerDark,
    onErrorContainer = WarmIvory
)

val KathaShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun KathaVaniTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = KathaDarkColorScheme,
        typography = KathaTypography,
        shapes = KathaShapes,
        content = content
    )
}
