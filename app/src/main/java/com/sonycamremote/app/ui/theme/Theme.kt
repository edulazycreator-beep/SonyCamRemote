package com.sonycamremote.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/** Dark Cinema palette (design spec §5). */
object Cinema {
    val Background = Color(0xFF090B0F)
    val Surface = Color(0xFF131720)
    val Surface2 = Color(0xFF1A202B)
    val Accent = Color(0xFFE8A23A)
    val TextPrimary = Color(0xFFF5F5F5)
    val TextSecondary = Color(0xFF8993A3)
    val Success = Color(0xFF39C98A)
    val Warning = Color(0xFFF0B44D)
    val Error = Color(0xFFED5A5A)
    val Outline = Color(0xFF2A3140)
    val Record = Color(0xFFE53935)
}

private val scheme = darkColorScheme(
    primary = Cinema.Accent,
    onPrimary = Cinema.Background,
    secondary = Cinema.TextSecondary,
    background = Cinema.Background,
    onBackground = Cinema.TextPrimary,
    surface = Cinema.Surface,
    onSurface = Cinema.TextPrimary,
    surfaceVariant = Cinema.Surface2,
    onSurfaceVariant = Cinema.TextSecondary,
    surfaceContainer = Cinema.Surface,
    surfaceContainerHigh = Cinema.Surface2,
    surfaceContainerHighest = Cinema.Surface2,
    outline = Cinema.Outline,
    error = Cinema.Error,
)

/** Tabular figures keep exposure readouts from jittering as values change. */
val NumericStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.SemiBold,
    fontSize = 15.sp,
    fontFeatureSettings = "tnum",
    textAlign = TextAlign.Center,
    color = Cinema.TextPrimary,
)

private val typography = Typography().let { t ->
    t.copy(
        labelSmall = t.labelSmall.copy(letterSpacing = 0.8.sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun SonyCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
