package dev.sangatai.app.data.crm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NameKeyTest {

    @Test
    fun `keys ignore case, accents, apostrophes and spacing`() {
        assertEquals("zoe obrien", NameKey.of("  Zoë  O'Brien "))
        assertEquals("sara khan", NameKey.of("Sara-Khan"))
        assertEquals("علی", NameKey.of(" علی "))
        assertEquals("", NameKey.of("..."))
    }

    @Test
    fun `a longer form only replaces a name it extends`() {
        assertEquals("Ali Khan", NameKey.longerName("Ali", "Ali Khan"))
        assertEquals("Ali Khan", NameKey.longerName("Ali Khan", "Ali"))
        assertEquals("Ali", NameKey.longerName("Ali", "Alina"))
    }

    @Test
    fun `words`() {
        assertEquals(1, NameKey.wordCount("ali"))
        assertEquals(2, NameKey.wordCount("ali khan"))
        assertEquals(0, NameKey.wordCount(""))
        assertEquals("ali", NameKey.firstWord("ali khan"))
    }

    @Test
    fun `clean trims quotes and punctuation`() {
        assertEquals("Sara Khan", NameKey.clean("  \"Sara   Khan\". "))
    }
}

class VectorMathTest {

    @Test
    fun `normalizes to unit length`() {
        assertArrayEquals(floatArrayOf(0.6f, 0.8f), VectorMath.normalized(floatArrayOf(3f, 4f)), 1e-6f)
        assertNull(VectorMath.normalized(floatArrayOf(0f, 0f)))
        assertNull(VectorMath.normalized(floatArrayOf(Float.NaN, 1f)))
    }

    @Test
    fun `blob round trip is exact`() {
        val v = floatArrayOf(0.1f, -2.5f, 3.4028235e38f, 1e-45f)
        val bytes = VectorMath.toBytes(v)
        assertEquals(16, bytes.size)
        assertArrayEquals(v, VectorMath.fromBytes(bytes), 0f)
    }

    @Test
    fun `dot of unit vectors is cosine`() {
        val a = VectorMath.normalized(floatArrayOf(1f, 0f))!!
        val b = VectorMath.normalized(floatArrayOf(1f, 1f))!!
        assertEquals(0.70710678f, VectorMath.dot(a, b), 1e-6f)
    }
}
