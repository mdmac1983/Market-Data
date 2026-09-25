package app.orionmd.marketdata.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.orionmd.marketdata.R
import app.orionmd.marketdata.data.Settings
import app.orionmd.marketdata.data.ThemeMode

val Up = Color(0xFF22C55E)
val Down = Color(0xFFEF4444)
val Cyan = Color(0xFF3FC8F5)
val Gold = Color(0xFFF5C44F)
val Indigo = Color(0xFF6366F1)

fun changeColor(v: Double?): Color = if ((v ?: 0.0) >= 0) Up else Down

private val DarkScheme = darkColorScheme(
    primary = Cyan, onPrimary = Color(0xFF00212E), secondary = Gold, onSecondary = Color(0xFF2B1F00), tertiary = Indigo,
    background = Color(0xFF060A20), onBackground = Color(0xFFE6EAF7),
    surface = Color(0xFF0C1230), onSurface = Color(0xFFE6EAF7),
    surfaceVariant = Color(0xFF161E45), onSurfaceVariant = Color(0xFFA9B3D6),
    surfaceContainer = Color(0xFF10173A), surfaceContainerHigh = Color(0xFF172050), surfaceContainerLow = Color(0xFF0B1130),
    outline = Color(0xFF2E3A73), outlineVariant = Color(0xFF222B5A),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0B6FA4), onPrimary = Color.White, secondary = Color(0xFF9A6B00), tertiary = Indigo,
    background = Color(0xFFF3F5FB), onBackground = Color(0xFF0B1030),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF0B1030),
    surfaceVariant = Color(0xFFE6EAF5), onSurfaceVariant = Color(0xFF4A557A),
    surfaceContainer = Color(0xFFF7F8FD), surfaceContainerHigh = Color(0xFFEDF0F8), surfaceContainerLow = Color(0xFFFBFCFF),
    outline = Color(0xFFC3CAE0), outlineVariant = Color(0xFFDDE2F0),
)

data class Spacing(val card: Dp, val row: Dp, val gap: Dp)

val LocalSpacing = staticCompositionLocalOf { Spacing(14.dp, 10.dp, 12.dp) }
val LocalDark = compositionLocalOf { true }

@Composable
fun MarketTheme(settings: Settings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> isSystemInDarkTheme() }
    val base = LocalDensity.current
    val spacing = if (settings.compact) Spacing(10.dp, 6.dp, 8.dp) else Spacing(14.dp, 10.dp, 12.dp)
    CompositionLocalProvider(
        LocalDensity provides Density(base.density, base.fontScale * settings.textScale),
        LocalSpacing provides spacing,
        LocalDark provides dark,
    ) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}

/** App background: gradient plus the OrionMD logo watermark. */
@Composable
fun WatermarkBackground(alpha: Float, content: @Composable () -> Unit) {
    val dark = LocalDark.current
    val bg = if (dark) Brush.verticalGradient(listOf(Color(0xFF0A0F2E), Color(0xFF060A20), Color(0xFF040716)))
    else Brush.verticalGradient(listOf(Color(0xFFF6F7FC), Color(0xFFEEF1F9)))
    BoxWithConstraints(Modifier.fillMaxSize().background(bg)) {
        val wide = maxWidth > maxHeight
        val res = when {
            wide && dark -> R.drawable.watermark_square
            wide -> R.drawable.watermark_square_ink
            dark -> R.drawable.watermark
            else -> R.drawable.watermark_ink
        }
        if (alpha > 0f) Image(
            painterResource(res), contentDescription = null,
            modifier = (if (wide) Modifier.fillMaxHeight(0.9f) else Modifier.fillMaxWidth()).align(Alignment.Center).alpha(alpha),
            contentScale = ContentScale.Fit,
        )
        content()
    }
}
