package dev.sangatai.app.data.ai

import dev.sangatai.app.data.ai.JsonFields.field
import dev.sangatai.app.data.ai.JsonFields.hasAny
import dev.sangatai.app.data.crm.NameKey
import dev.sangatai.app.data.crm.TagScoring
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * Reads Gemini Nano's answers for the social graph. Built for drift seen on weaker builds:
 * camelCase or renamed keys, tags as one comma string, a bare list instead of the wrapper
 * object, prose or code fences around the JSON, and answers cut off by the token limit.
 * Pure functions; every bad output found on a device should become a test case.
 */
object CrmParsers {

    /** [relation] is who they are to the user ("cousin"), empty when the words didn't say. */
    data class Mention(val name: String, val tags: List<String>, val relation: String = "")

    /** Two mentioned people the words connect, by name as in [Extraction.mentioned]. */
    data class Link(val a: String, val b: String, val label: String)

    data class Extraction(
        val summary: String,
        val mentioned: List<Mention>,
        val links: List<Link> = emptyList(),
    )

    data class Match(val shortId: String, val reason: String)

    private val SUMMARY_KEYS = arrayOf("interaction_summary", "summary", "interaction", "what_happened")
    private val MENTION_KEYS = arrayOf("mentioned", "mentions", "people", "persons", "contacts", "names")
    private val NAME_KEYS = arrayOf("name", "person", "who", "full_name")
    private val TAG_KEYS = arrayOf("tags", "tag", "traits", "labels", "attributes")
    private val MATCH_KEYS = arrayOf("matches", "match", "results", "people", "candidates", "top")
    private val ID_KEYS = arrayOf("id", "person_id", "candidate", "person")
    private val REASON_KEYS = arrayOf("reason", "why", "explanation", "because")
    private val RELATION_KEYS = arrayOf("relation", "relationship", "relation_to_user", "to_user", "role", "who_to_me")
    private val LINK_KEYS = arrayOf("links", "relations", "relationships", "connections", "edges", "pairs")
    private val LINK_A_KEYS = arrayOf("a", "from", "person_a", "source", "person1", "first")
    private val LINK_B_KEYS = arrayOf("b", "to", "person_b", "target", "person2", "second")
    private val LINK_LABEL_KEYS = arrayOf("relation", "label", "relationship", "type", "how", "as")

    /** Labels that say nothing about a relationship. */
    private val EMPTY_LABELS = setOf(
        "", "unknown", "none", "n a", "na", "null", "nil", "person", "someone", "somebody", "other", "others",
        "not said", "unspecified", "not specified", "same", "related", "relation", "mentioned",
    )

    /** A name that is itself the relationship ("Mom"), so it can fill an empty relation. */
    private val KIN_NAMES = setOf(
        "mom", "mum", "mother", "ammi", "amma", "dad", "father", "abbu", "abba", "papa", "baba",
        "brother", "bhai", "sister", "behen", "wife", "husband", "son", "daughter", "grandma", "grandpa",
        "nana", "nani", "dada", "dadi", "uncle", "aunt", "chacha", "mamu", "khala", "phupho",
        "boss", "manager", "landlord", "neighbor", "neighbour", "teacher", "doctor", "roommate",
    )

    private const val MAX_LABEL_CHARS = 30
    private const val MAX_LABEL_WORDS = 3

    /** Words that mean the user, never a person to save. */
    val SELF_WORDS = setOf(
        "i", "me", "my", "myself", "mine", "user", "the user", "you", "yourself", "we", "us", "our", "self",
        "unknown", "someone", "somebody", "nobody", "none", "n a", "na", "null",
    )

    private const val MAX_SUMMARY_CHARS = 280
    private const val MAX_REASON_CHARS = 240

    /**
     * Null when nothing recognizable came back. A valid "nobody mentioned" answer is an
     * [Extraction] with an empty list, which is a success, not a failure.
     */
    fun parseExtraction(raw: String, maxTagsPerMention: Int = 6): Extraction? {
        val root = JsonRepair.firstJson(raw) ?: return null
        val summary: String
        val items: List<JsonElement>
        var linkItems: List<JsonElement> = emptyList()
        when (root) {
            is JsonArray -> {
                summary = ""
                items = root
            }
            is JsonObject -> {
                val hasMentions = root.hasAny(*MENTION_KEYS)
                val hasSummary = root.hasAny(*SUMMARY_KEYS)
                when {
                    hasMentions || hasSummary -> {
                        summary = JsonFields.text(root.field(*SUMMARY_KEYS)).orEmpty()
                        items = asList(root.field(*MENTION_KEYS))
                        linkItems = asList(root.field(*LINK_KEYS))
                    }
                    // A single person object instead of the wrapper.
                    root.hasAny(*NAME_KEYS) -> {
                        summary = ""
                        items = listOf(root)
                    }
                    else -> return null
                }
            }
            else -> return null
        }

        val byKey = LinkedHashMap<String, Mention>()
        fun add(mention: Mention) {
            val key = NameKey.of(mention.name)
            val existing = byKey[key]
            byKey[key] = if (existing == null) {
                mention
            } else {
                Mention(
                    name = NameKey.longerName(existing.name, mention.name),
                    tags = (existing.tags + mention.tags).distinct().take(maxTagsPerMention),
                    relation = existing.relation.ifEmpty { mention.relation },
                )
            }
        }
        for (item in items) readMention(item, maxTagsPerMention)?.let(::add)

        // Links: between two people, or (when one side is the user) who someone is to the user.
        val links = LinkedHashMap<String, Link>()
        for (item in linkItems) {
            val obj = item as? JsonObject ?: continue
            val label = cleanLabel(JsonFields.text(obj.field(*LINK_LABEL_KEYS)).orEmpty()) ?: continue
            val a = NameKey.clean(JsonFields.text(obj.field(*LINK_A_KEYS)).orEmpty())
            val b = NameKey.clean(JsonFields.text(obj.field(*LINK_B_KEYS)).orEmpty())
            val keyA = NameKey.of(a)
            val keyB = NameKey.of(b)
            if (keyA.isEmpty() || keyB.isEmpty() || keyA == keyB) continue
            val selfA = keyA in SELF_WORDS
            val selfB = keyB in SELF_WORDS
            when {
                selfA && selfB -> continue
                selfA || selfB -> {
                    val other = if (selfA) b else a
                    val key = NameKey.of(other)
                    val existing = byKey[key]
                    if (existing == null) add(Mention(other, emptyList(), label))
                    else if (existing.relation.isEmpty()) byKey[key] = existing.copy(relation = label)
                }
                else -> {
                    // Someone named only in a link is still someone the user mentioned.
                    if (keyA !in byKey) add(Mention(a, emptyList()))
                    if (keyB !in byKey) add(Mention(b, emptyList()))
                    val first = minOf(keyA, keyB)
                    val second = maxOf(keyA, keyB)
                    links.putIfAbsent("$first|$second|$label", Link(byKey.getValue(keyA).name, byKey.getValue(keyB).name, label))
                }
            }
        }
        return Extraction(cleanSentence(summary, MAX_SUMMARY_CHARS), byKey.values.toList(), links.values.toList())
    }

