package dev.sangatai.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class RelationGroupTest {

    @Test
    fun `relations fall into circles`() {
        assertEquals(RelationGroup.FAMILY, RelationGroup.of("cousin"))
        assertEquals(RelationGroup.FAMILY, RelationGroup.of("Father-in-law"))
        assertEquals(RelationGroup.FAMILY, RelationGroup.of("khala"))
        assertEquals(RelationGroup.FRIENDS, RelationGroup.of("best friend"))
        assertEquals(RelationGroup.FRIENDS, RelationGroup.of("gym buddy"))
        assertEquals(RelationGroup.WORK, RelationGroup.of("boss"))
        assertEquals(RelationGroup.WORK, RelationGroup.of("business partner"))
        assertEquals(RelationGroup.OTHERS, RelationGroup.of("landlord"))
        assertEquals(RelationGroup.UNKNOWN, RelationGroup.of(null))
        assertEquals(RelationGroup.UNKNOWN, RelationGroup.of(" "))
    }
}
