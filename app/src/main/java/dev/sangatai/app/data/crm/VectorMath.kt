package dev.sangatai.app.data.crm

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

object VectorMath {

    /** Unit length, so cosine similarity is a plain dot product. Null for an all-zero or non-finite vector. */
    fun normalized(v: FloatArray): FloatArray? {
        var sum = 0.0
        for (x in v) {
            if (!x.isFinite()) return null
            sum += x * x
        }
        if (sum <= 0.0) return null
        val inv = (1.0 / sqrt(sum)).toFloat()
        return FloatArray(v.size) { v[it] * inv }
    }

    fun dot(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Vector sizes differ: ${a.size} vs ${b.size}" }
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** float32, little-endian: 4 bytes per dimension. */
    fun toBytes(v: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(v)
        return buffer.array()
    }

    fun fromBytes(bytes: ByteArray): FloatArray {
        val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(floats.remaining()).also { floats.get(it) }
    }
}
