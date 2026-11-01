package dev.sangatai.app.data.crm

import dev.sangatai.app.data.local.crm.StringSet
import dev.sangatai.app.data.local.crm.TagMap
import dev.sangatai.app.data.local.crm.TagStat
import java.util.Locale
import kotlin.math.pow

/** Pure tag rules: normalizing, merging, ranking. No Android, so it's unit-tested on the JVM. */
object TagScoring {

    private const val DAY_MS = 86_400_000.0
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

    /** Tags too vague to say anything about a person. */
    val STOP_TAGS = setOf(
        "person", "people", "friend", "human", "man", "woman", "male", "female", "guy", "girl",
        "someone", "name", "user", "mentioned", "unknown", "none", "other", "contact", "tag",
    )

    /**
     * "Car Expert!" -> "car_expert". Keeps letters of any script, so rants in Urdu or Spanish keep
     * their tags. Null for junk: stop words, over 3 words, or outside 2..32 chars.
     */
    fun normalize(raw: String): String? {
        val tag = raw.lowercase(Locale.ROOT).replace(NON_WORD, "_").trim('_')
        if (tag.length !in 2..32) return null
        if (tag.count { it == '_' } > 2) return null
        if (tag in STOP_TAGS) return null
        if (tag.all { it.isDigit() || it == '_' }) return null
        return tag
    }

    /**
     * Shallow merge: a new tag is added, an existing one gets count + 1 and its newest time.
     * [suppressed] tags (removed by the user) are skipped. Over [maxTags], the weakest AI tags are
     * evicted; user tags never are.
     */
    fun merge(
        current: TagMap,
        incoming: Collection<String>,
        at: Long,
        suppressed: StringSet = StringSet(),
        maxTags: Int = 40,
        halfLifeDays: Double = 45.0,
    ): TagMap {
        val out = LinkedHashMap(current.tags)
        for (tag in incoming) {
            if (tag in suppressed) continue
            val stat = out[tag]
            out[tag] = if (stat == null) {
                TagStat(count = 1, firstAt = at, lastAt = at)
            } else {
                stat.copy(count = stat.count + 1, firstAt = minOf(stat.firstAt, at), lastAt = maxOf(stat.lastAt, at))
            }
        }
        return TagMap(capped(out, maxTags, at, halfLifeDays))
    }

    /** Two people's tags as one (for merging duplicates): counts add up, time spans widen. */
    fun union(a: TagMap, b: TagMap): TagMap {
        val out = LinkedHashMap(a.tags)
        for ((tag, s) in b.tags) {
            val t = out[tag]
            out[tag] = if (t == null) s else TagStat(
                count = t.count + s.count,
                firstAt = minOf(t.firstAt, s.firstAt),
                lastAt = maxOf(t.lastAt, s.lastAt),
                byUser = t.byUser || s.byUser,
            )
        }
        return TagMap(out)
    }

    /**
     * Rebuilds a tag map from scratch: the user's own tags, plus what each remaining interaction
     * contributed, oldest first. Used after an interaction or a rant is deleted, so its tags go with it.
     */
    fun rebuild(
        userTags: Map<String, TagStat>,
        contributions: List<Pair<Long, Set<String>>>,
        suppressed: StringSet,
        maxTags: Int,
        halfLifeDays: Double,
        now: Long,
    ): TagMap {
        var map = TagMap(userTags.filterValues { it.byUser })
        for ((at, tags) in contributions.sortedBy { it.first }) {
            // Cap only at the end: an early cap would evict tags a later interaction reinforces.
            map = merge(map, tags, at, suppressed, maxTags = Int.MAX_VALUE, halfLifeDays = halfLifeDays)
        }
        return TagMap(capped(LinkedHashMap(map.tags), maxTags, now, halfLifeDays))
    }

    /** Count, halved every [halfLifeDays] since it was last noted: one "flaked_today" 90 days ago scores 0.25. */
    fun score(stat: TagStat, now: Long, halfLifeDays: Double = 45.0): Double {
        val ageDays = ((now - stat.lastAt) / DAY_MS).coerceAtLeast(0.0)
        val decay = if (halfLifeDays <= 0) 1.0 else 0.5.pow(ageDays / halfLifeDays)
        // User tags are facts the user stated; they don't fade.
        return if (stat.byUser) maxOf(stat.count.toDouble(), 1.0) else stat.count * decay
    }

    /** Tag names, strongest first; ties go to the most recent. */
    fun ranked(tags: TagMap, now: Long, halfLifeDays: Double = 45.0): List<String> =
        tags.tags.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, TagStat>> { score(it.value, now, halfLifeDays) }
                    .thenByDescending { it.value.lastAt }
                    .thenBy { it.key },
            )
            .map { it.key }

    /** The text that gets embedded for a person: their strongest tags as words. No name (it only adds noise). */
    fun profileText(tags: TagMap, now: Long, maxTags: Int, halfLifeDays: Double): String =
        ranked(tags, now, halfLifeDays).take(maxTags).joinToString(", ") { it.replace('_', ' ') }

    private fun capped(
        tags: LinkedHashMap<String, TagStat>,
        maxTags: Int,
        now: Long,
        halfLifeDays: Double,
    ): Map<String, TagStat> {
        if (tags.size <= maxTags) return tags
        val evictable = tags.entries.filter { !it.value.byUser }
            .sortedWith(compareBy<Map.Entry<String, TagStat>> { score(it.value, now, halfLifeDays) }.thenBy { it.value.lastAt })
        val toDrop = (tags.size - maxTags).coerceAtMost(evictable.size)
        evictable.take(toDrop).map { it.key }.forEach { tags.remove(it) }
        return tags
    }
}
