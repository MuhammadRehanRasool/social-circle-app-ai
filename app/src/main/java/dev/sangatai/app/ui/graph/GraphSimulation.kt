package dev.sangatai.app.ui.graph

import dev.sangatai.app.domain.model.SocialGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A small force-directed layout. Node 0 is the user, pinned at the origin; everyone else is
 * tied to the user by a spring whose length grows with how long ago and how rarely they came
 * up, so the people the user talks about most sit closest. People said to be connected pull
 * together, everyone pushes everyone else apart, and nodes never overlap.
 *
 * World units are dp. Runs only while "hot": it cools down and stops, and is reheated when the
 * graph changes or a node is dragged, so an idle screen costs nothing.
 */
class GraphSimulation {

    /** Index 0 is the user. Ids are person ids; the user is [ME]. */
    var ids = LongArray(1) { ME }
        private set
    var x = FloatArray(1)
        private set
    var y = FloatArray(1)
        private set
    private var vx = FloatArray(1)
    private var vy = FloatArray(1)

    /** Node radius in dp. */
    var radius = FloatArray(1) { ME_RADIUS }
        private set

    /** 0 (rarely, long ago) to 1 (often, lately). */
    var closeness = FloatArray(1) { 1f }
        private set

    private var indexById = HashMap<Long, Int>()

    // Springs: endpoints, rest length and stiffness.
    private var springA = IntArray(0)
    private var springB = IntArray(0)
    private var springRest = FloatArray(0)
    private var springK = FloatArray(0)

    private var pinned = -1

    private var alpha = 0f
    private val hot = MutableStateFlow(false)

    val size: Int get() = ids.size

    fun indexOf(id: Long): Int? = indexById[id]

    /**
     * Takes a new graph, keeping everyone's position. Newcomers start next to someone they're
     * connected to, or on a spiral around the user.
     */
    fun update(graph: SocialGraph, now: Long) {
        val oldX = HashMap<Long, Pair<Float, Float>>(ids.size)
        for (i in ids.indices) oldX[ids[i]] = x[i] to y[i]

        val people = graph.people
        val n = people.size + 1
        val newIds = LongArray(n)
        newIds[0] = ME
        people.forEachIndexed { i, p -> newIds[i + 1] = p.id }
        val newIndex = HashMap<Long, Int>(n * 2)
        for (i in 0 until n) newIndex[newIds[i]] = i

        val newRadius = FloatArray(n)
        val newCloseness = FloatArray(n)
        newRadius[0] = ME_RADIUS
        newCloseness[0] = 1f
        people.forEachIndexed { i, p ->
            val days = max(0L, now - p.lastInteractionAt) / DAY_MS.toFloat()
            val recency = exp(-days / RECENCY_DAYS)
            val frequency = min(1f, ln(1f + p.mentionCount) / ln(1f + FREQUENT_MENTIONS))
            newCloseness[i + 1] = 0.6f * recency + 0.4f * frequency
            newRadius[i + 1] = MIN_RADIUS + (MAX_RADIUS - MIN_RADIUS) * frequency
        }

        // Springs: the user to everyone, then the ties between people.
        val edges = graph.edges.filter { it.a in newIndex && it.b in newIndex }
        val springs = n - 1 + edges.size
        val sa = IntArray(springs)
        val sb = IntArray(springs)
        val rest = FloatArray(springs)
        val k = FloatArray(springs)
        var s = 0
        for (i in 1 until n) {
            sa[s] = 0
            sb[s] = i
            rest[s] = NEAR + (FAR - NEAR) * (1f - newCloseness[i])
            k[s] = ME_SPRING
            s++
        }
        // Who each person is tied to, for placing newcomers.
        val neighbor = IntArray(n) { -1 }
        for (edge in edges) {
            val a = newIndex.getValue(edge.a)
            val b = newIndex.getValue(edge.b)
            sa[s] = a
            sb[s] = b
            rest[s] = TIE_LENGTH
            k[s] = if (edge.labels.isNotEmpty()) LABELED_SPRING else TIE_SPRING + 0.01f * min(edge.coMentions, 5)
            s++
            if (neighbor[a] < 0) neighbor[a] = b
            if (neighbor[b] < 0) neighbor[b] = a
        }

        val nx = FloatArray(n)
        val ny = FloatArray(n)
        // Known people first, so a newcomer can start beside a known neighbor.
        val placed = BooleanArray(n)
        placed[0] = true
        for (i in 1 until n) {
            oldX[newIds[i]]?.let { (px, py) ->
                nx[i] = px
                ny[i] = py
                placed[i] = true
            }
        }
        var spiral = 0
        for (i in 1 until n) {
            if (placed[i]) continue
            val j = neighbor[i]
            val jitter = jitter(newIds[i])
            if (j > 0 && placed[j]) {
                nx[i] = nx[j] + TIE_LENGTH * cos(jitter)
                ny[i] = ny[j] + TIE_LENGTH * sin(jitter)
            } else {
                // Golden-angle spiral: even spacing without randomness, so the layout is repeatable.
                val r = NEAR * 0.7f + 34f * sqrt(spiral.toFloat() + 1f)
                val angle = spiral * GOLDEN_ANGLE + jitter * 0.1f
                nx[i] = r * cos(angle)
                ny[i] = r * sin(angle)
                spiral++
            }
            placed[i] = true
        }

        val pinnedId = if (pinned >= 0 && pinned < ids.size) ids[pinned] else null
        ids = newIds
        indexById = newIndex
        x = nx
        y = ny
        vx = FloatArray(n)
        vy = FloatArray(n)
        radius = newRadius
        closeness = newCloseness
        springA = sa
        springB = sb
        springRest = rest
        springK = k
        pinned = pinnedId?.let { newIndex[it] } ?: -1

        reheat(if (oldX.size <= 1) 1f else 0.6f)
    }

