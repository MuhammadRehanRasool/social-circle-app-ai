package dev.sangatai.app.data.crm

import dev.sangatai.app.data.local.crm.StringSet
import dev.sangatai.app.data.local.crm.TagMap
import dev.sangatai.app.data.local.crm.TagStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TagScoringTest {

    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    @Test
    fun `normalizes to snake case and rejects junk`() {
        assertEquals("car_expert", TagScoring.normalize("Car Expert!"))
        assertEquals("high_trust", TagScoring.normalize("high-trust"))
        assertNull(TagScoring.normalize("friend"))
        assertNull(TagScoring.normalize("a"))
        assertNull(TagScoring.normalize("one two three four"))
        assertNull(TagScoring.normalize("2024"))
        assertEquals("بہت_اچھا", TagScoring.normalize("بہت اچھا"))
    }

    @Test
    fun `shallow merge adds new tags and reinforces existing ones`() {
        val start = TagMap(mapOf("car_expert" to TagStat(1, now - 10 * day, now - 10 * day)))
        val merged = TagScoring.merge(start, listOf("car_expert", "chef"), now)
        assertEquals(TagStat(2, now - 10 * day, now), merged.tags["car_expert"])
        assertEquals(TagStat(1, now, now), merged.tags["chef"])
    }

    @Test
    fun `a merge never brings back a tag the user removed`() {
        val merged = TagScoring.merge(TagMap(), listOf("chef", "liar"), now, suppressed = StringSet(setOf("liar")))
        assertEquals(setOf("chef"), merged.tags.keys)
    }

    @Test
    fun `over the cap the weakest AI tags go, user tags stay`() {
        val start = TagMap(
            mapOf(
                "strong" to TagStat(5, now, now),
                "stale" to TagStat(1, now - 400 * day, now - 400 * day),
                "mine" to TagStat(1, now - 400 * day, now - 400 * day, byUser = true),
            )
        )
        val merged = TagScoring.merge(start, listOf("fresh"), now, maxTags = 2)
        assertEquals(setOf("strong", "mine"), merged.tags.keys)
    }

    @Test
    fun `score halves every half-life, user tags don't fade`() {
        assertEquals(1.0, TagScoring.score(TagStat(2, 0, now - 45 * day), now, 45.0), 1e-9)
        assertEquals(2.0, TagScoring.score(TagStat(2, 0, now), now, 45.0), 1e-9)
        assertEquals(1.0, TagScoring.score(TagStat(1, 0, now - 900 * day, byUser = true), now, 45.0), 1e-9)
        assertEquals(3.0, TagScoring.score(TagStat(3, 0, now - 900 * day), now, 0.0), 1e-9)
    }

    @Test
    fun `ranked puts strong recent tags first`() {
        val tags = TagMap(
            mapOf(
                "old_flake" to TagStat(1, 0, now - 200 * day),
                "car_expert" to TagStat(3, 0, now - day),
                "chef" to TagStat(1, 0, now),
            )
        )
        assertEquals(listOf("car_expert", "chef", "old_flake"), TagScoring.ranked(tags, now))
        assertEquals("car expert, chef", TagScoring.profileText(tags, now, maxTags = 2, halfLifeDays = 45.0))
    }

    @Test
    fun `rebuild recomputes from interactions and keeps user tags`() {
        val rebuilt = TagScoring.rebuild(
            userTags = mapOf(
                "mine" to TagStat(1, now, now, byUser = true),
                "from_deleted_rant" to TagStat(4, now, now),
            ),
            contributions = listOf((now - day) to setOf("chef", "funny"), now to setOf("chef")),
            suppressed = StringSet(setOf("funny")),
            maxTags = 40,
            halfLifeDays = 45.0,
            now = now,
        )
        assertEquals(setOf("mine", "chef"), rebuilt.tags.keys)
        assertEquals(TagStat(2, now - day, now), rebuilt.tags["chef"])
        assertFalse("from_deleted_rant" in rebuilt.tags)
    }

    @Test
    fun `union of two people adds counts and widens spans`() {
        val a = TagMap(mapOf("chef" to TagStat(2, now - 5 * day, now - day)))
        val b = TagMap(mapOf("chef" to TagStat(1, now - 9 * day, now, byUser = true), "funny" to TagStat(1, now, now)))
        val u = TagScoring.union(a, b)
        assertEquals(TagStat(3, now - 9 * day, now, byUser = true), u.tags["chef"])
        assertEquals(setOf("chef", "funny"), u.tags.keys)
    }
}
