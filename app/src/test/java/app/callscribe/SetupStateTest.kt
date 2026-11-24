package app.callscribe

import app.callscribe.data.CallerType
import app.callscribe.data.PolicyEntity
import app.callscribe.ui.SetupStatus
import app.callscribe.ui.SetupStep
import app.callscribe.ui.StartingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupStateTest {
    private val all = SetupStatus(
        audioGranted = true, notificationsGranted = true, recordingCount = 3,
        batteryUnrestricted = true, transcriberConfigured = true, analyzerConfigured = true, driveBackupOn = true,
    )

    @Test fun everythingDone() {
        SetupStep.entries.forEach { assertTrue(it.name, all.isDone(it)) }
        assertTrue(all.missingRequired().isEmpty())
        assertNull(all.firstIncompleteRequired())
    }

    @Test fun onlyPermissionsIsMandatory() {
        assertEquals(listOf(SetupStep.PERMISSIONS), SetupStep.entries.filter { !it.skippable })
        val noAudio = all.copy(audioGranted = false)
        assertFalse(noAudio.canContinue(SetupStep.PERMISSIONS))
        assertTrue(noAudio.canContinue(SetupStep.TRANSCRIPTION))
        // Notifications are optional: you may continue, but the step isn't "done".
        val noNotif = all.copy(notificationsGranted = false)
        assertTrue(noNotif.canContinue(SetupStep.PERMISSIONS))
        assertFalse(noNotif.isDone(SetupStep.PERMISSIONS))
    }

    @Test fun stepCompletion() {
        assertFalse(all.copy(recordingCount = 0).isDone(SetupStep.RECORDING))
        assertFalse(all.copy(batteryUnrestricted = false).isDone(SetupStep.BACKGROUND))
        assertFalse(all.copy(transcriberConfigured = false).isDone(SetupStep.TRANSCRIPTION))
        assertFalse(all.copy(analyzerConfigured = false).isDone(SetupStep.ANALYSIS))
    }

    @Test fun bannerListsOnlyRequiredItems() {
        val s = all.copy(notificationsGranted = false, transcriberConfigured = false, analyzerConfigured = false, batteryUnrestricted = false)
        assertEquals(listOf("notifications off", "transcription not set up"), s.missingRequired())
        assertEquals(SetupStep.PERMISSIONS, s.firstIncompleteRequired())
        assertEquals(SetupStep.TRANSCRIPTION, all.copy(transcriberConfigured = false).firstIncompleteRequired())
        // Optional items never trigger the banner.
        assertTrue(all.copy(analyzerConfigured = false, batteryUnrestricted = false, recordingCount = 0).missingRequired().isEmpty())
    }

    private fun groups(transcribe: Boolean) = CallerType.entries.mapIndexed { i, k ->
        PolicyEntity(id = i + 1L, callerType = k, transcribe = transcribe, summarize = true)
    }

    @Test fun automaticPolicyTurnsOnNonContactGroups() {
        val contact = PolicyEntity(id = 99, displayName = "Mom", numbers = "541234567", transcribe = false, summarize = false)
        val updates = StartingPolicy.groupUpdates(StartingPolicy.AUTOMATIC, groups(false) + contact)
        assertEquals(CallerType.entries.filter { it != CallerType.CONTACT }.toSet(), updates.map { it.callerType }.toSet())
        assertTrue(updates.all { it.transcribe && it.summarize })
        assertTrue(StartingPolicy.autoEnabled(StartingPolicy.AUTOMATIC))
    }

    @Test fun automaticPolicyTurnsOffContactsGroup() {
        val updates = StartingPolicy.groupUpdates(StartingPolicy.AUTOMATIC, groups(true))
        assertEquals(1, updates.size)
        assertEquals(CallerType.CONTACT, updates[0].callerType)
        assertFalse(updates[0].transcribe)
    }

    @Test fun manualPolicyOnlyFlipsMasterSwitch() {
        assertTrue(StartingPolicy.groupUpdates(StartingPolicy.MANUAL, groups(true)).isEmpty())
        assertFalse(StartingPolicy.autoEnabled(StartingPolicy.MANUAL))
        assertEquals(StartingPolicy.MANUAL, StartingPolicy.current(false))
        assertEquals(StartingPolicy.AUTOMATIC, StartingPolicy.current(true))
    }

    @Test fun existingCallsStepComesAfterNewCallPolicyAndIsOptional() {
        val order = SetupStep.entries
        assertTrue(order.indexOf(SetupStep.EXISTING) == order.indexOf(SetupStep.POLICY) + 1)
        assertTrue(SetupStep.EXISTING.skippable)
    }
}
