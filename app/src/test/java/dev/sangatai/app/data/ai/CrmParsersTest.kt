package dev.sangatai.app.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Outputs shaped like what weaker Gemini Nano builds actually return. Add every new failure here. */
class CrmParsersTest {

    private fun extract(raw: String) = CrmParsers.parseExtraction(raw, maxTagsPerMention = 6)

    @Test
    fun `reads the documented shape`() {
        val x = extract(
            """{"interaction_summary":"Bilal skipped the gym again.","mentioned":[{"name":"Bilal","tags":["flaked_today","Gym Buddy"]}]}"""
        )!!
        assertEquals("Bilal skipped the gym again.", x.summary)
        assertEquals(listOf("Bilal"), x.mentioned.map { it.name })
        assertEquals(listOf("flaked_today", "gym_buddy"), x.mentioned[0].tags)
    }

    @Test
    fun `nobody mentioned is a valid answer`() {
        val x = extract("""{"interaction_summary":"A long day at work.","mentioned":[]}""")
        assertNotNull(x)
        assertTrue(x!!.mentioned.isEmpty())
        assertEquals("A long day at work.", x.summary)
    }

    @Test
    fun `camelCase keys and tags as one string`() {
        val x = extract("""{"interactionSummary":"x","Mentioned":[{"Name":"Sara Khan","Tags":"car expert, high-trust"}]}""")!!
        assertEquals("Sara Khan", x.mentioned.single().name)
        assertEquals(listOf("car_expert", "high_trust"), x.mentioned.single().tags)
    }

    @Test
    fun `code fence and prose around the JSON`() {
        val raw = "Sure! Here you go:\n```json\n{\"interaction_summary\":\"Lunch with Ali.\",\"mentioned\":[{\"name\":\"Ali\",\"tags\":[\"funny\"]}]}\n```\nHope that helps."
        val x = extract(raw)!!
        assertEquals("Ali", x.mentioned.single().name)
    }

    @Test
    fun `the user is never saved as a person`() {
        val x = extract("""{"interaction_summary":"x","mentioned":[{"name":"me","tags":["tired"]},{"name":"Mom","tags":["supportive"]},{"name":"N/A","tags":[]}]}""")!!
        assertEquals(listOf("Mom"), x.mentioned.map { it.name })
    }

    @Test
    fun `bare list instead of the wrapper object`() {
        val x = extract("""[{"name":"Ali","tags":["chef"]},"Sara"]""")!!
        assertEquals(listOf("Ali", "Sara"), x.mentioned.map { it.name })
        assertEquals("", x.summary)
    }

    @Test
    fun `same person twice is merged`() {
        val x = extract("""{"summary":"x","people":[{"name":"Ali","tags":["chef"]},{"name":"ali","tags":["funny","chef"]}]}""")!!
        assertEquals(1, x.mentioned.size)
        assertEquals(listOf("chef", "funny"), x.mentioned.single().tags)
    }

    @Test
    fun `junk tags are dropped and the count is capped`() {
        val x = CrmParsers.parseExtraction(
            """{"summary":"x","mentioned":[{"name":"Ali","tags":["friend","person","a","chef","funny","one two three four","driver"]}]}""",
            maxTagsPerMention = 2,
        )!!
        assertEquals(listOf("chef", "funny"), x.mentioned.single().tags)
    }

    @Test
    fun `cut off inside a tag keeps what came before`() {
        val x = extract("""{"interaction_summary":"Met Ali","mentioned":[{"name":"Ali","tags":["car_expert","high_tr""")!!
        assertEquals("Ali", x.mentioned.single().name)
        assertEquals(listOf("car_expert"), x.mentioned.single().tags)
    }

    @Test
    fun `cut off inside a name never creates a half-named person`() {
        val x = extract("""{"interaction_summary":"x","mentioned":[{"name":"Ali","tags":["chef"]},{"name":"Sa""")!!
        assertEquals(listOf("Ali"), x.mentioned.map { it.name })
    }

    @Test
    fun `cut off between elements closes cleanly`() {
        val x = extract("""{"interaction_summary":"x","mentioned":[{"name":"Ali","tags":["chef"]}""")!!
        assertEquals(listOf("chef"), x.mentioned.single().tags)
    }

    @Test
    fun `prose with no JSON is not an answer`() {
        assertNull(extract("I'm sorry, I can't help with that."))
        assertNull(extract("""{"answer":"no"}"""))
    }

