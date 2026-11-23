package app.callscribe

import app.callscribe.data.CallerType
import app.callscribe.data.LabelKind
import app.callscribe.data.PolicyEntity
import app.callscribe.data.PolicyEntity.Companion.FOREVER
import app.callscribe.data.PolicyEntity.Companion.SAME_AS_LOCAL
import app.callscribe.data.PolicyMigrations
import app.callscribe.data.PolicyTarget
import app.callscribe.data.RecordingEntity
import app.callscribe.rules.ContactGroupIndex
import app.callscribe.rules.PolicyChecks
import app.callscribe.rules.PolicyResolver
import app.callscribe.rules.Resolution.Level
import app.callscribe.rules.Retention
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

private const val DAY = 86_400L
private const val NOW = 1_000 * DAY

private fun rec(id: Long, kind: LabelKind, ageDays: Long, cp: String? = "0541234567", text: String? = "t") = RecordingEntity(
    id = id, contentHash = "h$id", mediaId = id, uri = "u$id", displayName = "f$id", relativePath = "Recordings/Call/",
    sizeBytes = 1000, durationMs = 1000, dateAddedSec = 0, dateModifiedSec = 0,
    counterparty = cp, samsungLabel = null, labelKind = kind, callTimeSec = NOW - ageDays * DAY, transcriptText = text,
)

private val groups = CallerType.entries.map { PolicyEntity.defaultFor(it) }
private fun contact(name: String, number: String = "") = PolicyEntity(displayName = name, numbers = number, transcribe = true, summarize = true)
private fun tag(t: String) = PolicyEntity(tag = t, transcribe = true, summarize = true)
private fun group(g: String, priority: Int) = PolicyEntity(contactGroup = g, priority = priority, transcribe = true, summarize = true)

class ContactGroupTest {
    private val family = group("Family", 0).copy(id = 10, audioRetentionDays = 3)
    private val work = group("Work", 1).copy(id = 11, audioRetentionDays = 4)
    private val index = ContactGroupIndex.of(
        listOf(
            Triple("Dana Levi", listOf("+972 54-111-2222"), setOf("Family", "Work")),
            Triple("Boss", listOf("054-333-4444"), setOf("Work")),
            Triple("Nobody", listOf("054-999-9999"), emptySet()),
        )
    )

    @Test fun indexFindsGroupsByNameOrNumber() {
        assertEquals(setOf("Family", "Work"), index.groupsOf("dana  levi"))
        assertEquals(setOf("Work"), index.groupsOf("0543334444"))
        assertEquals(setOf("Family", "Work"), index.groupsOf("+972541112222"))
        assertEquals(emptySet<String>(), index.groupsOf("Nobody"))
        assertEquals(emptySet<String>(), index.groupsOf(null))
    }

    @Test fun groupBeatsTagAndCallerTypeContactBeatsGroup() {
        val all = groups + family + work + tag("work") + contact("Boss")
        assertEquals(Level.CONTACT_GROUP, PolicyResolver.explain(LabelKind.CONTACT, "Dana Levi", "work", all, setOf("Work")).level)
        assertEquals(Level.CONTACT, PolicyResolver.explain(LabelKind.CONTACT, "Boss", null, all, setOf("Work")).level)
        assertEquals(Level.CALLER_TYPE, PolicyResolver.explain(LabelKind.CONTACT, "X", null, all, setOf("Gym")).level)
    }

    @Test fun highestPriorityGroupWinsAndOthersAreNamed() {
        val res = PolicyResolver.explain(LabelKind.CONTACT, "Dana Levi", null, groups + work + family, setOf("Family", "Work"))
        assertEquals(family, res.policy)
        assertEquals(listOf("Work"), res.alsoIn)
        assertTrue(res.reason().contains("also in Work"))
        val swapped = PolicyResolver.explain(LabelKind.CONTACT, "Dana Levi", null,
            groups + work.copy(priority = 0) + family.copy(priority = 1), setOf("Family", "Work"))
        assertEquals("Work", swapped.policy.contactGroup)
    }

