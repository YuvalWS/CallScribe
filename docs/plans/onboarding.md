# First-run onboarding — plan

Goal: a short, friendly setup flow on first launch that covers permissions, recording, background
reliability, providers and a starting policy. Every step can be skipped except the audio permission
step (the app is useless without it). Replaces the bare `PermissionGate` in `ui/MainActivity.kt`.

## Steps (registry: enum `SetupStep` in `ui/SetupState.kt`, UI in `SetupStep.ui()` in `ui/Onboarding.kt`)

The step order is the declaration order of `SetupStep`; each entry carries its heading, whether it can be skipped
and when it counts as done. Its icon and body live in one exhaustive `when` (`SetupStep.ui()`), so adding a step
is: one enum entry + one `when` branch (the compiler flags the missing branch) + a row in the table below.

| # | Step | Skippable | "Done" when (live, re-checked on resume) |
|---|------|-----------|-------------------------------------------|
| 1 | **Welcome** — what the app does + privacy promise: no servers, bring your own services, nothing uploaded until you set up a provider | yes (just "Start") | always |
| 2 | **Permissions** — audio files (`READ_MEDIA_AUDIO`) + notifications (`POST_NOTIFICATIONS`). Explains why; one button asks for both. If the system won't ask again, an "Open app settings" button appears | **no** for audio (Next disabled until granted); notifications are optional | audio granted (notifications tracked separately) |
| 3 | **Call recording** — "Phone → ⋮ → Settings → Record calls → Auto record calls", button opens the dialer's call settings (`TelecomManager.ACTION_SHOW_CALL_SETTINGS`), shows "N recordings found" (DB count after a scan; Rescan button) | yes | N > 0 |
| 4 | **Background (Samsung)** — Battery → Background usage limits → Never sleeping apps; buttons open app info (Battery → Unrestricted) and the battery-optimization list | yes | `PowerManager.isIgnoringBatteryOptimizations` |
| 5 | **Transcription** — the same RunPod fields + Test connection as the Providers screen (shared composable `TranscriptionProviderFields`) | yes | transcriber `isConfigured()` |
| 6 | **AI summary** — Gemini / OpenRouter / Claude radio + that provider's fields (shared composable `AnalyzerProviderFields`) | yes | analyzer `isConfigured()` |
| 7 | **Starting policy** — two cards: "Automatically: unknown, private & spam callers" vs "Only when I tap Transcribe" | yes | always (preselected from current state) |
| 8 | **Done** — checklist summary of the above, "Open my calls" | — | — |

Bottom bar on every step: Back · Skip (if skippable) · Next. A thin progress bar + "Step n of 8" on top.

### Starting policy mapping (kept thin — policies are being redesigned elsewhere)
Pure function `StartingPolicy.apply(choice, policies)` (unit-tested):
- **Automatic**: `autoEnabled = true`; group policies SPAM, FRAUD, OTHER_LABEL, UNKNOWN_NUMBER, PRIVATE → `transcribe = true`;
  CONTACT → `transcribe = false`. Contact policies untouched.
- **Manual only**: `autoEnabled = false`; group policies untouched.
- Only written when the user picks an option; skipping changes nothing. Preselection: `autoEnabled` false → Manual, else Automatic.

## State & persistence (`data/Settings.kt`)
- `onboardingDone: Boolean` — set when the user reaches/finishes the Done step (or taps "Skip setup" on Welcome).
  Default for **existing installs**: `true` if DataStore already holds saved settings (so upgrading users are not
  forced through it), `false` on a fresh install.
- `setupBannerDismissed: Boolean` — the user closed the call-list banner. Reset by "Run setup again".
- Step completion is **computed live**, not stored: pure `SetupStatus(audio, notifications, recordings,
  battery, transcriber, analyzer)` → `isDone(step)`, `missingRequired()`, `firstIncomplete()` (unit-tested).
  Required for the banner: audio permission, notifications, transcription provider.

## Entry points / re-running
- `MainActivity`: if `!onboardingDone` **or** audio permission is missing → onboarding (audio missing after setup
  opens directly at step 2). Otherwise the normal `NavHost`.
- Settings → **Run setup again** → `setup` route (starts at Welcome, finishing pops back).
- Call list: small banner "Setup incomplete: notifications off · transcription not set up" with **Finish setup**
  (opens the flow at the first incomplete required step) and ✕ (dismiss). Shown only when `onboardingDone` and
  something required is missing.
- Debug builds: `adb shell am broadcast -n app.callscribe/.debug.DebugReceiver -a app.callscribe.DEBUG_ONBOARDING`
  sets `onboardingDone = false` (forces the flow without clearing data).

## Keeping onboarding current (rule for every change)
Any change that **adds or changes a runtime permission, a provider, a setting the user must configure, or a policy
default** must, in the same change:
- update the flow — `SetupStep` (+ `SetupStatus` if it needs a new fact) in `ui/SetupState.kt`, `SetupStep.ui()` and
  the step composables in `ui/Onboarding.kt`, the shared provider composables `TranscriptionProviderFields` /
  `AnalyzerProviderFields` in `ui/ProvidersScreen.kt`, and `StartingPolicy` for policy defaults;
- update `SetupStateTest` and this document;
- say in the final report what changed in onboarding, or why nothing was needed.

## Out of scope
Localised (Hebrew) strings — the app UI is English today; the layout uses start/end so it mirrors correctly in RTL.
