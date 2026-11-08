package dev.sangatai.app.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import java.util.Locale

/**
 * The rough circle someone belongs to, read from who they are to the user ("cousin" is family).
 * Only for color and filtering: the exact words are always shown as said.
 */
enum class RelationGroup(val label: String) {
    FAMILY("Family"),
    FRIENDS("Friends"),
    WORK("Work"),
    OTHERS("Others"),
    UNKNOWN("Not sure yet");

    companion object {
        fun of(relation: String?): RelationGroup {
            val words = relation?.lowercase(Locale.ROOT)?.split(NON_LETTER)?.filter { it.isNotEmpty() }.orEmpty()
            if (words.isEmpty()) return UNKNOWN
            // Work first, so "business partner" isn't read as a life partner.
            return when {
                words.any { it.startsWithAny(WORK_KEYS) } -> WORK
                words.any { it.startsWithAny(FAMILY_KEYS) } -> FAMILY
                words.any { it.startsWithAny(FRIENDS_KEYS) } -> FRIENDS
                else -> OTHERS
            }
        }

        private val NON_LETTER = Regex("""[^\p{L}]+""")

        private fun String.startsWithAny(keys: List<String>) = keys.any { startsWith(it) }

        private val WORK_KEYS = listOf(
            "boss", "manager", "colleague", "coworker", "co-worker", "teammate", "client", "customer",
            "business", "office", "workplace", "worker", "employee", "employer", "intern", "founder", "cofounder", "recruiter",
            "mentor", "supervisor", "lead", "senior", "junior", "vendor", "supplier", "investor", "hr", "ceo", "cto",
        )
        private val FAMILY_KEYS = listOf(
            "mother", "mom", "mum", "ammi", "amma", "father", "dad", "abbu", "abba", "papa", "baba", "parent",
            "brother", "bhai", "sister", "behen", "bahen", "sibling", "son", "daughter", "child", "kid",
            "wife", "husband", "spouse", "partner", "fianc", "girlfriend", "boyfriend", "cousin", "uncle", "aunt",
            "chacha", "chachi", "mamu", "mamo", "mami", "khala", "khalu", "phupho", "phuppo", "phuphi", "tayya",
            "taya", "nephew", "niece", "grand", "nana", "nani", "dada", "dadi", "family", "relative",
            "bhabhi", "bhabi", "jija", "saala", "sala", "saali", "devar", "nand", "married",
        )
        private val FRIENDS_KEYS = listOf(
            "friend", "dost", "yaar", "yar", "buddy", "bestie", "bff", "mate", "pal", "classmate", "roommate",
            "flatmate", "gym", "homie", "bro",
        )
    }
}

/** Fill and text colors for a [RelationGroup], from the SangatAI palette. */
@Immutable
data class GroupColors(val fill: Color, val onFill: Color)

@Composable
@ReadOnlyComposable
fun RelationGroup.colors(): GroupColors {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    return when (this) {
        RelationGroup.FAMILY -> GroupColors(scheme.tertiary, scheme.onTertiary)
        RelationGroup.FRIENDS -> GroupColors(scheme.primary, scheme.onPrimary)
        RelationGroup.WORK -> GroupColors(scheme.secondary, scheme.onSecondary)
        RelationGroup.OTHERS -> if (dark) {
            GroupColors(Color(0xFF8FC9B0), Color(0xFF00382A))
        } else {
            GroupColors(Color(0xFF3F7A64), Color.White)
        }
        RelationGroup.UNKNOWN -> GroupColors(scheme.outline, scheme.surface)
    }
}