    @Test fun groupsDriveProcessingAndRetention() {
        val r = rec(1, LabelKind.CONTACT, 10, cp = "Dana Levi")
        assertTrue(PolicyResolver.forProcessing(r, groups + family, index).transcribe) // Contacts default: no
        val plan = Retention.plan(listOf(r, rec(2, LabelKind.CONTACT, 10, cp = "Stranger")), groups + family, NOW, groups = index)
        assertEquals(listOf(1L), plan.trashAudio.map { it.id })
    }
}

class CallerTypeTest {
    @Test fun spamAndFraudShareAPolicyOtherLabelsAreUnknown() {
        assertEquals(CallerType.SPAM_FRAUD, CallerType.of(LabelKind.SPAM))
        assertEquals(CallerType.SPAM_FRAUD, CallerType.of(LabelKind.FRAUD))
        assertEquals(CallerType.UNKNOWN_NUMBER, CallerType.of(LabelKind.OTHER_LABEL))
        val spam = PolicyEntity.defaultFor(CallerType.SPAM_FRAUD).copy(audioRetentionDays = 7)
        assertEquals(spam, PolicyResolver.resolve(LabelKind.FRAUD, "0541234567", groups.drop(0).filter { it.callerType != CallerType.SPAM_FRAUD } + spam))
        val unknown = PolicyResolver.explain(LabelKind.OTHER_LABEL, "0541234567", null, groups)
        assertEquals(CallerType.UNKNOWN_NUMBER, unknown.policy.callerType)
    }
}

class PrecedenceTest {
    private val mom = contact("Mom").copy(audioRetentionDays = 1)
    private val work = tag("work").copy(audioRetentionDays = 2)
    private val all = groups + mom + work

    @Test fun targetIsDerivedFromTheSetField() {
        assertEquals(PolicyTarget.CALLER_TYPE, groups[0].target)
        assertEquals(PolicyTarget.TAG, work.target)
        assertEquals(PolicyTarget.CONTACT, mom.target)
    }

    @Test fun contactBeatsTagBeatsCallerTypeBeatsDefault() {
        assertEquals(Level.CONTACT, PolicyResolver.explain(LabelKind.CONTACT, "Mom", "work", all).level)
        assertEquals(Level.TAG, PolicyResolver.explain(LabelKind.CONTACT, "Dan", "work", all).level)
        assertEquals(Level.CALLER_TYPE, PolicyResolver.explain(LabelKind.CONTACT, "Dan", "family_friends", all).level)
        assertEquals(Level.CALLER_TYPE, PolicyResolver.explain(LabelKind.SPAM, null, null, all).level)
        assertEquals(Level.DEFAULT, PolicyResolver.explain(LabelKind.SPAM, null, null, listOf(mom)).level)
    }

    @Test fun winnerAppliesAsAWholeNoFieldMerging() {
        // The tag policy only changed retention; transcribe/summarize come from it too, not from the caller type.
        val workNoAi = work.copy(transcribe = false, summarize = false)
        val p = PolicyResolver.resolve(LabelKind.UNKNOWN_NUMBER, "0541234567", groups + workNoAi, tag = "work")
        assertEquals(workNoAi, p)
    }

    @Test fun processingIgnoresTagsBecauseTheyAreUnknownBeforeAnalysis() {
        val r = rec(1, LabelKind.CONTACT, 0, cp = "Dan")
        val p = PolicyResolver.forProcessing(r, groups + work.copy(transcribe = true), ContactGroupIndex.EMPTY)
        assertEquals(CallerType.CONTACT, p.callerType)
    }

    @Test fun reasonNamesTheWinner() {
        assertTrue(PolicyResolver.explain(LabelKind.CONTACT, "Mom", "work", all).reason().contains("Mom"))
        assertTrue(PolicyResolver.explain(LabelKind.CONTACT, "Dan", "work", all).reason().contains("Work"))
    }
}

class RetentionV3Test {
    @Test fun keepForeverOverridesEverything() {
        val spam = PolicyEntity.defaultFor(CallerType.SPAM_FRAUD).copy(audioRetentionDays = 1, textRetentionDays = 1, cloudRetentionDays = 1)
        val rows = listOf(rec(1, LabelKind.SPAM, 30), rec(2, LabelKind.SPAM, 30))
        val plan = Retention.plan(rows, listOf(spam), NOW, keptForever = { it.id == 1L })
        assertEquals(listOf(2L), plan.trashAudio.map { it.id })
        assertEquals(listOf(2L), plan.deleteText.map { it.id })
        assertEquals(listOf(2L), plan.cloudAudio.map { it.id })
        assertEquals(listOf(2L), plan.cloudText.map { it.id })
    }

