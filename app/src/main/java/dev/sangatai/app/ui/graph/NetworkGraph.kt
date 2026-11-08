package dev.sangatai.app.ui.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sangatai.app.domain.model.Person
import dev.sangatai.app.domain.model.SocialGraph
import dev.sangatai.app.ui.common.RelationGroup
import dev.sangatai.app.ui.common.colors
import dev.sangatai.app.ui.common.initialsOf
import dev.sangatai.app.ui.theme.Glass
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The user's network: the user in the middle, everyone they've talked about around them (closer
 * the more often and more recently), and lines between people who are connected.
 *
 * Tap someone to highlight their ties, tap them again to open them. Drag a person to move
 * them, drag the background to pan, pinch to zoom.
 *
 * @param recenter bump to reset pan and zoom.
 */
@Composable
fun NetworkGraph(
    graph: SocialGraph,
    selectedId: Long?,
    visibleGroups: Set<RelationGroup>,
    onSelect: (Long?) -> Unit,
    onOpen: (Long) -> Unit,
    recenter: Int,
    modifier: Modifier = Modifier,
) {
    val sim = remember { GraphSimulation() }
    // Bumped every simulation tick; read only while drawing, so ticks redraw without recomposing.
    var frame by remember { mutableIntStateOf(0) }
    var userScale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val people = remember(graph) { graph.people.associateBy { it.id } }
    val groups = remember(graph) { graph.people.associate { it.id to RelationGroup.of(it.relation) } }
    val neighbors = remember(graph) {
        val map = HashMap<Long, MutableSet<Long>>()
        for (edge in graph.edges) {
            map.getOrPut(edge.a) { HashSet() } += edge.b
            map.getOrPut(edge.b) { HashSet() } += edge.a
        }
        map
    }

    val currentSelected by rememberUpdatedState(selectedId)
    val currentVisible by rememberUpdatedState(visibleGroups)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnOpen by rememberUpdatedState(onOpen)

    LaunchedEffect(graph) {
        sim.update(graph, System.currentTimeMillis())
        frame++
    }
    LaunchedEffect(sim) {
        while (true) {
            if (sim.isHot) {
                withFrameNanos { }
                sim.step()
                frame++
            } else {
                sim.awaitHeat()
            }
        }
    }
    LaunchedEffect(recenter) {
        if (recenter > 0) {
            userScale = 1f
            pan = Offset.Zero
            frame++
        }
    }

    // Colors and text styles, resolved in composition so drawing only reads them.
    val palette = RelationGroup.entries.map { it.colors() }
    val scheme = MaterialTheme.colorScheme
    val glass = Glass.colors
    val nameStyle = MaterialTheme.typography.labelMedium.copy(color = scheme.onSurface, fontWeight = FontWeight.Medium)
    val edgeStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.tertiary)
    val measurer = rememberTextMeasurer(cacheSize = 256)

    fun isVisible(i: Int): Boolean {
        if (i == 0) return true
        val group = groups[sim.ids[i]] ?: RelationGroup.UNKNOWN
        return group in currentVisible
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it }
            .semantics { contentDescription = "Network of ${graph.people.size} people" }
            .pointerInput(sim) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val k = worldScale(canvasSize, density, userScale)
                    val center = Offset(canvasSize.width / 2f, canvasSize.height / 2f) + pan
                    val hit = hitTest(sim, down.position, center, k, 10.dp.toPx(), ::isVisible)
                    val slop = viewConfiguration.touchSlop

                    if (hit != null && hit > 0) {
                        // Drag a person, or tap them.
                        var moved = false
                        sim.pin(hit)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (!moved && (change.position - down.position).getDistance() > slop) moved = true
                            if (moved) {
                                val delta = change.positionChange()
                                val scaleNow = worldScale(canvasSize, density, userScale)
                                sim.moveBy(hit, delta.x / scaleNow, delta.y / scaleNow)
                                sim.reheat(0.25f)
                                change.consume()
                            }
                        }
                        sim.pin(-1)
                        sim.reheat(0.15f)
                        if (!moved) {
                            val id = sim.ids[hit]
                            if (currentSelected == id) currentOnOpen(id) else currentOnSelect(id)
                        }
                    } else {
                        // Pan and zoom, or a tap on the background (or on the user).
                        var moved = false
                        var travelled = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) break
                            val zoom = event.calculateZoom()
                            val panChange = event.calculatePan()
                            travelled += panChange.getDistance()
                            if (!moved && (event.changes.size > 1 || travelled > slop)) moved = true
                            if (!moved) continue
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val newScale = (userScale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                            val applied = newScale / userScale
                            if (centroid.isSpecified) {
                                val c = centroid - Offset(canvasSize.width / 2f, canvasSize.height / 2f)
                                pan = c - (c - pan) * applied
                            }
                            pan += panChange
                            userScale = newScale
                            frame++
                            event.changes.forEach { it.consume() }
                        }
                        if (!moved) currentOnSelect(null)
                    }
                }
            },
    ) {
        if (frame < 0) return@Canvas // reads the tick, so every tick redraws

        val k = worldScale(canvasSize, density, userScale)
        val center = Offset(size.width / 2f, size.height / 2f) + pan
        fun at(i: Int) = Offset(center.x + sim.x[i] * k, center.y + sim.y[i] * k)

        val n = sim.size
        val selected = currentSelected?.let { sim.indexOf(it) }
        val selectedId = currentSelected
        val near = selectedId?.let { neighbors[it] }.orEmpty()
        fun focus(i: Int): Boolean {
            if (selected == null) return true
            val id = sim.ids[i]
            return i == 0 || id == selectedId || id in near
        }

        // Orbits: how close people are to the user.
        val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 8.dp.toPx()))
        for (r in ORBITS) {
            drawCircle(
                color = scheme.outlineVariant.copy(alpha = 0.35f),
                radius = r * k,
                center = center,
                style = Stroke(width = 1.dp.toPx(), pathEffect = dash),
            )
        }

        // The user to everyone.
        for (i in 1 until n) {
            if (!isVisible(i)) continue
            val strong = selected == i
            val alpha = when {
                strong -> 0.8f
                selected != null -> 0.06f
                else -> 0.12f + 0.3f * sim.closeness[i]
            }
            drawLine(
                color = if (strong) scheme.primary else scheme.outline,
                start = center,
                end = at(i),
                strokeWidth = (if (strong) 2.dp else 1.dp).toPx(),
                alpha = alpha,
            )
        }

        // Ties between people.
        val showEdgeLabels = userScale >= 0.9f
        for (edge in graph.edges) {
            val i = sim.indexOf(edge.a) ?: continue
            val j = sim.indexOf(edge.b) ?: continue
            if (!isVisible(i) || !isVisible(j)) continue
            val touches = selectedId != null && (edge.a == selectedId || edge.b == selectedId)
            val labeled = edge.labels.isNotEmpty()
            val color = when {
                touches -> scheme.primary
                labeled -> scheme.tertiary
                else -> scheme.onSurfaceVariant
            }
            val alpha = when {
                touches -> 0.9f
                selected != null -> 0.08f
                labeled -> 0.7f
                else -> 0.4f
            }
            val width = 1.5f + 0.5f * min(edge.coMentions, 6) + if (touches) 1f else 0f
            drawLine(color, at(i), at(j), strokeWidth = width.dp.toPx(), alpha = alpha, cap = StrokeCap.Round)
            if (labeled && (touches || (selected == null && showEdgeLabels))) {
                val mid = (at(i) + at(j)) / 2f
                drawPillText(measurer, edge.labels.first(), edgeStyle, mid, glass.fillStrong, alpha = if (touches) 1f else 0.9f)
            }
        }

        // People.
        val showNames = userScale * fitScale(canvasSize, density) >= 0.55f
        for (i in 1 until n) {
            if (!isVisible(i)) continue
            val id = sim.ids[i]
            val person: Person = people[id] ?: continue
            val group = groups[id] ?: RelationGroup.UNKNOWN
            val colors = palette[group.ordinal]
            val p = at(i)
            val r = sim.radius[i] * k
            val dimmed = !focus(i)
            val alpha = if (dimmed) 0.18f else 0.6f + 0.4f * sim.closeness[i]

            if (selected == i) {
                drawCircle(scheme.primary, radius = r + 6.dp.toPx(), center = p, alpha = 0.25f)
                drawCircle(scheme.primary, radius = r + 6.dp.toPx(), center = p, style = Stroke(2.dp.toPx()))
            }
            drawCircle(colors.fill, radius = r, center = p, alpha = alpha)
            drawCircle(scheme.surface, radius = r, center = p, style = Stroke(2.dp.toPx()), alpha = alpha)
            if (r >= 11.dp.toPx()) {
                val initials = measurer.measure(
                    initialsOf(person.name),
                    TextStyle(color = colors.onFill, fontSize = (r / density / 1.9f).coerceIn(9f, 18f).sp, fontWeight = FontWeight.SemiBold),
                )
                drawText(
                    initials,
                    topLeft = p - Offset(initials.size.width / 2f, initials.size.height / 2f),
                    alpha = if (dimmed) 0.4f else 1f,
                )
            }
            if (!dimmed && (showNames || focus(i) && selected != null)) {
                val label = measurer.measure(
                    person.name,
                    nameStyle,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    constraints = Constraints(maxWidth = 120.dp.roundToPx()),
                )
                val top = Offset(p.x - label.size.width / 2f, p.y + r + 4.dp.toPx())
                val padH = 6.dp.toPx()
                val padV = 2.dp.toPx()
                drawRoundRect(
                    color = glass.fillStrong,
                    topLeft = top - Offset(padH, padV),
                    size = Size(label.size.width + 2 * padH, label.size.height + 2 * padV),
                    cornerRadius = CornerRadius(10.dp.toPx()),
                )
                drawText(label, topLeft = top)
            }
        }

        // The user, on top of their own lines.
        val meR = GraphSimulation.ME_RADIUS * k
        drawCircle(brush = glass.accent, radius = meR, center = center)
        drawCircle(scheme.surface, radius = meR, center = center, style = Stroke(2.dp.toPx()))
        val me = measurer.measure("You", TextStyle(color = glass.onAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
        drawText(me, topLeft = center - Offset(me.size.width / 2f, me.size.height / 2f))
    }
}