    private fun asList(element: JsonElement?): List<JsonElement> = when (element) {
        is JsonArray -> element
        null, JsonNull -> emptyList()
        else -> listOf(element)
    }

    private fun readMention(item: JsonElement, maxTags: Int): Mention? {
        val rawName: String
        val rawTags: List<String>
        var rawRelation = ""
        when (item) {
            is JsonPrimitive -> {
                rawName = item.content
                rawTags = emptyList()
            }
            is JsonObject -> {
                rawName = JsonFields.text(item.field(*NAME_KEYS)) ?: return null
                rawTags = JsonFields.textList(item.field(*TAG_KEYS))
                rawRelation = JsonFields.text(item.field(*RELATION_KEYS)).orEmpty()
            }
            else -> return null
        }
        val name = NameKey.clean(rawName)
        val key = NameKey.of(name)
        if (key.isEmpty() || key in SELF_WORDS) return null
        val tags = rawTags.mapNotNull(TagScoring::normalize).distinct().take(maxTags)
        // "Mom" with no relation given is the user's mom.
        val relation = cleanLabel(rawRelation) ?: key.takeIf { it in KIN_NAMES }.orEmpty()
        return Mention(name, tags, relation)
    }

    /**
     * "My_Cousin " -> "cousin", "father-in-law" stays. Lowercase words, at most three. Null when
     * it says nothing ("unknown", "", "n/a").
     */
    fun cleanLabel(raw: String): String? {
        val words = raw.lowercase(Locale.ROOT)
            .replace('_', ' ')
            .replace(LABEL_JUNK, " ")
            .trim()
            .split(' ')
            .filter { it.isNotBlank() }
            .dropWhile { it in LABEL_LEAD_WORDS }
        val label = words.take(MAX_LABEL_WORDS).joinToString(" ").take(MAX_LABEL_CHARS).trim()
        return label.takeUnless { it in EMPTY_LABELS || it in SELF_WORDS }
    }

    private val LABEL_JUNK = Regex("""[^\p{L}\p{N}\s-]+""")
    private val LABEL_LEAD_WORDS = setOf("my", "the", "a", "an", "is", "are", "user", "users", "user's", "his", "her", "their")

    /**
     * Matches whose id was in the shortlist ([allowedIds]), best first, at most [maxMatches].
     * An id the model invented is dropped. Null only when the answer isn't recognizable.
     */
    fun parseMatches(raw: String, allowedIds: Set<String>, maxMatches: Int = 3): List<Match>? {
        val root = JsonRepair.firstJson(raw) ?: return null
        val items: List<JsonElement> = when (root) {
            is JsonArray -> root
            is JsonObject -> when {
                root.hasAny(*MATCH_KEYS) -> when (val m = root.field(*MATCH_KEYS)) {
                    is JsonArray -> m
                    null -> emptyList()
                    else -> listOf(m)
                }
                root.hasAny(*ID_KEYS) -> listOf(root)
                else -> return null
            }
            else -> return null
        }
        val allowed = allowedIds.map(::normalizeId).toSet()
        val out = LinkedHashMap<String, Match>()
        for (item in items) {
            val (rawId, rawReason) = when (item) {
                is JsonPrimitive -> item.content to ""
                is JsonObject -> (JsonFields.text(item.field(*ID_KEYS)) ?: continue) to
                    JsonFields.text(item.field(*REASON_KEYS)).orEmpty()
                else -> continue
            }
            val id = normalizeId(rawId)
            if (id !in allowed || id in out) continue
            out[id] = Match(id, cleanSentence(rawReason, MAX_REASON_CHARS))
            if (out.size >= maxMatches) break
        }
        return out.values.toList()
    }

    /** "p3", " P 3 ", "3" and "#3" are all "P3". */
    fun normalizeId(raw: String): String {
        val compact = raw.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        return if (compact.isNotEmpty() && compact.all { it.isDigit() }) "P$compact" else compact
    }

    private fun cleanSentence(value: String, maxChars: Int): String =
        value.replace(Regex("""\s+"""), " ").trim().trim('"', '`').take(maxChars).trim()
}
