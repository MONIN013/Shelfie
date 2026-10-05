package net.monindev.shelfie.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Generated from seed #6A5193 with Material Color Utilities (TonalSpot, spec 2021).
private val LightColors = lightColorScheme(
    primary = Color(0xFF69548D), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFECDCFF), onPrimaryContainer = Color(0xFF513C73),
    inversePrimary = Color(0xFFD5BBFC),
    secondary = Color(0xFF645B70), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEADEF7), onSecondaryContainer = Color(0xFF4B4357),
    tertiary = Color(0xFF7F525C), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD9E0), onTertiaryContainer = Color(0xFF643B45),
    background = Color(0xFFFEF7FF), onBackground = Color(0xFF1D1A20),
    surface = Color(0xFFFEF7FF), onSurface = Color(0xFF1D1A20),
    surfaceVariant = Color(0xFFE8E0EB), onSurfaceVariant = Color(0xFF49454E),
    surfaceTint = Color(0xFF69548D),
    inverseSurface = Color(0xFF322F35), inverseOnSurface = Color(0xFFF6EEF7),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF93000A),
    outline = Color(0xFF7B757F), outlineVariant = Color(0xFFCBC4CF), scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFEF7FF), surfaceDim = Color(0xFFDED8E0),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF8F1F9),
    surfaceContainer = Color(0xFFF3ECF4), surfaceContainerHigh = Color(0xFFEDE6EE),
    surfaceContainerHighest = Color(0xFFE7E0E8),
    primaryFixed = Color(0xFFECDCFF), primaryFixedDim = Color(0xFFD5BBFC),
    onPrimaryFixed = Color(0xFF240E45), onPrimaryFixedVariant = Color(0xFF513C73),
    secondaryFixed = Color(0xFFEADEF7), secondaryFixedDim = Color(0xFFCEC2DB),
    onSecondaryFixed = Color(0xFF1F182A), onSecondaryFixedVariant = Color(0xFF4B4357),
    tertiaryFixed = Color(0xFFFFD9E0), tertiaryFixedDim = Color(0xFFF1B7C3),
    onTertiaryFixed = Color(0xFF32101A), onTertiaryFixedVariant = Color(0xFF643B45),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD5BBFC), onPrimary = Color(0xFF3A255B),
    primaryContainer = Color(0xFF513C73), onPrimaryContainer = Color(0xFFECDCFF),
    inversePrimary = Color(0xFF69548D),
    secondary = Color(0xFFCEC2DB), onSecondary = Color(0xFF352D40),
    secondaryContainer = Color(0xFF4B4357), onSecondaryContainer = Color(0xFFEADEF7),
    tertiary = Color(0xFFF1B7C3), onTertiary = Color(0xFF4B252F),
    tertiaryContainer = Color(0xFF643B45), onTertiaryContainer = Color(0xFFFFD9E0),
    background = Color(0xFF151218), onBackground = Color(0xFFE7E0E8),
    surface = Color(0xFF151218), onSurface = Color(0xFFE7E0E8),
    surfaceVariant = Color(0xFF49454E), onSurfaceVariant = Color(0xFFCBC4CF),
    surfaceTint = Color(0xFFD5BBFC),
    inverseSurface = Color(0xFFE7E0E8), inverseOnSurface = Color(0xFF322F35),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF958E99), outlineVariant = Color(0xFF49454E), scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF3B383E), surfaceDim = Color(0xFF151218),
    surfaceContainerLowest = Color(0xFF0F0D12), surfaceContainerLow = Color(0xFF1D1A20),
    surfaceContainer = Color(0xFF211E24), surfaceContainerHigh = Color(0xFF2C292F),
    surfaceContainerHighest = Color(0xFF37333A),
    primaryFixed = Color(0xFFECDCFF), primaryFixedDim = Color(0xFFD5BBFC),
    onPrimaryFixed = Color(0xFF240E45), onPrimaryFixedVariant = Color(0xFF513C73),
    secondaryFixed = Color(0xFFEADEF7), secondaryFixedDim = Color(0xFFCEC2DB),
    onSecondaryFixed = Color(0xFF1F182A), onSecondaryFixedVariant = Color(0xFF4B4357),
    tertiaryFixed = Color(0xFFFFD9E0), tertiaryFixedDim = Color(0xFFF1B7C3),
    onTertiaryFixed = Color(0xFF32101A), onTertiaryFixedVariant = Color(0xFF643B45),
)

/** Compose already scales these specs by the system animator duration setting. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ShelfieTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
