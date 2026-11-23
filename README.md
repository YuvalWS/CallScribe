# CallScribe

A free, open-source **call transcription manager** for Samsung phones. It lists the call recordings
your phone already saves (including calls answered by Samsung's call screening), transcribes them in
Hebrew with [ivrit.ai](https://huggingface.co/ivrit-ai) models, and can ask an AI model (Gemini,
any OpenRouter model, or Claude) for a short summary and a spam / not-spam verdict.

**Bring your own services.** CallScribe has no servers. Audio goes only to the RunPod endpoint you
deploy, and transcripts only to the AI provider account you choose. You pay those providers directly
(typically a fraction of a cent per call). Nothing is hard-coded and nothing is sent anywhere until
you configure it.

## Features
- Call list of every recording in `Recordings/Call`, with Samsung's caller-ID label (Suspected Spam,
  Potential Fraud, …), filters, and per-call + total cost.
- **Search** by name, number (any format) or words in transcripts and summaries.
- **Tags** from a short closed list — Family & friends, Work, Business & suppliers, Services & official, Other —
  set by the AI and correctable on the call page (your choice is never overwritten). Filter and search by tag
  (English or Hebrew, e.g. "משפחה").
- **Transcribe** button on every row and on the call page; **Analyze** for the AI summary.
- **Policies** per caller type (spam, fraud, other labels, unknown, private, contacts) and per contact:
  transcribe automatically? summarize? how long to keep the recording? A contact's policy overrides its group.
- **Retention**: recordings past their period are moved to the system trash (recoverable 30 days) after you
  confirm, or silently if you grant *Media management* access. Transcripts are kept by default; tick
  *Also delete transcript & summary* to expire text too (runs daily).
- Long calls are compressed on the phone (16 kHz mono AAC) before upload.
- **On-device transcription** (optional, free, offline): download ivrit.ai's Hebrew Zipformer (72 MB) in
  Settings → Providers → *On this phone*. Runs with sherpa-onnx; ~25 s per minute of audio on a Galaxy S20 FE.
  Automatic jobs can wait for the charger. Details: [docs/plans/on-device-transcription.md](docs/plans/on-device-transcription.md).
- Recordings are identified by content hash, so renames / restores keep their transcripts.
- Optional webhook export (e.g. to Home Assistant).
- Optional **Google Drive backup** to your own Drive: transcripts, summaries, policies, settings and (opt-in)
  recordings; incremental, daily, Wi-Fi/charging options; restore on a new phone re-links calls to audio by content hash.
- API keys are encrypted with the Android Keystore and excluded from backups.

## Requirements
- Android 13+ Samsung phone with call recording enabled
  (Phone → ⋮ → Settings → Record calls → Auto record calls).
