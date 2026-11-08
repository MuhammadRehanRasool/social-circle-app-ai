package dev.sangatai.app.domain.model

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
    /** Who they are to the user ("cousin"), or null when nobody said. */
    val relation: String?,
    /** [relation] was set by the user, not heard in a rant. */
    val relationByUser: Boolean,
    /** How many times they came up. */
    val mentionCount: Int,
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
    /** Set by the user. */
    val labels: List<String>,
    /** Heard in rants, newest first. */
    val aiLabels: List<String>,
) {
    val allLabels: List<String> get() = (labels + aiLabels).distinctBy { it.lowercase() }
}

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
    /** Who it was saved to. */
    val people: List<String>,
)

sealed interface IngestOutcome {
    data object Empty : IngestOutcome

    /** Processed: [people] were updated or added, and [links] were heard between them. */
    data class Saved(
        val captureId: Long,
        val summary: String,
        val people: List<IngestedPerson>,
        val links: List<IngestedLink>,
    ) : IngestOutcome

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

/** Someone a rant was saved to. */
data class IngestedPerson(
    val id: Long,
    val name: String,
    /** First heard in this rant. */
    val isNew: Boolean,
    val relation: String?,
)

/** A relation between two people heard in a rant. */
data class IngestedLink(val a: String, val b: String, val label: String)

/** An edge of the network graph. Each pair appears once, with [a] < [b]. */
data class GraphEdge(
    val a: Long,
    val b: Long,
    /** Rants that named both. */
    val coMentions: Int,
    /** The user's labels, then those heard in rants. */
    val labels: List<String>,
)

/** Everyone and every known tie between them. The user is implicit: linked to every person. */
data class SocialGraph(
    val people: List<Person>,
    val edges: List<GraphEdge>,
)
