# CallScribe — agent instructions

Kotlin / Jetpack Compose Android app (minSdk 33). Read `README.md` and `PLAN.md` first.
Principles: bring-your-own services, nothing hard-coded, privacy first, keep the UI simple.

## Build & test (Windows, PowerShell, no Android Studio)
- JDK: `$env:JAVA_HOME='C:\Users\yuval\Android\jdk21'`; SDK: `C:\Users\yuval\Android\Sdk`.
- A fresh checkout/worktree needs the gitignored `local.properties`: `sdk.dir=C\:\\Users\\yuval\\Android\\Sdk`
- Test + build: `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug`
- Install (updates in place, keeps data): `C:\Users\yuval\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk`
- Debug adb hooks: see `app/src/debug/.../DebugReceiver.kt` (e.g. `DEBUG_ONBOARDING` re-shows first-run setup).
- Provider keys live in the gitignored `.env` — never commit, print or type them into the app.
- The test phone holds real recordings: never uninstall the app, clear its data or delete recordings.

## Rule: keep onboarding current
Any change that **adds or changes a runtime permission, a provider, a setting the user must configure, or a policy
default** MUST, in the same change, update the first-run onboarding:
- `SetupStep` / `SetupStatus` / `StartingPolicy` in `app/src/main/java/app/callscribe/ui/SetupState.kt`
  (the ordered step registry — add a step as one enum entry);
- `SetupStep.ui()` and the step composables in `ui/Onboarding.kt`;
- the shared provider composables `TranscriptionProviderFields` / `AnalyzerProviderFields` in `ui/ProvidersScreen.kt`;
- `app/src/test/java/app/callscribe/SetupStateTest.kt` and `docs/plans/onboarding.md`.

The final report MUST say what was updated in onboarding, or why nothing was needed.