- A [RunPod](https://runpod.io) account (for transcription) and optionally an
  [Anthropic API key](https://console.anthropic.com) (for summaries).

## Setup

### 1. Transcription endpoint (RunPod, ~5 minutes)
1. In RunPod → Serverless → **New Endpoint**, deploy the hub template
   **ivrit-ai/runpod-serverless** (or image `yairlifshitz/whisper-runpod-serverless`).
2. Settings: **Min workers 0** (you pay nothing while idle), max workers 1–2, idle timeout ~5 s,
   container disk ≥ 20 GB, any 16–24 GB GPU.
3. Copy the **Endpoint ID** and create an **API key** (Settings → API Keys).
4. In CallScribe → Settings → Providers, paste both and tap **Test connection** (free).
5. Set the *GPU price per second* to the price of the GPU you chose (used only for cost estimates).

Measured on a real endpoint: a 14-minute call took 14 s of GPU time ≈ **$0.003**; cold starts add
~10–20 s of waiting.

### 2. AI summary (optional) — pick one provider in Settings → Providers
- **Gemini** (aistudio.google.com key). Free tier: ~15 requests/minute, spaced automatically. Default model
  `gemini-3.5-flash-lite`. Note: on the free tier Google may use prompts (your transcripts) to improve its products.
- **OpenRouter** (openrouter.ai key, pay per request; exact cost recorded per call). Default
  `anthropic/claude-haiku-5.5` ≈ $0.0003 per call (measured).
- **Claude** (console.anthropic.com key).

"Test connection" is free for every provider.

### 3. Google Drive backup (optional)
Backups go to a visible **CallScribe Backup** folder in *your* Drive (scope `drive.file`: the app can only see files it
created). API keys are never backed up. There is no CallScribe server: the app talks to Google Drive directly.

Because there is no shared CallScribe server or Google project, **whoever builds the APK registers it with Google once**
(free, ~10 minutes). Nothing is pasted into the app — Google recognises it by package name + signing certificate.
1. [console.cloud.google.com](https://console.cloud.google.com) → create a project (e.g. "CallScribe").
2. **APIs & Services → Library → Google Drive API → Enable.**
3. **Google Auth Platform → Branding / OAuth consent screen**: User type **External**, app name "CallScribe",
   your e-mail as support and developer contact. **Data access → Add scope** `.../auth/drive.file`
   (non-sensitive: no Google verification needed).
4. **Audience**: either **Publish app** (recommended — in *Testing* mode Google expires access after 7 days) or keep
   *Testing* and add your Google account under *Test users*.
5. Get the **SHA-1** of the key that signs the APK you install:
   - Local builds (debug key, also used for release builds unless you configured a release keystore):
     ```powershell
     keytool -list -v -keystore $env:USERPROFILE\.android\debug.keystore -alias androiddebugkey -storepass android
     ```
   - Release keystore (e.g. from CI): `keytool -list -v -keystore <your.keystore> -alias <alias>`.
6. **Clients → Create client → Android**: package name `app.callscribe`, the SHA-1 from step 5.
   Builds signed with a different key (debug vs release) need **one Android client each** in the same project.
7. On the phone: CallScribe → Settings → **Google Drive backup** → turn it on, pick your account, allow access.
   Optionally turn on *Include recordings* (~1 MB per minute of audio). "Back up now" runs immediately.

If turning it on says *"Google rejected this app build"*, the package name or SHA-1 of the installed APK doesn't
match an Android client (step 5–6). How long backed-up recordings are kept is set per policy (cloud retention); expired
ones are moved to the Drive trash. **Restore** (same screen) brings calls, transcripts, policies and settings to a new
phone and can download recordings that aren't on it.

### 4. Background reliability (Samsung)
Settings → Battery → Background usage limits → **Never sleeping apps** → add CallScribe.

## Building
Needs JDK 17+ and the Android SDK (platform 37). No Android Studio required.
The first build downloads the prebuilt sherpa-onnx AAR (50 MB, SHA-256 pinned) from GitHub releases into the
Gradle user home; no NDK is needed. The APK contains arm64-v8a native code only.

```bash
./gradlew :app:testDebugUnitTest      # unit tests
./gradlew :app:assembleRelease        # app/build/outputs/apk/release/app-release.apk (~32 MB)
adb install -r app/build/outputs/apk/release/app-release.apk
```

Debug builds include adb hooks for development (see `app/src/debug`).

To sign a local release build with the release key, set `CALLSCRIBE_KEYSTORE_FILE`,
`CALLSCRIBE_KEYSTORE_PASSWORD`, `CALLSCRIBE_KEY_ALIAS` and `CALLSCRIBE_KEY_PASSWORD`; without them the
release APK is signed with your debug key.

## CI/CD (GitHub Actions)
`.github/workflows/ci.yml` (design and cost details: [docs/plans/cicd.md](docs/plans/cicd.md)):

| Event | What runs | Output |
|---|---|---|
| PR into `dev` or `main`, push to `dev` | unit tests + `assembleDebug` | debug APK as a workflow artifact (kept 3 days) |
| push / merge to `main` | unit tests + signed R8 `assembleRelease` | GitHub Release `v0.1.<run>` with the APK and notes from the commits |

Docs-only changes (`*.md`, `docs/**`) don't trigger a run. Versions are `versionCode = <run number>`,
`versionName = <callscribe.version in gradle.properties>.<run number>`, so every release installs as an
update over the previous one.

**Cost:** the repo is public, so GitHub Actions minutes and artifact storage are free and unlimited
on the free plan. (A private copy would get ≈ 2,000 Linux minutes a month — roughly 150 PR runs plus
30 releases — see the plan doc.)

### Branches
`feature/…` → PR into **`dev`** (integration; CI builds a debug APK) → PR `dev` → **`main`** (every
merge is a release). Don't commit straight to `main`.

### Release signing setup (once)
The repo ([YuvalWS/CallScribe](https://github.com/YuvalWS/CallScribe)) and its `main` / `dev` branches
exist. Run these from the repo root in PowerShell with the [GitHub CLI](https://cli.github.com)
(`gh auth login` first).

Create the release key once (`keytool` ships with any JDK; if it isn't on your PATH, call it by its full path, e.g. `& "<JDK folder>\bin\keytool.exe"`), **outside the repo**, and back it up with its password (lose it and you
can never ship an update to existing installs):

```powershell
keytool -genkeypair -v -keystore "$HOME\callscribe-release.jks" -alias callscribe -keyalg RSA -keysize 4096 -validity 10000
```

Add it as repository secrets (`gh secret set NAME` with no `--body` prompts for the value):

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$HOME\callscribe-release.jks")) | gh secret set CALLSCRIBE_KEYSTORE_BASE64
gh secret set CALLSCRIBE_KEYSTORE_PASSWORD
gh secret set CALLSCRIBE_KEY_ALIAS --body callscribe
gh secret set CALLSCRIBE_KEY_PASSWORD
```

(keytool's default PKCS12 keystore uses the same password for store and key.) Without these secrets
releases are still published, signed with a throw-away debug key and marked with a warning.

**Switching to the release key:** an install signed with your PC's debug key can't be updated by a
release-key APK. Uninstall CallScribe once (this deletes its local data), then install from the Releases
page; later releases update in place.

## Privacy
Recordings stay on the phone unless a call is processed. Then the audio goes to *your* RunPod
endpoint and the transcript to *your* AI provider. With Drive backup on, data (and recordings, if chosen) go to
*your* Google Drive. No analytics, no crash reporting.
Recording calls you take part in is legal in Israel; check the law where you live.

## License
CallScribe is free software, licensed under the [Apache License 2.0](LICENSE). Copyright 2026 Yuval Weiss.
The libraries it uses and the models you can download keep their own licenses; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