    fun reheat(to: Float = 0.5f) {
        alpha = max(alpha, to)
        hot.value = true
    }

    val isHot: Boolean get() = hot.value

    suspend fun awaitHeat() {
        hot.first { it }
    }

    /** Holds [index] under the finger while dragging; -1 lets go. */
    fun pin(index: Int) {
        pinned = index
    }

    fun moveBy(index: Int, dx: Float, dy: Float) {
        if (index <= 0 || index >= size) return
        x[index] += dx
        y[index] += dy
        vx[index] = 0f
        vy[index] = 0f
    }

    /** One tick. Returns false once it has cooled down and stopped. */
    fun step(): Boolean {
        val n = size
        if (n <= 1) {
            cool()
            return false
        }
        val a = alpha

        // Everyone pushes everyone apart; overlapping nodes are pushed apart hard.
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                var dx = x[j] - x[i]
                var dy = y[j] - y[i]
                var d2 = dx * dx + dy * dy
                if (d2 < 0.01f) {
                    // Exactly on top of each other: nudge apart in a fixed direction.
                    dx = 0.1f * (j - i)
                    dy = 0.1f
                    d2 = dx * dx + dy * dy
                }
                val d = sqrt(d2)
                var f = REPULSION * a / d2
                val minD = radius[i] + radius[j] + GAP
                if (d < minD) f += (minD - d) / d * COLLISION
                val fx = dx * f
                val fy = dy * f
                vx[i] -= fx
                vy[i] -= fy
                vx[j] += fx
                vy[j] += fy
            }
        }

        // Springs.
        for (s in springA.indices) {
            val i = springA[s]
            val j = springB[s]
            val dx = x[j] - x[i]
            val dy = y[j] - y[i]
            val d = max(sqrt(dx * dx + dy * dy), 0.01f)
            val f = (d - springRest[s]) / d * springK[s] * a
            val fx = dx * f
            val fy = dy * f
            vx[i] += fx
            vy[i] += fy
            vx[j] -= fx
            vy[j] -= fy
        }

        // Integrate. The user and a dragged node stay put.
        for (i in 1 until n) {
            if (i == pinned) {
                vx[i] = 0f
                vy[i] = 0f
                continue
            }
            vx[i] = (vx[i] - x[i] * GRAVITY * a) * DAMPING
            vy[i] = (vy[i] - y[i] * GRAVITY * a) * DAMPING
            // A cap keeps one bad frame from flinging a node off screen.
            val v2 = vx[i] * vx[i] + vy[i] * vy[i]
            if (v2 > MAX_SPEED * MAX_SPEED) {
                val scale = MAX_SPEED / sqrt(v2)
                vx[i] *= scale
                vy[i] *= scale
            }
            x[i] += vx[i]
            y[i] += vy[i]
        }
        x[0] = 0f
        y[0] = 0f
        vx[0] = 0f
        vy[0] = 0f

        alpha *= COOLING
        if (alpha < MIN_ALPHA && pinned < 0) {
            cool()
            return false
        }
        return true
    }

    private fun cool() {
        alpha = 0f
        hot.value = false
    }

    private fun jitter(id: Long): Float {
        val h = (id * 2654435761L) xor (id ushr 16)
        return ((h and 0xFFFFL).toFloat() / 0xFFFF) * (2 * PI).toFloat()
    }

    companion object {
        const val ME = -1L
        const val ME_RADIUS = 30f
        const val MIN_RADIUS = 18f
        const val MAX_RADIUS = 30f

        /** Spring length to the user for the closest and the most distant person. */
        const val NEAR = 110f
        const val FAR = 330f
        const val TIE_LENGTH = 90f

        private const val ME_SPRING = 0.035f
        private const val TIE_SPRING = 0.05f
        private const val LABELED_SPRING = 0.09f
        private const val REPULSION = 1_400f
        private const val COLLISION = 0.5f
        private const val GAP = 14f
        private const val GRAVITY = 0.004f
        private const val DAMPING = 0.6f
        private const val MAX_SPEED = 40f
        private const val COOLING = 0.985f
        private const val MIN_ALPHA = 0.008f

        private const val DAY_MS = 86_400_000L
        private const val RECENCY_DAYS = 45f
        private const val FREQUENT_MENTIONS = 20f
        private val GOLDEN_ANGLE = (PI * (3 - sqrt(5.0))).toFloat()
    }
}
