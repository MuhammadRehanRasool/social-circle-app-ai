package dev.braindump.splitter.domain.model

import kotlinx.serialization.Serializable

/**
 * How the user writes, distilled by Gemini Nano from their own raw dumps (never from the
 * AI-written notes, which would just teach the model its own voice). Style only: no facts.
 */
@Serializable
data class Persona(
    val traits: Map<PersonaAspect, String>,
    /** One sentence about how they write. */
    val summary: String,
    /** How many dumps it was read from. */
    val basedOn: Int,
    /** Dumps on record when it was built; decides when it's due for a refresh. */
    val dumpCount: Int,
    val updatedAt: Long,
) {
    companion object {
        /** Fewer dumps than this say more about the day than about the person. */
        const val MIN_DUMPS = 3

        /** New dumps after which the persona is rebuilt. */
        const val REFRESH_EVERY = 5

        /** Shorter dumps ("milk", a photo) say nothing about how someone writes. */
        const val MIN_DUMP_CHARS = 20
    }
}

/** The aspects Gemini Nano is asked for, in the order they're shown. */
enum class PersonaAspect { VOICE, TONE, MOOD, STRUCTURE, DETAIL, FOCUS }
