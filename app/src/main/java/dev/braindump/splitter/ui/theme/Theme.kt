package dev.braindump.splitter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colors for the frosted-glass look, derived from the wallpaper palette. */
@Immutable
data class GlassColors(
    /** Page color under the drifting color fields. */
    val base: Color,
    /** Soft color fields behind every glass panel. */
    val blobs: List<Color>,
    val fill: Color,
    /** For panels that float over content: sheets, sticky headers, floating buttons. */
    val fillStrong: Color,
    /** Recessed areas inside a panel, such as the text field. */
    val inset: Color,
    /** Hairline edge: bright on top where light hits, fading toward the bottom. */
    val border: Brush,
    val accent: Brush,
    val onAccent: Color,
    val success: Color,
    val warning: Color,
)

private val LocalGlass = staticCompositionLocalOf<GlassColors> { error("BrainDumpTheme is missing") }

object Glass {
    val colors: GlassColors
        @Composable
        @ReadOnlyComposable
        get() = LocalGlass.current
}

/** Material You colors straight from the wallpaper (minSdk 31 always has them). */
@Composable
fun BrainDumpTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = remember(context, darkTheme) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    val glass = remember(colorScheme, darkTheme) { glassColors(colorScheme, darkTheme) }
    CompositionLocalProvider(LocalGlass provides glass) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
        ) {
            // Nothing here sits on an opaque Surface, which is what normally sets the text color,
            // so without this anything off the glass panels falls back to black.
            CompositionLocalProvider(LocalContentColor provides colorScheme.onSurface, content = content)
        }
    }
}

private fun glassColors(scheme: ColorScheme, dark: Boolean): GlassColors = if (dark) {
    GlassColors(
        base = scheme.surface,
        blobs = listOf(
            scheme.primary.copy(alpha = 0.34f),
            scheme.tertiary.copy(alpha = 0.26f),
            scheme.secondary.copy(alpha = 0.22f),
        ),
        fill = Color.White.copy(alpha = 0.07f),
        fillStrong = scheme.surfaceContainerHigh.copy(alpha = 0.86f),
        inset = Color.White.copy(alpha = 0.05f),
        border = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.05f))),
        accent = Brush.linearGradient(listOf(scheme.primary, scheme.tertiary)),
        onAccent = scheme.onPrimary,
        success = Color(0xFF30D158),
        warning = Color(0xFFFF9F0A),
    )
} else {
    GlassColors(
        base = scheme.surface,
        blobs = listOf(scheme.primaryContainer, scheme.tertiaryContainer, scheme.secondaryContainer),
        fill = Color.White.copy(alpha = 0.55f),
        fillStrong = Color.White.copy(alpha = 0.84f),
        inset = scheme.onSurface.copy(alpha = 0.04f),
        border = Brush.verticalGradient(listOf(Color.White, scheme.outlineVariant.copy(alpha = 0.6f))),
        accent = Brush.linearGradient(listOf(scheme.primary, scheme.tertiary)),
        onAccent = scheme.onPrimary,
        success = Color(0xFF34C759),
        warning = Color(0xFFFF9500),
    )
}

private val AppTypography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