    @Test fun tagPolicyDrivesRetention() {
        val unknown = PolicyEntity.defaultFor(CallerType.UNKNOWN_NUMBER) // forever
        val work = tag("work").copy(audioRetentionDays = 30)
        val rows = listOf(rec(1, LabelKind.UNKNOWN_NUMBER, 60), rec(2, LabelKind.UNKNOWN_NUMBER, 60))
        val plan = Retention.plan(rows, listOf(unknown, work), NOW, tagOf = { if (it.id == 1L) "work" else null })
        assertEquals(listOf(1L), plan.trashAudio.map { it.id })
    }

    @Test fun cloudSameAsLocalFollowsPhoneAudioAndText() {
        val p = PolicyEntity.defaultFor(CallerType.SPAM_FRAUD).copy(audioRetentionDays = 7, textRetentionDays = 30)
        // "Same as local" follows what the phone actually deleted (after the user confirmed the trash), not what is due.
        val rows = listOf(
            rec(1, LabelKind.SPAM, 10).copy(audioDeletedAt = 1),
            rec(2, LabelKind.SPAM, 40).copy(audioDeletedAt = 1, textDeletedAt = 1),
            rec(3, LabelKind.SPAM, 40), // due locally, not yet deleted: cloud copy stays
        )
        val plan = Retention.plan(rows, listOf(p), NOW)
        assertEquals(listOf(1L, 2L), plan.cloudAudio.map { it.id })
        assertEquals(listOf(2L), plan.cloudText.map { it.id })
    }

    @Test fun cloudSameAsLocalWithLocalForeverKeepsForever() {
        val plan = Retention.plan(listOf(rec(1, LabelKind.SPAM, 9999)), groups, NOW)
        assertTrue(plan.cloudAudio.isEmpty() && plan.cloudText.isEmpty())
    }

    @Test fun cloudIndependentOfPhone() {
        // Phone 7 days, cloud forever: audio leaves the phone, stays in the cloud.
        val keepInCloud = PolicyEntity.defaultFor(CallerType.SPAM_FRAUD).copy(audioRetentionDays = 7, cloudRetentionDays = FOREVER)
        val plan1 = Retention.plan(listOf(rec(1, LabelKind.SPAM, 10)), listOf(keepInCloud), NOW)
        assertEquals(1, plan1.trashAudio.size)
        assertTrue(plan1.cloudAudio.isEmpty())
        // Phone forever, cloud 30 days: both cloud copies expire, nothing local.
        val shortCloud = PolicyEntity.defaultFor(CallerType.SPAM_FRAUD).copy(cloudRetentionDays = 30)
        val plan2 = Retention.plan(listOf(rec(1, LabelKind.SPAM, 40)), listOf(shortCloud), NOW)
        assertTrue(plan2.isEmpty)
        assertEquals(1, plan2.cloudAudio.size)
        assertEquals(1, plan2.cloudText.size)
    }
}

class PolicyChecksTest {
    private val base = PolicyEntity.defaultFor(CallerType.SPAM_FRAUD)

    @Test fun defaultsAreQuiet() {
        groups.forEach { assertEquals(emptyList<String>(), PolicyChecks.warnings(it)) }
    }

    @Test fun warnsOnOddCombos() {
        assertEquals(1, PolicyChecks.warnings(base.copy(transcribe = false, summarize = false, audioRetentionDays = 7)).size)
        assertEquals(1, PolicyChecks.warnings(base.copy(audioRetentionDays = 30, textRetentionDays = 7)).size)
        assertEquals(1, PolicyChecks.warnings(base.copy(audioRetentionDays = 90, cloudRetentionDays = 30)).size)
        assertEquals(1, PolicyChecks.warnings(base.copy(cloudRetentionDays = 30)).size)
    }

