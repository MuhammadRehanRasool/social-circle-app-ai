package dev.sangatai.app.data.ai

import dev.sangatai.app.data.ai.JsonFields.field
import dev.sangatai.app.data.ai.JsonFields.hasAny
import dev.sangatai.app.data.crm.NameKey
import dev.sangatai.app.data.crm.TagScoring
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

    data class Mention(val name: String, val tags: List<String>)

    data class Extraction(val summary: String, val mentioned: List<Mention>)

    data class Match(val shortId: String, val reason: String)

    private val SUMMARY_KEYS = arrayOf("interaction_summary", "summary", "interaction", "what_happened")
    private val MENTION_KEYS = arrayOf("mentioned", "mentions", "people", "persons", "contacts", "names")
    private val NAME_KEYS = arrayOf("name", "person", "who", "full_name")
    private val TAG_KEYS = arrayOf("tags", "tag", "traits", "labels", "attributes")
    private val MATCH_KEYS = arrayOf("matches", "match", "results", "people", "candidates", "top")
    private val ID_KEYS = arrayOf("id", "person_id", "candidate", "person")
    private val REASON_KEYS = arrayOf("reason", "why", "explanation", "because")

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
                        items = when (val m = root.field(*MENTION_KEYS)) {
                            is JsonArray -> m
                            null -> emptyList()
                            else -> listOf(m)
                        }
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
        for (item in items) {
            val mention = readMention(item, maxTagsPerMention) ?: continue
            val key = NameKey.of(mention.name)
            val existing = byKey[key]
            byKey[key] = if (existing == null) {
                mention
            } else {
                Mention(
                    name = NameKey.longerName(existing.name, mention.name),
                    tags = (existing.tags + mention.tags).distinct().take(maxTagsPerMention),
                )
            }
        }
        return Extraction(cleanSentence(summary, MAX_SUMMARY_CHARS), byKey.values.toList())
    }

    private fun readMention(item: JsonElement, maxTags: Int): Mention? {
        val (rawName, rawTags) = when (item) {
            is JsonPrimitive -> item.content to emptyList<String>()
            is JsonObject -> (JsonFields.text(item.field(*NAME_KEYS)) ?: return null) to JsonFields.textList(item.field(*TAG_KEYS))
            else -> return null
        }
        val name = NameKey.clean(rawName)
        val key = NameKey.of(name)
        if (key.isEmpty() || key in SELF_WORDS) return null
        val tags = rawTags.mapNotNull(TagScoring::normalize).distinct().take(maxTags)
        return Mention(name, tags)
    }

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
