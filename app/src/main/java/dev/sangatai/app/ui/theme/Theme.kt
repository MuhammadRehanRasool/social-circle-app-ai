package dev.sangatai.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colors for the frosted-glass look, derived from the SangatAI palette. */
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

private val LocalGlass = staticCompositionLocalOf<GlassColors> { error("SangatTheme is missing") }

object Glass {
    val colors: GlassColors
        @Composable
        @ReadOnlyComposable
        get() = LocalGlass.current
}

/**
 * SangatAI's own warm palette: terracotta primary, honey secondary, rose tertiary on cream.
 * Fixed rather than wallpaper-derived, so the app looks like itself on every phone.
 * The XML side (window, widget, launcher icon) mirrors these in res/values/colors.xml and res/values-night/colors.xml.
 */
private val SangatLight = lightColorScheme(
    primary = Color(0xFFC2532F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBCF),
    onPrimaryContainer = Color(0xFF3A0B00),
    secondary = Color(0xFF845400),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDDB3),
    onSecondaryContainer = Color(0xFF2A1800),
    tertiary = Color(0xFF9C4361),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD9E2),
    onTertiaryContainer = Color(0xFF3E001D),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8F2),
    onBackground = Color(0xFF231A15),
    surface = Color(0xFFFFF8F2),
    onSurface = Color(0xFF231A15),
    surfaceVariant = Color(0xFFF5DED5),
    onSurfaceVariant = Color(0xFF53433E),
    outline = Color(0xFF85736D),
    outlineVariant = Color(0xFFD8C2BA),
    inverseSurface = Color(0xFF392E2A),
    inverseOnSurface = Color(0xFFFFEDE6),
    inversePrimary = Color(0xFFFFB59C),
    surfaceTint = Color(0xFFC2532F),
    surfaceBright = Color(0xFFFFF8F2),
    surfaceDim = Color(0xFFE8D7D0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF1EA),
    surfaceContainer = Color(0xFFFCEAE2),
    surfaceContainerHigh = Color(0xFFF6E4DC),
    surfaceContainerHighest = Color(0xFFF0DED6),
)

private val SangatDark = darkColorScheme(
    primary = Color(0xFFFFB59C),
    onPrimary = Color(0xFF5C1A03),
    primaryContainer = Color(0xFF8E3A1A),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Color(0xFFF3BD6E),
    onSecondary = Color(0xFF452B00),
    secondaryContainer = Color(0xFF633F00),
    onSecondaryContainer = Color(0xFFFFDDB3),
    tertiary = Color(0xFFFFB1C6),
    onTertiary = Color(0xFF5F1131),
    tertiaryContainer = Color(0xFF7E2A48),
    onTertiaryContainer = Color(0xFFFFD9E2),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1C1411),
    onBackground = Color(0xFFF1DFD8),
    surface = Color(0xFF1C1411),
    onSurface = Color(0xFFF1DFD8),
    surfaceVariant = Color(0xFF53433E),
    onSurfaceVariant = Color(0xFFD8C2BA),
    outline = Color(0xFFA08D86),
    outlineVariant = Color(0xFF53433E),
    inverseSurface = Color(0xFFF1DFD8),
    inverseOnSurface = Color(0xFF392E2A),
    inversePrimary = Color(0xFFC2532F),
    surfaceTint = Color(0xFFFFB59C),
    surfaceBright = Color(0xFF443934),
    surfaceDim = Color(0xFF1C1411),
    surfaceContainerLowest = Color(0xFF160F0C),
    surfaceContainerLow = Color(0xFF241915),
    surfaceContainer = Color(0xFF291D19),
    surfaceContainerHigh = Color(0xFF342823),
    surfaceContainerHighest = Color(0xFF3F322D),
)

@Composable
fun SangatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) SangatDark else SangatLight
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
        fill = Color(0xFFFFEDE6).copy(alpha = 0.07f),
        fillStrong = scheme.surfaceContainerHigh.copy(alpha = 0.86f),
        inset = Color(0xFFFFEDE6).copy(alpha = 0.05f),
        border = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.05f))),
        accent = Brush.linearGradient(listOf(scheme.primary, scheme.tertiary)),
        onAccent = scheme.onPrimary,
        success = Color(0xFF8CD4A0),
        warning = Color(0xFFF3BD6E),
    )
} else {
    GlassColors(
        base = scheme.surface,
        blobs = listOf(scheme.primaryContainer, scheme.tertiaryContainer, scheme.secondaryContainer),
        fill = Color(0xFFFFFCFA).copy(alpha = 0.6f),
        fillStrong = Color(0xFFFFFCFA).copy(alpha = 0.88f),
        inset = scheme.onSurface.copy(alpha = 0.04f),
        border = Brush.verticalGradient(listOf(Color.White, scheme.outlineVariant.copy(alpha = 0.6f))),
        accent = Brush.linearGradient(listOf(scheme.primary, scheme.tertiary)),
        onAccent = scheme.onPrimary,
        success = Color(0xFF3E8E5A),
        warning = Color(0xFFB86E00),
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
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
