package dev.braindump.splitter.data.crm

import java.text.Normalizer
import java.util.Locale

/** How names are compared: "  Zoë  O'Brien " and "zoe obrien" are the same key. */
object NameKey {
    private val MARKS = Regex("""\p{Mn}+""")
    private val APOSTROPHES = Regex("""['’`]""")
    private val NON_NAME = Regex("""[^\p{L}\p{N}]+""")

    fun of(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFKD)
            .replace(MARKS, "")
            .replace(APOSTROPHES, "")
            .lowercase(Locale.ROOT)
            .replace(NON_NAME, " ")
            .trim()

    fun firstWord(key: String): String = key.substringBefore(' ')

    fun wordCount(key: String): Int = if (key.isEmpty()) 0 else key.count { it == ' ' } + 1

    /** "Ali" -> "Ali Khan" when the new form extends the old one; otherwise the current name stays. */
    fun longerName(current: String, mentioned: String): String {
        val a = of(current)
        val b = of(mentioned)
        return if (b.startsWith("$a ") && b.length > a.length) mentioned.trim() else current
    }

    /** A name as the user would write it: trimmed, single-spaced, at most [maxChars]. */
    fun clean(name: String, maxChars: Int = 60): String =
        name.trim().trim('"', '\'', '.', ',', ':', ';', '*', '-').replace(Regex("""\s+"""), " ").take(maxChars).trim()
}
