package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = BrightEmerald,
    onPrimary = NightCanopy,
    primaryContainer = SurveyEmerald,
    onPrimaryContainer = MintMist,
    secondary = AmberGold,
    onSecondary = NightCanopy,
    secondaryContainer = Color(0xFF4B3207),
    onSecondaryContainer = WarmSandContainer,
    tertiary = GpsBlueLight,
    onTertiary = NightCanopy,
    background = NightCanopy,
    onBackground = NightInk,
    surface = NightSurface,
    onSurface = NightInk,
    surfaceVariant = NightSurfaceElevated,
    onSurfaceVariant = NightMuted,
    outline = NightOutline,
    error = AlertCoralDark,
    onError = NightCanopy
)

private val LightColorScheme = lightColorScheme(
    primary = SurveyEmerald,
    onPrimary = Color.White,
    primaryContainer = MintMist,
    onPrimaryContainer = ForestSlate,
    secondary = CompassAmber,
    onSecondary = Color.White,
    secondaryContainer = WarmSandContainer,
    onSecondaryContainer = Color(0xFF452303),
    tertiary = GpsBlue,
    onTertiary = Color.White,
    background = TopoCream,
    onBackground = CharcoalInk,
    surface = TopoSurface,
    onSurface = CharcoalInk,
    surfaceVariant = TopoSurfaceVariant,
    onSurfaceVariant = SlateMuted,
    outline = OutlineStone,
    error = AlertCoral,
    onError = Color.White
)

val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp)
)

@Composable
fun GeoSurveiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
