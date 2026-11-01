package dev.braindump.splitter.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Soft color fields that the translucent panels sit on, which is what makes them read as
 * frosted glass. They swirl into place once on launch and then hold still, so an idle screen
 * doesn't keep the display redrawing. Positions are read in the draw phase only.
 */
@Composable
fun GlassBackdrop(modifier: Modifier = Modifier) {
    val glass = Glass.colors
    val settle = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        settle.animateTo(1f, tween(SETTLE_MS, easing = FastOutSlowInEasing))
    }
    Canvas(modifier.fillMaxSize()) {
        drawRect(glass.base)
        val progress = settle.value
        val turn = (1f - progress) * SETTLE_TURN
        glass.blobs.forEachIndexed { index, color ->
            val anchor = BLOB_ANCHORS[index % BLOB_ANCHORS.size]
            val angle = turn + index * 2.1
            val center = Offset(
                x = size.width * (anchor.x + 0.14f * cos(angle).toFloat()),
                y = size.height * (anchor.y + 0.08f * sin(angle).toFloat()),
            )
            val radius = size.maxDimension * (0.4f + 0.15f * progress)
            val tint = color.copy(alpha = color.alpha * progress)
            drawCircle(
                brush = Brush.radialGradient(listOf(tint, tint.copy(alpha = 0f)), center, radius),
                radius = radius,
                center = center,
            )
        }
    }
}

/** A frosted panel: translucent fill and a hairline that catches light on top, no drop shadow. */
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

/** Springy shrink while pressed, the iOS stand-in for a ripple. */
@Composable
fun rememberPressScale(interactionSource: MutableInteractionSource, pressedScale: Float = 0.96f): () -> Float {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    return { scale }
}

/** The primary action: a gradient pill. */
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

/** A round glass button for secondary actions. */
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
    val scale = rememberPressScale(interaction, pressedScale = 0.9f)
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

private const val SETTLE_MS = 1_600
private const val SETTLE_TURN = PI / 2

private val BLOB_ANCHORS = listOf(Offset(0.15f, 0.12f), Offset(0.9f, 0.45f), Offset(0.25f, 0.85f))
