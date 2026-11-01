package dev.braindump.splitter.domain.model

/** One tag on a person: how often it was noted and when. */
data class PersonTag(
    val tag: String,
    val count: Int,
    val firstAt: Long,
    val lastAt: Long,
    /** Added by the user by hand. Never evicted, and never removed by a rebuild. */
    val byUser: Boolean,
    /** Strength used for ranking: count, fading with time since [lastAt]. */
    val score: Double,
)

data class Person(
    val id: Long,
    val name: String,
    val aliases: Set<String>,
    /** Strongest first. */
    val tags: List<PersonTag>,
    /** Tags the user removed; the AI won't add them back. */
    val suppressedTags: Set<String>,
    val lastInteractionAt: Long,
    val createdAt: Long,
    val hasEmbedding: Boolean,
    val createdByUser: Boolean,
)

data class Interaction(
    val id: Long,
    val personId: Long,
    val captureId: Long?,
    val timestamp: Long,
    val rawText: String,
    val summary: String?,
    val tagsAdded: Set<String>,
)

/** An edge as seen from one person. */
data class Connection(
    val edgeId: Long,
    val otherId: Long,
    val otherName: String,
    val coMentions: Int,
    val lastAt: Long,
    val labels: List<String>,
)

data class PersonDetail(
    val person: Person,
    val interactions: List<Interaction>,
    val connections: List<Connection>,
)

enum class CaptureStatus { PENDING, DONE, FAILED }

/** One rant as the user said it, and what became of it. */
data class Capture(
    val id: Long,
    val rawText: String,
    val status: CaptureStatus,
    val attempts: Int,
    val error: String?,
    val summary: String?,
    val mentionCount: Int,
    val createdAt: Long,
)

sealed interface IngestOutcome {
    data object Empty : IngestOutcome

    /** Processed: [mentionCount] people were updated or added. */
    data class Saved(val captureId: Long, val summary: String, val mentionCount: Int) : IngestOutcome

    /** Kept, waiting for Gemini Nano (app backgrounded, model downloading...). */
    data class Queued(val captureId: Long, val reason: String?) : IngestOutcome

    data class Failed(val captureId: Long, val reason: String?) : IngestOutcome
}

data class PersonMatch(
    val personId: Long,
    val name: String,
    val reason: String,
    /** Cosine similarity of the query to this person's tags, before reranking. */
    val similarity: Float,
    /** False when Gemini Nano wasn't available and the reason was built from the matching tags. */
    val fromAi: Boolean,
)

sealed interface AskOutcome {
    data object Empty : AskOutcome

    /** Nobody in the network has tags close enough to the question. */
    data object NoOne : AskOutcome

    /** Semantic search itself couldn't run (embedding model missing or broken). */
    data class Unavailable(val reason: String) : AskOutcome

    /**
     * [shortlist] is what retrieval handed to Gemini Nano, kept for inspection.
     * [degraded] says why the AI step was skipped, when it was.
     */
    data class Matches(
        val matches: List<PersonMatch>,
        val shortlist: List<PersonMatch>,
        val degraded: String? = null,
    ) : AskOutcome
}