/** dp in the simulation to px on screen: fits the outer orbit on screen, then the user's zoom. */
private fun worldScale(size: IntSize, density: Float, userScale: Float): Float =
    fitScale(size, density) * userScale * density

private fun fitScale(size: IntSize, density: Float): Float {
    if (size.width == 0 || size.height == 0) return 1f
    val half = min(size.width, size.height) / 2f
    return min(1f, half / ((GraphSimulation.FAR + 60f) * density))
}

private fun hitTest(
    sim: GraphSimulation,
    point: Offset,
    center: Offset,
    k: Float,
    touchPad: Float,
    visible: (Int) -> Boolean,
): Int? {
    var best: Int? = null
    var bestD = Float.MAX_VALUE
    for (i in 0 until sim.size) {
        if (!visible(i)) continue
        val dx = point.x - (center.x + sim.x[i] * k)
        val dy = point.y - (center.y + sim.y[i] * k)
        val d = sqrt(dx * dx + dy * dy)
        val reach = max(sim.radius[i] * k, 14f) + touchPad
        if (d <= reach && d < bestD) {
            best = i
            bestD = d
        }
    }
    return best
}

private fun DrawScope.drawPillText(
    measurer: androidx.compose.ui.text.TextMeasurer,
    text: String,
    style: TextStyle,
    center: Offset,
    background: Color,
    alpha: Float,
) {
    val layout = measurer.measure(text, style, maxLines = 1, overflow = TextOverflow.Ellipsis,
        constraints = Constraints(maxWidth = 110.dp.roundToPx()))
    val padH = 6.dp.toPx()
    val padV = 2.dp.toPx()
    val topLeft = center - Offset(layout.size.width / 2f, layout.size.height / 2f)
    drawRoundRect(
        color = background,
        topLeft = topLeft - Offset(padH, padV),
        size = Size(layout.size.width + 2 * padH, layout.size.height + 2 * padV),
        cornerRadius = CornerRadius(10.dp.toPx()),
        alpha = alpha,
    )
    drawText(layout, topLeft = topLeft, alpha = alpha)
}

private const val MIN_ZOOM = 0.3f
private const val MAX_ZOOM = 4f
private val ORBITS = floatArrayOf(GraphSimulation.NEAR, (GraphSimulation.NEAR + GraphSimulation.FAR) / 2f, GraphSimulation.FAR)
