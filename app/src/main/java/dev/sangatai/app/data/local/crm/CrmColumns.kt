package dev.sangatai.app.data.local.crm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One tag's history on a person, as stored in `people.dynamic_tags_json`. */
@Serializable
data class TagStat(
    /** Times the AI (or the user) asserted it. */
    @SerialName("n") val count: Int,
    @SerialName("f") val firstAt: Long,
    @SerialName("l") val lastAt: Long,
    /** Added by hand: never evicted, survives rebuilds. */
    @SerialName("u") val byUser: Boolean = false,
)

/**
 * `{"car_expert":{"n":3,"f":1730400000000,"l":1730486400000}, ...}`. Wrapped in a class so Room
 * sees one concrete type per converter.
 */
data class TagMap(val tags: Map<String, TagStat> = emptyMap())

/** A JSON string array: aliases, suppressed tags, the tags one rant contributed. */
data class StringSet(val values: Set<String> = emptySet()) {
    operator fun contains(value: String) = value in values
    operator fun plus(value: String) = StringSet(values + value)
    operator fun minus(value: String) = StringSet(values - value)
}

/** What an edge between two people means, stored in `connections.relation_json`. */
@Serializable
data class Relation(
    /** Number of rants that named both people. */
    @SerialName("co_mentions") val coMentions: Int = 0,
    @SerialName("last_at") val lastAt: Long = 0,
    /** Set by the user ("siblings", "coworkers"). Keeps the edge alive with zero co-mentions. */
    @SerialName("labels") val labels: List<String> = emptyList(),
    /**
     * What the user's own words said about the pair ("married", "coworkers"), newest first.
     * Rebuilt from `relation_facts`, so deleting the rant takes the label back.
     */
    @SerialName("ai_labels") val aiLabels: List<String> = emptyList(),
) {
    /** The user's labels first, then the ones heard in rants that they didn't already give. */
    val allLabels: List<String> get() = (labels + aiLabels).distinctBy { it.lowercase() }

    val isEmpty: Boolean get() = coMentions == 0 && labels.isEmpty() && aiLabels.isEmpty()
}
