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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Surface colors for panels and the page, derived from the SangatAI palette (names kept from the earlier glass look). */
@Immutable
data class GlassColors(
    /** Page color. */
    val base: Color,
    /** Optional soft color fields behind the page. Empty in the paper look. */
    val blobs: List<Color>,
    val fill: Color,
    /** For panels that float over content: sheets, sticky headers, floating buttons. */
    val fillStrong: Color,
    /** Recessed areas inside a panel, such as the text field. */
    val inset: Color,
    /** Hairline edge around panels. */
    val border: Brush,
    /** The one strong surface: the primary button and the "You" node. */
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
 * "Paper & ink": warm off-white paper, near-black ink and one muted clay accent.
 * Kept quiet on purpose: color is reserved for meaning (who someone is to you, status),
 * never decoration. Fixed rather than wallpaper-derived, so the app looks like itself on every phone.
 * The XML side (window, widget, launcher icon) mirrors these in res/values/colors.xml and res/values-night/colors.xml.
 */
internal val Ink = Color(0xFF1B1A18)
internal val Paper = Color(0xFFF5F2EC)
internal val Clay = Color(0xFFA4492A)
internal val ClayLight = Color(0xFFE39A7A)

private val SangatLight = lightColorScheme(
    primary = Clay,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF3DED4),
    onPrimaryContainer = Color(0xFF3B1508),
    secondary = Color(0xFF85672A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEFE3C8),
    onSecondaryContainer = Color(0xFF2A1E05),
    tertiary = Color(0xFF7A4A58),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEFDCE1),
    onTertiaryContainer = Color(0xFF2E0E18),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEAE5DD),
    onSurfaceVariant = Color(0xFF67625A),
    outline = Color(0xFF8E877D),
    outlineVariant = Color(0xFFDDD7CD),
    inverseSurface = Color(0xFF2F2D2A),
    inverseOnSurface = Color(0xFFF2EFE9),
    inversePrimary = ClayLight,
    surfaceTint = Color.Transparent,
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE3DED6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAF8F4),
    surfaceContainer = Color(0xFFF0ECE5),
    surfaceContainerHigh = Color(0xFFEAE5DD),
    surfaceContainerHighest = Color(0xFFE3DDD3),
)

private val SangatDark = darkColorScheme(
    primary = ClayLight,
    onPrimary = Color(0xFF3B1508),
    primaryContainer = Color(0xFF5A2814),
    onPrimaryContainer = Color(0xFFF3DED4),
    secondary = Color(0xFFD8B877),
    onSecondary = Color(0xFF3A2A06),
    secondaryContainer = Color(0xFF54411A),
    onSecondaryContainer = Color(0xFFEFE3C8),
    tertiary = Color(0xFFD7A5B4),
    onTertiary = Color(0xFF3E1A25),
    tertiaryContainer = Color(0xFF5C3542),
    onTertiaryContainer = Color(0xFFEFDCE1),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF131211),
    onBackground = Color(0xFFECE8E1),
    surface = Color(0xFF131211),
    onSurface = Color(0xFFECE8E1),
    surfaceVariant = Color(0xFF2A2825),
    onSurfaceVariant = Color(0xFFA8A198),
    outline = Color(0xFF6F6A62),
    outlineVariant = Color(0xFF34312D),
    inverseSurface = Color(0xFFECE8E1),
    inverseOnSurface = Color(0xFF2F2D2A),
    inversePrimary = Clay,
    surfaceTint = Color.Transparent,
    surfaceBright = Color(0xFF393632),
    surfaceDim = Color(0xFF131211),
    surfaceContainerLowest = Color(0xFF0E0D0C),
    surfaceContainerLow = Color(0xFF1A1917),
    surfaceContainer = Color(0xFF1F1D1B),
    surfaceContainerHigh = Color(0xFF282624),
    surfaceContainerHighest = Color(0xFF312E2B),
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
            // so without this anything off the panels falls back to black.
            CompositionLocalProvider(LocalContentColor provides colorScheme.onSurface, content = content)
        }
    }
}

private fun glassColors(scheme: ColorScheme, dark: Boolean): GlassColors = if (dark) {
    GlassColors(
        base = scheme.surface,
        blobs = emptyList(),
        fill = scheme.surfaceContainerLow,
        fillStrong = scheme.surfaceContainer.copy(alpha = 0.97f),
        inset = scheme.onSurface.copy(alpha = 0.06f),
        border = SolidColor(scheme.outlineVariant),
        // Ink (here, paper) rather than a colored gradient.
        accent = SolidColor(scheme.onSurface),
        onAccent = scheme.surface,
        success = Color(0xFF8DB89A),
        warning = Color(0xFFD8B877),
    )
} else {
    GlassColors(
        base = scheme.surface,
        blobs = emptyList(),
        fill = scheme.surfaceContainerLowest,
        fillStrong = scheme.surfaceContainerLowest.copy(alpha = 0.97f),
        inset = scheme.onSurface.copy(alpha = 0.045f),
        border = SolidColor(scheme.outlineVariant),
        accent = SolidColor(scheme.onSurface),
        onAccent = scheme.surface,
        success = Color(0xFF4F7A5A),
        warning = Color(0xFFA87B2C),
    )
}

/** Serif for display (the editorial voice); the system sans for everything you read or tap. */
private val Display = FontFamily.Serif

private val AppTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontFamily = Display, fontWeight = FontWeight.Normal, letterSpacing = (-0.5).sp),
        headlineLarge = headlineLarge.copy(fontFamily = Display, fontWeight = FontWeight.Normal, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontFamily = Display, fontWeight = FontWeight.Normal, letterSpacing = (-0.5).sp),
        headlineSmall = headlineSmall.copy(fontFamily = Display, fontWeight = FontWeight.Normal, letterSpacing = (-0.25).sp),
        titleLarge = titleLarge.copy(fontFamily = Display, fontWeight = FontWeight.Normal, letterSpacing = (-0.25).sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
        bodyLarge = bodyLarge.copy(letterSpacing = 0.sp),
        bodyMedium = bodyMedium.copy(letterSpacing = 0.sp, lineHeight = 21.sp),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
        labelMedium = labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
        // Small uppercase section labels.
        labelSmall = labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp),
    )
}

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
