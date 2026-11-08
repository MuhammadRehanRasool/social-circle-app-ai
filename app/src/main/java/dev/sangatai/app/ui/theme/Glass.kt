package dev.sangatai.app.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The page: plain paper. No moving color fields, so an idle screen never redraws. */
@Composable
fun GlassBackdrop(modifier: Modifier = Modifier) {
    val base = Glass.colors.base
    Canvas(modifier.fillMaxSize()) { drawRect(base) }
}

/**
 * The SangatAI mark: you (the center dot) inside your circle, an open ring with
 * one person stepping in through the gap. Same geometry as res/drawable/ic_launcher_foreground.xml.
 */
@Composable
fun SangatMark(
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    ring: Color = MaterialTheme.colorScheme.onSurface,
    dot: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(modifier.size(size)) {
        // Drawn on a 44-unit grid: ring radius 22 at 108 scale maps to 0.4 of the box.
        val u = this.size.minDimension / 44f
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        val r = 16f * u
        drawArc(
            color = ring,
            startAngle = -15f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(c.x - r, c.y - r),
            size = Size(2 * r, 2 * r),
            style = Stroke(width = 4.4f * u, cap = StrokeCap.Round),
        )
        val a = (-45.0) * PI / 180.0
        drawCircle(dot, radius = 3.3f * u, center = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat()))
        drawCircle(ring, radius = 5.1f * u, center = c)
    }
}

/** A card: paper-white fill and a hairline edge, no drop shadow. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    strong: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val glass = Glass.colors
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Box(
            modifier = modifier
                .clip(shape)
                .background(if (strong) glass.fillStrong else glass.fill)
                .border(1.dp, glass.border, shape),
            content = content,
        )
    }
}

/** A small, unbouncy settle while pressed, in place of a ripple. */
@Composable
fun rememberPressScale(interactionSource: MutableInteractionSource, pressedScale: Float = 0.97f): () -> Float {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "press",
    )
    return { scale }
}

/** The primary action: a solid ink pill. */
@Composable
fun AccentButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val glass = Glass.colors
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction)
    val alpha by animateFloatAsState(if (enabled) 1f else 0.4f, label = "enabled")
    CompositionLocalProvider(LocalContentColor provides glass.onAccent) {
        Row(
            modifier = modifier
                .graphicsLayer {
                    scaleX = scale()
                    scaleY = scale()
                    this.alpha = alpha
                }
                .clip(CircleShape)
                .background(glass.accent)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(contentPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** A round outlined button for secondary actions. */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    strong: Boolean = true,
    onClickLabel: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction, pressedScale = 0.94f)
    GlassPanel(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale()
                scaleY = scale()
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = onClickLabel,
                onClick = onClick,
            ),
        shape = CircleShape,
        strong = strong,
    ) {
        Box(Modifier.align(Alignment.Center), content = content)
    }
}
