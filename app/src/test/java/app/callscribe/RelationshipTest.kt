package app.callscribe

import app.callscribe.data.AppDatabase
import app.callscribe.data.LabelKind
import app.callscribe.data.RecordingEntity
import app.callscribe.data.Relationship
import app.callscribe.providers.AnalysisPrompt
import app.callscribe.ui.CallSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationshipTest {
    private fun rec(tag: Relationship?) = RecordingEntity(
        contentHash = "h", mediaId = 1, uri = "u", displayName = "Call recording x_250101_120000.m4a",
        relativePath = "Recordings/Call/", sizeBytes = 1, durationMs = 1000, dateAddedSec = 0, dateModifiedSec = 0,
        counterparty = "Somebody", samsungLabel = null, labelKind = LabelKind.CONTACT, callTimeSec = 0,
        relationship = tag,
    )

    private val base = """{"summary_he":"ס","verdict":"not_spam","spam_confidence":0.1,"category":"personal","caller_intent":"x","action_items":[]"""

    @Test fun fromKeyKnownAndUnknown() {
        assertEquals(Relationship.FAMILY_FRIENDS, Relationship.fromKey("family_friends"))
        assertEquals(Relationship.WORK, Relationship.fromKey(" WORK "))
        assertEquals(Relationship.SERVICES, Relationship.fromKey("SERVICES"))
        assertEquals(Relationship.OTHER, Relationship.fromKey("spam"))
        assertEquals(Relationship.OTHER, Relationship.fromKey(""))
        assertEquals(Relationship.OTHER, Relationship.fromKey(null))
    }

    @Test fun parseNormalizesRelationship() {
        assertEquals("business", AnalysisPrompt.parse("$base,\"relationship\":\"business\"}").relationship)
        assertEquals("other", AnalysisPrompt.parse("$base,\"relationship\":\"enemies\"}").relationship)
        assertEquals("other", AnalysisPrompt.parse("$base}").relationship)
    }

    @Test fun relationshipFromStoredJson() {
        assertEquals(Relationship.WORK, AnalysisPrompt.relationshipFrom("$base,\"relationship\":\"work\"}"))
        assertNull(AnalysisPrompt.relationshipFrom("$base}")) // analysed before tags existed
        assertNull(AnalysisPrompt.relationshipFrom("{\"refusal\":true}"))
        assertNull(AnalysisPrompt.relationshipFrom(null))
    }

    @Test fun schemaHasClosedEnum() {
        @Suppress("UNCHECKED_CAST")
        val prop = AnalysisPrompt.PROPERTIES["relationship"] as Map<String, Any>
        assertEquals(listOf("family_friends", "work", "business", "services", "other"), prop["enum"])
        assertTrue("relationship" in (AnalysisPrompt.schemaMap()["required"] as List<*>))
        assertTrue(AnalysisPrompt.SYSTEM.contains("family_friends"))
    }

    @Test fun searchMatchesTagNames() {
        val fam = rec(Relationship.FAMILY_FRIENDS)
        assertTrue(CallSearch.matches(fam, "משפחה"))
        assertTrue(CallSearch.matches(fam, "Family"))
        assertTrue(CallSearch.matches(fam, "friends"))
        assertFalse(CallSearch.matches(fam, "עבודה"))
        assertTrue(CallSearch.matches(rec(Relationship.WORK), "colleagues"))
        assertTrue(CallSearch.matches(rec(Relationship.WORK), "עבודה"))
        assertTrue(CallSearch.matches(rec(Relationship.BUSINESS), "ספקים"))
        assertTrue(CallSearch.matches(rec(Relationship.SERVICES), "בנק"))
        assertFalse(CallSearch.matches(rec(null), "משפחה"))
        assertFalse(CallSearch.matches(rec(Relationship.OTHER), "q")) // 1-char queries don't match tags
    }

    @Test fun migration2to3AddsNullableTagAndManualFlag() {
        val sql = AppDatabase.MIGRATION_3_4_SQL
        assertEquals(3, AppDatabase.MIGRATION_3_4.startVersion)
        assertEquals(4, AppDatabase.MIGRATION_3_4.endVersion)
        assertTrue(sql.any { it == "ALTER TABLE recordings ADD COLUMN relationship TEXT" })
        assertTrue(sql.any { it.contains("relationshipManual INTEGER NOT NULL DEFAULT 0") })
    }
}