    @Test fun sameAsLocalAndLongerCloudAreFine() {
        assertEquals(emptyList<String>(), PolicyChecks.warnings(base.copy(audioRetentionDays = 7, cloudRetentionDays = SAME_AS_LOCAL)))
        assertEquals(emptyList<String>(), PolicyChecks.warnings(base.copy(audioRetentionDays = 7, cloudRetentionDays = 365)))
    }
}

/** Runs the real v4→v6 policy SQL on SQLite against the exported v2 policies table and compares with the exported v6 schema. */
class MigrationTest {
    private val schemas = File("schemas/app.callscribe.data.AppDatabase")

    private fun policiesCreateSql(version: Int): String {
        val db = JSONObject(File(schemas, "$version.json").readText()).getJSONObject("database")
        val entities = db.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            if (e.getString("tableName") == "policies") return e.getString("createSql").replace("\${TABLE_NAME}", "policies")
        }
        error("no policies table in v$version")
    }

    private fun columns(c: Connection): List<String> = c.createStatement().executeQuery("PRAGMA table_info(policies)").use { rs ->
        buildList { while (rs.next()) add("${rs.getString("name")} ${rs.getString("type")} ${rs.getInt("notnull")} ${rs.getString("dflt_value")}") }
    }

    private fun migrate(vararg inserts: String): List<String> {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            val st = c.createStatement()
            st.execute(policiesCreateSql(2))
            inserts.forEach {
                st.execute(
                    "INSERT INTO policies (groupKind, displayName, numbers, transcribe, summarize, audioRetentionDays, textRetentionDays) VALUES $it"
                )
            }
            PolicyMigrations.SQL_4_5.forEach { st.execute(it) }
            PolicyMigrations.SQL_5_6.forEach { st.execute(it) }
            DriverManager.getConnection("jdbc:sqlite::memory:").use { fresh ->
                fresh.createStatement().execute(policiesCreateSql(6))
                assertEquals(columns(fresh), columns(c))
            }
            return st.executeQuery(
                "SELECT groupKind, displayName, numbers, transcribe, summarize, audioRetentionDays, textRetentionDays, tag, " +
                    "cloudRetentionDays, contactGroup, priority FROM policies ORDER BY id"
            ).use { rs -> buildList { while (rs.next()) add((1..11).joinToString("|") { rs.getString(it) ?: "null" }) } }
        }
    }

    @Test fun existingPoliciesSurviveSpamFraudMergeKeepingMore() {
        val rows = migrate(
            "('CONTACT', '', '', 0, 0, -1, -1)",
            "('UNKNOWN_NUMBER', '', '', 1, 1, 30, -1)",
            "('PRIVATE', '', '', 1, 0, 90, 7)",
            "('SPAM', '', '', 1, 0, 7, 30)",
            "('FRAUD', '', '', 1, 1, 30, -1)",
            "('OTHER_LABEL', '', '', 0, 0, 1, 1)",
            "(NULL, 'Mom', '541234567', 1, 0, 90, -1)",
        )
        assertEquals(
            listOf(
                "CONTACT|||0|0|-1|-1|null|-2|null|0",
                "UNKNOWN_NUMBER|||1|1|30|-1|null|-2|null|0",
                "PRIVATE|||1|0|90|7|null|-2|null|0",
                // spam 7d/text 30d + fraud 30d/text forever, summarize on → keep-more of each field
                "SPAM_FRAUD|||1|1|30|-1|null|-2|null|0",
                "null|Mom|541234567|1|0|90|-1|null|-2|null|0",
            ),
            rows,
        )
    }

    @Test fun fraudAloneBecomesSpamFraud() {
        assertEquals(listOf("SPAM_FRAUD|||1|1|30|-1|null|-2|null|0"), migrate("('FRAUD', '', '', 1, 1, 30, -1)"))
    }

    @Test fun spamAloneIsUnchanged() {
        assertEquals(listOf("SPAM_FRAUD|||1|0|7|30|null|-2|null|0"), migrate("('SPAM', '', '', 1, 0, 7, 30)"))
    }

    @Test fun everyMigratedCallerTypeIsAKnownEnumValue() {
        val rows = migrate(*LabelKind.entries.map { "('${it.name}', '', '', 1, 1, -1, -1)" }.toTypedArray())
        val names = CallerType.entries.map { it.name }.toSet()
        rows.forEach { assertTrue(it, it.substringBefore('|') in names) }
    }
}
