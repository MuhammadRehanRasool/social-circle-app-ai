package dev.sangatai.app.data.ai

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pulls the first JSON value out of a small model's answer: drops code fences and prose around
 * it, and repairs an answer the token limit cut off (closes the open string, arrays and objects,
 * then cuts back to the last complete element if that isn't enough). Pure; unit-tested.
 */
object JsonRepair {

    @OptIn(ExperimentalSerializationApi::class)
    private val lenient = Json {
        isLenient = true
        allowTrailingComma = true
        allowComments = true
    }

    private const val MAX_CUTBACKS = 4

    /** The first `{...}` or `[...]` in [raw], repaired if needed; null if there's nothing usable. */
    fun firstJson(raw: String): JsonElement? {
        val text = raw.replace("\r\n", "\n")
        val start = text.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        val scan = scan(text, start)

        // Complete value: parse just that span, ignoring anything after it.
        if (scan.end >= 0) {
            parse(text.substring(start, scan.end + 1))?.let { return it }
        }

        // Truncated (or broken): close what's open, or cut back to an earlier comma.
        val body = text.substring(start, if (scan.end >= 0) scan.end + 1 else text.length)
        val tail = scan(body, 0)
        val truncated = tail.end < 0
        val closeInPlace = { parse(close(body.trimEnd(), tail.inString, tail.stack)) }

        // Cut between elements: closing as-is keeps everything.
        if (truncated && !tail.inString) closeInPlace()?.let { return it }
        // Cut inside a string: that string is half a name or half a tag, so drop its element
        // ("Sa" must not become a person) and keep the ones before it.
        for (comma in tail.commas.asReversed().take(MAX_CUTBACKS)) {
            parse(close(body.substring(0, comma.position), inString = false, stack = comma.stack))?.let { return it }
        }
        // Nothing before it to fall back on (e.g. cut inside the first string): keep the fragment.
        if (truncated && tail.inString) closeInPlace()?.let { return it }
        return null
    }

    fun firstObject(raw: String): JsonObject? = firstJson(raw) as? JsonObject

    private fun parse(candidate: String): JsonElement? =
        runCatching { lenient.parseToJsonElement(candidate) }.getOrNull()
            ?.takeIf { it is JsonObject || it is JsonArray }

    private fun close(prefix: String, inString: Boolean, stack: String): String = buildString {
        append(prefix)
        if (inString) append('"')
        // A dangling key or separator can't be closed into valid JSON; drop it.
        var trimmed = toString().trimEnd()
        while (trimmed.endsWith(',') || trimmed.endsWith(':')) {
            trimmed = trimmed.dropLast(1).trimEnd()
        }
        setLength(0)
        append(trimmed)
        for (open in stack.reversed()) append(if (open == '{') '}' else ']')
    }

    private class Comma(val position: Int, val stack: String)

    private class Scan(
        /** Index of the bracket that closes the value at the start, or -1 if it never closes. */
        val end: Int,
        val inString: Boolean,
        /** Brackets still open at the end of the text, outermost first. */
        val stack: String,
        /** Commas outside strings, with the brackets open at each. */
        val commas: List<Comma>,
    )

    private fun scan(text: String, start: Int): Scan {
        val stack = StringBuilder()
        val commas = ArrayList<Comma>()
        var inString = false
        var escaped = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{', '[' -> stack.append(c)
                    '}', ']' -> {
                        if (stack.isNotEmpty()) stack.setLength(stack.length - 1)
                        if (stack.isEmpty()) return Scan(i, false, "", commas)
                    }
                    ',' -> commas += Comma(i, stack.toString())
                }
            }
            i++
        }
        return Scan(-1, inString, stack.toString(), commas)
    }
}

/** Lenient field access for model JSON: keys compared lowercase with only letters and digits. */
internal object JsonFields {

    fun normalizeKey(key: String): String = key.lowercase().filter { it.isLetterOrDigit() }

    fun JsonObject.field(vararg names: String): JsonElement? {
        val wanted = names.map(::normalizeKey)
        for (name in wanted) {
            for ((key, value) in this) if (normalizeKey(key) == name) return value
        }
        return null
    }

    fun JsonObject.hasAny(vararg names: String): Boolean = field(*names) != null

    /** A plain string from a string, number, the first string in an object, or a list joined by commas. */
    fun text(element: JsonElement?): String? = when (element) {
        null, JsonNull -> null
        is JsonPrimitive -> element.content.takeIf { it.isNotBlank() && it != "null" }
        is JsonObject -> element.values.firstNotNullOfOrNull { text(it) }
        is JsonArray -> element.mapNotNull { text(it) }.joinToString(", ").takeIf { it.isNotBlank() }
    }

    private val LIST_SPLIT = Regex("""[,;\n|]+""")
    private val LIST_MARKER = Regex("""^\s*(?:[-*•#]+|\d+[.)])\s*""")

    /** A list from a JSON array, or from one "a, b; c" / numbered-list string. */
    fun textList(element: JsonElement?): List<String> {
        val raw = when (element) {
            null, JsonNull -> emptyList()
            is JsonArray -> element.mapNotNull { text(it) }
            is JsonPrimitive -> element.content.split(LIST_SPLIT)
            is JsonObject -> element.values.mapNotNull { text(it) }
        }
        return raw.map { it.replace(LIST_MARKER, "").trim().trim('"', '\'', '`') }.filter { it.isNotEmpty() }
    }
}
