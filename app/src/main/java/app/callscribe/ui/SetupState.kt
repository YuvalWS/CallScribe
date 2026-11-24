package app.callscribe.ui

import app.callscribe.data.CallerType
import app.callscribe.data.PolicyEntity

/**
 * The onboarding flow, as one ordered registry: steps run in declaration order. To add a step, add an entry here
 * (heading, skippable, when it counts as done) and its icon + body in `SetupStep.ui()` in Onboarding.kt — the
 * compiler flags that `when` until you do. Keep docs/plans/onboarding.md in sync. Only [PERMISSIONS] is mandatory.
 */
enum class SetupStep(val heading: String, val skippable: Boolean = true, val isDone: (SetupStatus) -> Boolean = { true }) {
    WELCOME("Welcome to CallScribe"),
    PERMISSIONS("Let CallScribe see your call recordings", skippable = false, isDone = { it.audioGranted && it.notificationsGranted }),
    RECORDING("Is call recording on?", isDone = { it.recordingCount > 0 }),
    BACKGROUND("Keep CallScribe awake", isDone = { it.batteryUnrestricted }),
    TRANSCRIPTION("Set up transcription", isDone = { it.transcriberConfigured }),
    ANALYSIS("AI summary & spam check", isDone = { it.analyzerConfigured }),
    POLICY("What should happen to new calls?"),
    EXISTING("Process the calls you already have?"),
    BACKUP("Back up to Google Drive?", isDone = { it.driveBackupOn }),
    DONE("You're all set"),
}

/** Live facts about the device and settings. Pure — unit-tested. */
data class SetupStatus(
    val audioGranted: Boolean,
    val notificationsGranted: Boolean,
    val recordingCount: Int,
    val batteryUnrestricted: Boolean,
    val transcriberConfigured: Boolean,
    val analyzerConfigured: Boolean,
    /** Optional: Google Drive backup is on. */
    val driveBackupOn: Boolean = false,
) {
    fun isDone(step: SetupStep): Boolean = step.isDone(this)

    /** Can the user leave this step with Next? Audio access is the one hard requirement. */
    fun canContinue(step: SetupStep): Boolean = step != SetupStep.PERMISSIONS || audioGranted

    /** Things the call-list banner nags about, in display order. */
    fun missingRequired(): List<String> = buildList {
        if (!audioGranted) add("no access to recordings")
        if (!notificationsGranted) add("notifications off")
        if (!transcriberConfigured) add("transcription not set up")
    }

    /**
     * Optional steps that were skipped but still matter, shown as recommendations at the top of Settings until done.
     * Pure — unit-tested.
     */
    fun recommendations(): List<Recommendation> = buildList {
        if (!batteryUnrestricted) add(Recommendation.BATTERY)
        if (!notificationsGranted) add(Recommendation.NOTIFICATIONS)
    }

    /** Where "Finish setup" should open: the first step behind a missing required item. */
    fun firstIncompleteRequired(): SetupStep? = when {
        !audioGranted || !notificationsGranted -> SetupStep.PERMISSIONS
        !transcriberConfigured -> SetupStep.TRANSCRIPTION
        else -> null
    }
}

/** A skipped-but-useful setup item. */
enum class Recommendation(val title: String, val detail: String) {
    BATTERY(
        "Let CallScribe run in the background",
        "Samsung's battery saver can delay automatic transcription. Set Battery to Unrestricted in the app's info.",
    ),
    NOTIFICATIONS("Turn on notifications", "Get told when a call is transcribed and whether it looked like spam."),
}

/** The two simple starting choices offered during onboarding, mapped onto the existing policies. */
enum class StartingPolicy(val title: String, val detail: String) {
    AUTOMATIC(
        "Process unknown & spam callers automatically",
        "New calls from unknown, private and spam / fraud-labelled numbers are transcribed and summarized as they arrive. Calls with your contacts are left alone until you tap Transcribe.",
    ),
    MANUAL(
        "Only when I tap Transcribe",
        "Nothing is sent anywhere automatically. You choose which calls to transcribe.",
    ),
    ;

    companion object {
        fun current(autoEnabled: Boolean) = if (autoEnabled) AUTOMATIC else MANUAL

        /** Group policies to update for [choice] (contact policies are never touched). Pure — unit-tested. */
        fun groupUpdates(choice: StartingPolicy, policies: List<PolicyEntity>): List<PolicyEntity> = when (choice) {
            MANUAL -> emptyList()
            AUTOMATIC -> policies.mapNotNull { p ->
                val kind = p.callerType ?: return@mapNotNull null
                val want = kind != CallerType.CONTACT
                if (p.transcribe == want) null else p.copy(transcribe = want)
            }
        }

        fun autoEnabled(choice: StartingPolicy) = choice == AUTOMATIC
    }
}