    @Test
    fun `matches keep only shortlisted ids`() {
        val m = CrmParsers.parseMatches(
            """{"matches":[{"id":"P2","reason":"Tagged car_expert."},{"id":"P9","reason":"invented"}]}""",
            allowedIds = setOf("P1", "P2", "P3"),
        )!!
        assertEquals(listOf("P2"), m.map { it.shortId })
        assertEquals("Tagged car_expert.", m.single().reason)
    }

    @Test
    fun `numeric and lowercase ids are understood`() {
        val m = CrmParsers.parseMatches(
            """{"matches":[{"id":3,"reason":"a"},{"id":"p1","why":"b"}]}""",
            allowedIds = setOf("P1", "P3"),
        )!!
        assertEquals(listOf("P3", "P1"), m.map { it.shortId })
        assertEquals("b", m[1].reason)
    }

    @Test
    fun `empty matches is a valid answer, prose is not`() {
        assertEquals(emptyList<CrmParsers.Match>(), CrmParsers.parseMatches("""{"matches":[]}""", setOf("P1")))
        assertNull(CrmParsers.parseMatches("Nobody fits.", setOf("P1")))
    }

    @Test
    fun `matches are capped and de-duplicated`() {
        val m = CrmParsers.parseMatches(
            """[{"id":"P1"},{"id":"P1"},{"id":"P2"},{"id":"P3"}]""",
            allowedIds = setOf("P1", "P2", "P3"),
            maxMatches = 2,
        )!!
        assertEquals(listOf("P1", "P2"), m.map { it.shortId })
    }

    // ---------------------------------------------------------------- relations and links

    @Test
    fun `reads relations and links from the documented shape`() {
        val x = extract(
            """{"interaction_summary":"x","mentioned":[{"name":"Bilal","relation":"cousin","tags":["gym_buddy"]},""" +
                """{"name":"Sara","relation":"","tags":["car_expert"]},{"name":"Omar","relation":"colleague","tags":[]}],""" +
                """"links":[{"a":"Sara","b":"Omar","relation":"married"}]}"""
        )!!
        assertEquals(listOf("cousin", "", "colleague"), x.mentioned.map { it.relation })
        assertEquals(listOf(CrmParsers.Link("Sara", "Omar", "married")), x.links)
    }

    @Test
    fun `a link to the user is who that person is to the user`() {
        val x = extract(
            """{"summary":"x","mentioned":[{"name":"Hina","tags":[]}],"links":[{"from":"me","to":"Hina","type":"best friend"}]}"""
        )!!
        assertEquals("best friend", x.mentioned.single().relation)
        assertTrue(x.links.isEmpty())
    }

    @Test
    fun `someone named only in a link is still saved`() {
        val x = extract(
            """{"summary":"x","mentioned":[{"name":"Ali","tags":["chef"]}],"links":[{"a":"Ali","b":"Zara","relation":"siblings"}]}"""
        )!!
        assertEquals(listOf("Ali", "Zara"), x.mentioned.map { it.name })
        assertEquals("siblings", x.links.single().label)
    }

    @Test
    fun `links without a relation or to the same person are dropped`() {
        val x = extract(
            """{"summary":"x","mentioned":[{"name":"Ali"},{"name":"Sara"}],""" +
                """"links":[{"a":"Ali","b":"Sara"},{"a":"Ali","b":"ali","relation":"friends"},{"a":"Ali","b":"Sara","relation":"unknown"}]}"""
        )!!
        assertTrue(x.links.isEmpty())
    }

    @Test
    fun `the same link twice is kept once`() {
        val x = extract(
            """{"summary":"x","mentioned":[{"name":"Ali"},{"name":"Sara"}],""" +
                """"links":[{"a":"Ali","b":"Sara","relation":"married"},{"a":"Sara","b":"Ali","relation":"married"}]}"""
        )!!
        assertEquals(1, x.links.size)
    }

    @Test
    fun `a name that is a relationship fills an empty relation`() {
        val x = extract("""{"summary":"x","mentioned":[{"name":"Mom","tags":["supportive"]},{"name":"Ali","relation":""}]}""")!!
        assertEquals(listOf("mom", ""), x.mentioned.map { it.relation })
    }

    @Test
    fun `relation labels are cleaned`() {
        assertEquals("cousin", CrmParsers.cleanLabel("My_Cousin "))
        assertEquals("father-in-law", CrmParsers.cleanLabel("father-in-law"))
        assertEquals("best friend", CrmParsers.cleanLabel("a Best Friend!"))
        assertNull(CrmParsers.cleanLabel("unknown"))
        assertNull(CrmParsers.cleanLabel(""))
        assertNull(CrmParsers.cleanLabel("N/A"))
    }
}
