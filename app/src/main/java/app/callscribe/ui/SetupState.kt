package app.callscribe.ui

import app.callscribe.data.LabelKind
import app.callscribe.data.PolicyEntity

/** Onboarding steps, in order. Only [PERMISSIONS] cannot be skipped. See docs/plans/onboarding.md. */
enum class SetupStep(val title: String, val skippable: Boolean = true) {
    WELCOME("Welcome"),
    PERMISSIONS("Access to recordings", skippable = false),
    RECORDING("Call recording"),
    BACKGROUND("Background"),
    TRANSCRIPTION("Transcription"),
    ANALYSIS("AI summary"),
    POLICY("Automatic processing"),
    DONE("All set"),
}

/** Live facts about the device and settings. Pure — unit-tested. */
data class SetupStatus(
    val audioGranted: Boolean,
    val notificationsGranted: Boolean,
    val recordingCount: Int,
    val batteryUnrestricted: Boolean,
    val transcriberConfigured: Boolean,
    val analyzerConfigured: Boolean,
) {
    fun isDone(step: SetupStep): Boolean = when (step) {
        SetupStep.WELCOME, SetupStep.POLICY, SetupStep.DONE -> true
        SetupStep.PERMISSIONS -> audioGranted && notificationsGranted
        SetupStep.RECORDING -> recordingCount > 0
        SetupStep.BACKGROUND -> batteryUnrestricted
        SetupStep.TRANSCRIPTION -> transcriberConfigured
        SetupStep.ANALYSIS -> analyzerConfigured
    }

    /** Can the user leave this step with Next? Audio access is the one hard requirement. */
    fun canContinue(step: SetupStep): Boolean = step != SetupStep.PERMISSIONS || audioGranted

    /** Things the call-list banner nags about, in display order. */
    fun missingRequired(): List<String> = buildList {
        if (!audioGranted) add("no access to recordings")
        if (!notificationsGranted) add("notifications off")
        if (!transcriberConfigured) add("transcription not set up")
    }

    /** Where "Finish setup" should open: the first step behind a missing required item. */
    fun firstIncompleteRequired(): SetupStep? = when {
        !audioGranted || !notificationsGranted -> SetupStep.PERMISSIONS
        !transcriberConfigured -> SetupStep.TRANSCRIPTION
        else -> null
    }
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
                val kind = p.groupKind ?: return@mapNotNull null
                val want = kind != LabelKind.CONTACT
                if (p.transcribe == want) null else p.copy(transcribe = want)
            }
        }

        fun autoEnabled(choice: StartingPolicy) = choice == AUTOMATIC
    }
}
