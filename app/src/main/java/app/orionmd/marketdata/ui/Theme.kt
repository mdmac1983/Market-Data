package app.orionmd.marketdata.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.LocalContentColor
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
    surfaceVariant = Color(0xFF161E45), onSurfaceVariant = Color(0xFFC3CBEA),
    surfaceContainer = Color(0xFF10173A), surfaceContainerHigh = Color(0xFF172050), surfaceContainerLow = Color(0xFF0B1130),
    outline = Color(0xFF2E3A73), outlineVariant = Color(0xFF222B5A),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0B6FA4), onPrimary = Color.White, secondary = Color(0xFF9A6B00), tertiary = Indigo,
    background = Color(0xFFE6E9F0), onBackground = Color(0xFF0B1030),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF0B1030),
    surfaceVariant = Color(0xFFE6EAF5), onSurfaceVariant = Color(0xFF4A557A),
    surfaceContainer = Color(0xFFF7F8FD), surfaceContainerHigh = Color(0xFFEDF0F8), surfaceContainerLow = Color(0xFFFBFCFF),
    outline = Color(0xFFC3CAE0), outlineVariant = Color(0xFFDDE2F0),
)

data class Spacing(val card: Dp, val row: Dp, val gap: Dp)

val LocalSpacing = staticCompositionLocalOf { Spacing(14.dp, 10.dp, 12.dp) }
val LocalDark = compositionLocalOf { true }

/** 0 = large, 1 = medium, 2 = small cards. */
val LocalCardSize = staticCompositionLocalOf { 0 }

/** Lets a dashboard card show a collapse chevron in its title bar. */
class CardCollapse(val collapsed: Boolean, val toggle: () -> Unit)
val LocalCardCollapse = compositionLocalOf<CardCollapse?> { null }

@Composable
fun MarketTheme(settings: Settings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> isSystemInDarkTheme() }
    val base = LocalDensity.current
    val spacing = when (settings.cardSize) {
        2 -> Spacing(8.dp, 3.dp, 6.dp)
        1 -> Spacing(11.dp, 6.dp, 9.dp)
        else -> Spacing(14.dp, 10.dp, 12.dp)
    }
    CompositionLocalProvider(
        LocalDensity provides Density(base.density, base.fontScale * settings.textScale),
        LocalSpacing provides spacing,
        LocalCardSize provides settings.cardSize,
        LocalDark provides dark,
    ) {
        val scheme = if (dark) DarkScheme else LightScheme
        MaterialTheme(colorScheme = scheme) {
            // Screens sit on a transparent background (so the watermark shows). Without this, text on it
            // falls back to black, which is unreadable in dark mode.
            CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}

/** App background: gradient plus the OrionMD logo watermark. */
@Composable
fun WatermarkBackground(darkAlpha: Float, lightAlpha: Float, content: @Composable () -> Unit) {
    val dark = LocalDark.current
    val alpha = if (dark) darkAlpha else lightAlpha
    val bg = if (dark) Brush.verticalGradient(listOf(Color(0xFF0A0F2E), Color(0xFF060A20), Color(0xFF040716)))
    else Brush.verticalGradient(listOf(Color(0xFFECEEF3), Color(0xFFE2E5EC), Color(0xFFD9DDE6)))
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
