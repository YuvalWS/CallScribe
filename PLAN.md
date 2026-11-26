# CallScribe-HE — Implementation Plan

A free, open-source **call transcription manager** for Android (Samsung, One UI 8.5/9). It lists every
**call recording** (including screened calls), lets the user **transcribe any call with one tap**, and can
**process calls automatically** according to user rules (all / by Samsung label / unknown callers /
contact include & exclude lists). Transcription goes to a **speech-to-text provider** (first provider:
ivrit.ai models on the user's RunPod endpoint) and analysis to an **LLM provider** (first provider: Claude),
which returns a summary and a spam / not-spam verdict. Results appear as a notification and in the call list.

"Free" = the app is free; the user brings their own provider keys and pays those providers directly.

**Design rule for the POC: providers sit behind interfaces, but only one implementation of each is built**
(see §1.1). No generic "configure any HTTP endpoint" engine.

This plan is written so an implementing agent (e.g. Claude Sonnet) can build it phase by phase.
Each phase ends with acceptance criteria. **Do not start a phase before the previous one passes.**

---

## 0. Key facts and constraints (read first)

### 0.1 What a third-party app can and cannot do
| Capability | Possible? | How |
|---|---|---|
| Record call audio itself | **No** (needs `CAPTURE_AUDIO_OUTPUT`, a system-only permission) | Not used. We rely on Samsung's own recorder. |
| Read Samsung's saved call recordings | **Yes** | `READ_MEDIA_AUDIO` (Android 13+) + `MediaStore.Audio` query. Files live under `Recordings/Call/` on internal storage. |
| Be woken when a new recording appears, even if app is closed | **Yes** | WorkManager job with a **content-URI trigger** on `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI`, plus a 15-min periodic sweep as fallback. No foreground service needed. |
| Know caller number/name | **Partly** | Samsung puts the contact name or number in the filename (e.g. `Call recording John Doe_251110_143012.m4a`). Optional: `READ_CALL_LOG` to match by timestamp (Phase 4, optional). |
| Upload to the internet | **Yes** | `INTERNET` (normal permission, no prompt). |

**Conclusion: no special/system permissions are needed.** One runtime prompt (`READ_MEDIA_AUDIO`),
plus `POST_NOTIFICATIONS`. The app is sideloaded (no Play Store), so Play's call-recording / call-log
policies do not apply.

### 0.2 Phase 0 findings — screened calls ARE saved ✅ (confirmed by the user on device, 2026-11-10)
- A screened call **produced an audio file** in the call recordings folder on internal storage
  (`Recordings/Call…` — confirm the exact `RELATIVE_PATH` spelling, e.g. `Recordings/Call/` vs `Recordings/Calls/`,
  by logging the MediaStore row on first run; the scanner's default prefix `Recordings/Call` matches both via `LIKE 'Recordings/Call%'`).
- The filename carries **Samsung's caller-ID label**, e.g. `Call_Suspected_Spam…`. Samsung's built-in spam filter
  has more labels (e.g. "Potential fraud"), and for contacts / plain numbers the name/number appears instead.
- So the full flow works for screened calls *and* ordinary recorded calls.

Remaining Phase 0 task for the implementer (with the user): collect **5–10 real filenames** covering
contact, unknown number, private/hidden number, "Suspected spam", "Potential fraud" (and any other label seen),
and save them in `docs/phase0-findings.md` + as unit-test fixtures for the filename parser (§2.3).
Also note codec/extension, file size per minute, and **channel count** (copy one file to a PC and run
`ffprobe -hide_banner file.m4a`): if recordings are **stereo with each side on its own channel**, speaker
separation (§5) becomes trivial and exact; if mono, it needs diarization.

### 0.3 Existing solutions (checked 2026-11-10)
- **Tiro** (Android) — watches Galaxy call recordings and auto-transcribes new ones with a persistent
  notification. Proves the approach works on Samsung. Closed, paid credits, Hebrew quality / LLM
  spam-verdict not documented.
- **Scrib** (F-Droid) — on-device Whisper, manual file pick, no auto-watch, no ivrit.ai models.
- **Samsung "Transcript assist"** — built-in transcription + summary of recorded calls, but no Hebrew-quality
  ivrit.ai model and no spam verdict.
- **ivrit.ai** — publishes models + a RunPod serverless template; **no hosted public API** — you deploy
  your own RunPod endpoint (cheap, pay per second).
- No existing app does *auto-watch → ivrit.ai → LLM spam verdict*. Building it is justified.

---

## 1. Architecture

```
Samsung Phone app  ──saves──▶  /sdcard/Recordings/Call/*.m4a
                                   │ (MediaStore insert)
                                   ▼
             WorkManager content-URI trigger  +  periodic 15-min sweep
                                   ▼
                  ScanWorker: sync MediaStore → Room; RuleEngine: auto or skip
                                   ▼   (insert row into Room, status=NEW)
                  ProcessWorker (one per recording, unique work name = recording row id)
                     ├─ Phase 1: Forwarder (webhook / Home Assistant / file copy) 
                     ├─ Phase 2: Transcriber → RunPod ivrit.ai endpoint
                     └─ Phase 3: CallAnalyzer → Claude API (summary + spam verdict, JSON)
                                   ▼
                  Room DB update  +  notification ("📞 053-xxx: SPAM 92% — 'הלוואה מהירה…'")
                                   ▼
                  Compose UI: list of calls → detail (transcript RTL, summary, verdict, play audio)
```

### 1.1 Provider interfaces (pluggable, one implementation each in the POC)
```kotlin
interface Transcriber {
  val id: String                       // "ivrit-runpod"
  val displayName: String
  val maxUploadBytes: Long
  suspend fun submit(audio: AudioSource, opts: TranscribeOptions): JobHandle      // may be sync or async
  suspend fun poll(handle: JobHandle): TranscriptionState                         // Pending | Done(Transcript) | Failed(msg, retryable)
}
data class Transcript(val text: String, val segments: List<Segment>, val language: String, val rawJson: String)
data class Segment(val startSec: Double, val endSec: Double, val text: String, val speaker: String? = null)

interface CallAnalyzer {
  val id: String                       // "claude"
  suspend fun analyze(transcript: Transcript, meta: CallMeta): Analysis           // summary, verdict, confidence, category, intent, action items
}
```
- `ProviderRegistry` maps ids → implementations; Settings stores the selected id and that provider's
  own config (endpoint, key, model) under keys prefixed with the id. The settings screen for each
  provider is a small Composable supplied by the provider (`@Composable fun SettingsSection()`).
- The pipeline, DB and UI only see `Transcript` / `Analysis` — never provider JSON (raw JSON is stored only for debugging).
- POC implementations: `IvritRunPodTranscriber` (§3), `ClaudeAnalyzer` (§4). Phase 1's webhook forwarder stays
  a separate optional "export" step, not a transcriber.
- **Later, cheap additions** (not in the POC, listed so the interfaces fit them):
  `OpenAiCompatibleWhisperTranscriber` (one impl covers many self-hosted servers exposing
  `POST /v1/audio/transcriptions`, e.g. faster-whisper servers running an ivrit.ai model on your own PC),
  `OnDeviceWhisperTranscriber` (whisper.cpp), and other LLM analyzers. Adding one = one class + registry entry.

### 1.1a Bring your own services (hard requirement)
The app is meant to be shared freely; **the developer never pays for other users' processing**.
- **Nothing is hard-coded**: no default endpoint IDs, URLs, API keys or accounts in the source, build config,
  or CI. A fresh install cannot send audio anywhere until the user configures a provider.
- **First-run setup** (after permissions): "Set up transcription" → pick a provider → fill its fields
  (RunPod endpoint ID + API key) → **Test connection** (sends a bundled 3-second Hebrew sample clip and shows the text).
  Same for "Set up AI analysis" (Anthropic API key + model → Test with a fixture transcript). Both steps can be
  skipped; the app then works as a recordings browser, and the Transcribe button opens the setup screen.
- Each provider's settings section has a **"How to get this"** link to the README section with step-by-step setup
  (RunPod template deploy; Anthropic Console API key) and a rough cost per call-minute.
- Keys are stored encrypted on the device only (Keystore-backed), excluded from Android backup
  (`android:dataExtractionRules` / `fullBackupContent` exclude the secrets file), never logged, never shown in full after saving.
- Optional convenience: **export/import provider settings** as a JSON file or QR code *without* keys by default
  (checkbox "include keys" with a warning) — lets a user move to a new phone or set up a family member's phone
  with their *own* endpoint.
- No analytics, no crash reporting to the developer, no network calls except to the user's configured providers.
- Because users will differ in what they can host, the `OpenAiCompatibleWhisperTranscriber` (§1.1, later) is the
  most valuable second provider: it lets a user run an ivrit.ai model on their own PC/home server for free.

### 1.2 Product features (POC scope)
- **Call list** = recordings found in MediaStore (not the system call log). Each row: counterparty, Samsung
  label chip, date, duration, status (Not transcribed / Queued / Transcribing / Done / Failed / Skipped by rule).
  Filters: All · Spam/fraud · Unknown · Contacts · Not transcribed.
- **Detail screen**: audio player, **Transcribe** button (or **Re-transcribe**), **Analyze** button (enabled once a
  transcript exists), transcript (RTL), summary + verdict.
- **Manual transcribe** enqueues the same `ProcessWorker` with `force = true` (ignores rules).
- **Auto-process rules** (Settings → Automatic processing), evaluated in `RuleEngine.decide(parsedName): Decision`
  (pure function, unit-tested):
  - Master mode: **Off (manual only)** / **All calls** / **Custom rules**.
  - Custom rules (checkboxes): Samsung-labelled spam · Samsung-labelled fraud · other Samsung labels ·
    unknown numbers · private numbers · contacts.
  - **Contact lists**: *Always include* and *Never include* (exclude wins over everything, including "All calls").
    Contacts are picked with the system contact picker (`ActivityResultContracts.PickContact`) — this grants
    one-off access to the chosen contact, so **no `READ_CONTACTS` permission is needed**. Store display name +
    phone numbers; match against the counterparty parsed from the filename (normalize: trim, case-fold,
    collapse `_`/spaces; for numbers strip non-digits and compare the last 9 digits so `+972…`/`05…` match).
  - Separate toggle: "Run AI analysis automatically after transcription" (on by default).
- A call skipped by rules stays in the list as **Skipped by rule** and can still be transcribed manually.
- Existing recordings at first install appear in the list as *Not transcribed* (the watermark only controls
  what is auto-processed, not what is listed).

### Tech stack
- Kotlin, minSdk 33 (Android 13), targetSdk 35/36, Jetpack Compose + Material 3.
- WorkManager (`androidx.work:work-runtime-ktx`), Room, DataStore (prefs), OkHttp 4, kotlinx.serialization.
- Secrets (RunPod key, Anthropic key) stored with `androidx.security:security-crypto`
  `EncryptedSharedPreferences` (or DataStore + Android Keystore). Entered by the user in Settings; never hard-coded.
- Claude: official Java SDK `com.anthropic:anthropic-java:3.3.0` (works from Kotlin). If it proves too heavy
  or fails on Android (Jackson/OkHttp conflicts), fall back is **not** allowed to an OpenAI shim; instead
  call `POST https://api.anthropic.com/v1/messages` with OkHttp and say why in the README.

### Package layout
```
app/src/main/java/.../callscribe/
  CallScribeApp.kt            // Application: schedules workers on start
  data/
    RecordingEntity.kt        // Room entity
    RecordingDao.kt
    AppDatabase.kt
    SettingsRepository.kt     // DataStore + encrypted secrets
  detect/
    RecordingScanner.kt       // MediaStore query logic (pure, unit-testable)
    ScanWorker.kt             // triggered by content URI + periodic
    WorkScheduler.kt          // enqueue/re-enqueue helpers
    BootReceiver.kt           // re-arm content trigger after reboot / app update
  rules/
    RuleEngine.kt             // decide(parsedName, settings): AUTO | SKIP (pure, unit-tested)
    ContactListRepository.kt  // include / exclude lists (Room table)
  providers/
    Transcriber.kt, CallAnalyzer.kt, ProviderRegistry.kt   // interfaces + registry (§1.1)
    ivrit/IvritRunPodTranscriber.kt                         // Phase 2
    claude/ClaudeAnalyzer.kt                                // Phase 3
  pipeline/
    ProcessWorker.kt          // per recording: [export] → transcribe → analyze, driven by input flags
    Forwarder.kt              // Phase 1 (optional export)
    Notifier.kt
  ui/
    MainActivity.kt, CallListScreen.kt, CallDetailScreen.kt, PermissionGate.kt
    settings/ SettingsScreen.kt, AutoProcessScreen.kt, ContactListsScreen.kt, ProvidersScreen.kt
```

### Data model (Room)
```kotlin
@Entity(tableName = "recordings", indices = [Index(value = ["contentHash"], unique = true), Index("mediaId")])
data class RecordingEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val contentHash: String,        // SHA-256 of the file — the real identity (unique index)
  val mediaId: Long,              // MediaStore _ID — lookup hint only, may change
  val dateModifiedSec: Long,      // with sizeBytes: cheap "unchanged?" check before re-hashing
  val fileMissing: Boolean = false,
  val uri: String,                // content://media/external/audio/media/<id>
  val displayName: String,
  val relativePath: String,       // "Recordings/Call/"
  val sizeBytes: Long,
  val durationMs: Long,
  val dateAddedSec: Long,
  val counterparty: String?,      // parsed from filename
  val samsungLabel: String?,
  val labelKind: LabelKind,
  val status: Status,             // NOT_TRANSCRIBED, SKIPPED_BY_RULE, QUEUED, FORWARDED, TRANSCRIBING, TRANSCRIBED, ANALYZED, FAILED
  val error: String? = null,
  val attempts: Int = 0,
  val transcriptText: String? = null,
  val transcriptJson: String? = null,   // segments as JSON (our Segment type, not provider JSON)
  val transcriberId: String? = null,    // which provider produced it, e.g. "ivrit-runpod"
  val providerJobId: String? = null,    // async job handle (RunPod job id) — re-polled on retry
  val analyzerId: String? = null,       // e.g. "claude"
  val summaryHe: String? = null,
  val verdict: String? = null,          // "spam" | "not_spam" | "unsure"
  val spamConfidence: Double? = null,
  val category: String? = null,         // e.g. "telemarketing", "robocall", "personal", "business", "scam"
  val updatedAt: Long = System.currentTimeMillis(),
)
```

---

## 2. Phase 1 (POC) — detect new recording & forward it somewhere

### 2.1 Manifest
```xml
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
<!-- Optional, Phase 4 only: <uses-permission android:name="android.permission.READ_CALL_LOG"/> -->
```
Receivers: `BootReceiver` for `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`.

### 2.2 Permission flow (`PermissionGate.kt`)
1. On first launch request `READ_MEDIA_AUDIO` and `POST_NOTIFICATIONS` via
   `rememberLauncherForActivityResult(RequestMultiplePermissions())`.
2. Show a card asking the user to exclude the app from battery optimization
   (`Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` needs the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
   permission — instead just open `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` and explain).
   On Samsung also explain: Settings → Battery → Background usage limits → **Never sleeping apps** → add CallScribe.
3. Show a checklist card: "Auto record calls is ON in the Phone app" (can't be checked programmatically; just a reminder with a button that opens the dialer settings via `TelecomManager.ACTION_SHOW_CALL_SETTINGS`).

### 2.3 Detection (`RecordingScanner.kt`)
Query:
```kotlin
val projection = arrayOf(_ID, DISPLAY_NAME, RELATIVE_PATH, SIZE, DURATION, DATE_ADDED, IS_PENDING)
val selection = "${RELATIVE_PATH} LIKE ? AND ${DATE_ADDED} > ? AND ${IS_PENDING} = 0"
val args = arrayOf("Recordings/Call%", lastScanWatermarkSec.toString())
contentResolver.query(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), projection, selection, args, "$DATE_ADDED ASC")
```
- Make the path prefix a **setting** (default `Recordings/Call`), because Phase 0 may reveal a different
  folder for screened calls. Allow a list of prefixes.
- Stability check: skip files with `SIZE == 0` or whose size changed in the last 5 s (re-query once after
  a 5 s delay before accepting). Samsung writes the file after hang-up, but be defensive.
- **Recording identity — content hash, with `mediaId` only as a lookup hint.** MediaStore `_ID` is *not* stable:
  it changes when the media database is rebuilt (restore, phone migration, some OS updates) or the file is
  moved/renamed. Keying on it would duplicate rows and pay for transcription twice.
  - `contentHash` = SHA-256 of the whole file (call recordings are ~0.1–1 MB/min, so hashing takes milliseconds;
    stream it, don't load the file into memory). Unique index on `contentHash`.
  - Sync algorithm per MediaStore row: (1) row with this `mediaId` exists and `size`+`dateModified` unchanged → nothing
    to do (no re-hash); (2) otherwise hash the file; if a row with that hash exists → **re-link** it (update `mediaId`,
    `uri`, `displayName`, `relativePath`) and keep its transcript/analysis; else insert a new row.
  - A row whose file disappeared is marked `fileMissing = true` (not deleted — its transcript stays useful);
    a "Remove entries without audio" action cleans them up.
  - Unit-test: renamed file keeps its transcript; same file seen with a new `_ID` is not duplicated.
- **Listing vs auto-processing**: the scanner lists *all* recordings in the folder (a full sync on first run
  and on "Rescan"; incremental by `DATE_ADDED` afterwards; rows whose file was deleted are removed).
  The watermark `autoProcessFromSec` (DataStore, set to *now* on first run) only limits which rows are
  *auto*-processed, so old calls show as *Not transcribed* and can be transcribed manually.
- **Filename parser** (`RecordingNameParser.kt`, pure Kotlin, heavily unit-tested with the real names from Phase 0):
  input `DISPLAY_NAME`, output
  ```kotlin
  data class ParsedName(
    val counterparty: String?,     // contact name or number, if present
    val samsungLabel: String?,     // raw label text, e.g. "Suspected_Spam", "Potential_Fraud"
    val labelKind: LabelKind,      // CONTACT, UNKNOWN_NUMBER, PRIVATE, SPAM, FRAUD, OTHER_LABEL
    val timestamp: LocalDateTime?, // from trailing _yyMMdd_HHmmss if present
  )
  ```
  Steps: strip extension; strip the leading `Call_` / `Call recording ` (and Hebrew `הקלטת שיחה ` if present);
  strip a trailing `_yyMMdd_HHmmss`-style timestamp; then match the remainder against a **label table**
  (case-insensitive, `_` ≡ space): `Suspected spam` → SPAM, `Potential fraud` → FRAUD, other known Samsung
  labels → OTHER_LABEL; a digits/`+` string → UNKNOWN_NUMBER; empty/"Private"/"Unknown" → PRIVATE;
  anything else → CONTACT. Keep the label table in one `Map` so new labels are a one-line addition, and
  log + keep the raw text for any unmatched label so it can be added later.
  Add `samsungLabel` and `labelKind` columns to `RecordingEntity`.

### 2.4 Triggering (`WorkScheduler.kt`, `ScanWorker.kt`)
- **Content-URI trigger** (the core trick — works with the app closed):
  ```kotlin
  val constraints = Constraints.Builder()
      .addContentUriTrigger(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true)
      .setTriggerContentUpdateDelay(Duration.ofSeconds(5))
      .setTriggerContentMaxDelay(Duration.ofSeconds(30))
      .build()
  val req = OneTimeWorkRequestBuilder<ScanWorker>().setConstraints(constraints).build()
  WorkManager.getInstance(ctx).enqueueUniqueWork("scan-trigger", ExistingWorkPolicy.REPLACE, req)
  ```
  A content-trigger job fires **once**; `ScanWorker.doWork()` must **re-enqueue the trigger at the end**
  (in a `finally`), otherwise detection stops after the first file.
- **Periodic fallback**: `PeriodicWorkRequestBuilder<ScanWorker>(15, MINUTES)` unique name `scan-periodic`, `KEEP`.
- Enqueue both from `CallScribeApp.onCreate()`, `BootReceiver`, and after permissions are granted.
- `ScanWorker` runs the scanner, inserts new rows, and for each new row with `dateAdded ≥ autoProcessFromSec`
  asks `RuleEngine.decide(...)`: AUTO → status QUEUED and enqueue a `ProcessWorker`; SKIP → status SKIPPED_BY_RULE.
  `ProcessWorker` uses `enqueueUniqueWork("process-${recordingId}", KEEP, ...)` with `NetworkType.CONNECTED` and
  exponential backoff 30 s. Input data: `force` (manual tap), `steps` (`transcribe`, `analyze`, `export`).
- The **Transcribe / Analyze buttons** in the UI call the same `WorkScheduler.enqueueProcess(recordingId, force = true, steps)`
  — one code path for manual and automatic processing.

### 2.5 Forwarding (Phase 1 target)
Implement `Forwarder` as an interface with one implementation picked in Settings:
- **WebhookForwarder** (default): `multipart/form-data` POST to a user-configured URL with fields
  `file` (audio, `audio/mp4`), `display_name`, `counterparty`, `date_added`, `duration_ms`.
  Optional header `Authorization: Bearer <token>` from Settings.
  Good test target: a Home Assistant webhook, a `webhook.site` URL, or a tiny local `python -m http.server`-style receiver
  (include `tools/receiver.py` — a 30-line Flask/`http.server` script that saves uploads to `./inbox/`).
- Stream the file from `contentResolver.openInputStream(uri)` with an OkHttp `RequestBody` that copies the
  stream (don't load it fully into memory).

### 2.6 UI (Phase 1 part)
- Call list as in §1.2 (counterparty · Samsung label chip · date · duration · status chip, with filters).
  Tap → detail with audio player, status/error, and an **Export** button (Phase 1); Transcribe/Analyze buttons
  appear in Phases 2/3.
- Settings: path prefixes, webhook URL/token, "Rescan".
- **Automatic processing** screen and **Contact lists** screen (§1.2) are built in Phase 1 — in Phase 1 the "action"
  for AUTO is the webhook export, so rules can be tested before any paid provider exists.
- Notification channel `calls` — Phase 1 posts "New call recording forwarded: <counterparty>".

### 2.7 Phase 1 acceptance
- [ ] Fresh install, grant permissions, kill the app from recents.
- [ ] Make a recorded call. Within ~1 min the webhook receives the `.m4a` and a notification appears.
- [ ] Reboot the phone, repeat — still works.
- [ ] Same recording is never sent twice (rotate the screen, re-run scan, reboot).
- [ ] Unit tests: filename parser, scanner selection/watermark logic (Robolectric or pure JVM with a fake cursor).
- [ ] Old recordings are listed as *Not transcribed* and are not auto-processed.
- [ ] Rules: with mode "Custom: spam + unknown", a contact's call shows *Skipped by rule*; an excluded contact is
      skipped even in "All calls"; an included contact is processed in "Custom" mode. `RuleEngine` unit tests cover
      every mode × label kind × include/exclude combination, plus number normalization (`+972 54…` ≡ `054…`).
- [ ] Manual Export on a skipped call works.

---

## 3. Phase 2 — Hebrew transcription via ivrit.ai (RunPod)

### 3.1 One-time backend setup (user does this; write it into README)
1. Create a RunPod account, add a small balance.
2. Deploy the official template **ivrit-ai/runpod-serverless** (RunPod Hub) or create a serverless endpoint
   with image `yairlifshitz/whisper-runpod-serverless:latest`; container disk ≥ 20 GB; 1 GPU/worker (16 GB class is enough);
   min workers 0 (pay only when used), max workers 1–2, idle timeout ~5 s.
3. Copy the **Endpoint ID** and a RunPod **API key** into the app's Settings.
4. Expected cost: GPU time for a 1-min call is a few seconds of processing → fractions of a cent; cold start adds 20–60 s latency.

### 3.2 API contract (from `ivrit-ai/runpod-serverless/infer.py`)
Async submit:
```
POST https://api.runpod.ai/v2/{ENDPOINT_ID}/run
Authorization: Bearer {RUNPOD_API_KEY}
Content-Type: application/json

{
  "input": {
    "engine": "faster-whisper",
    "model": "ivrit-ai/whisper-large-v3-turbo-ct2",
    "streaming": false,
    "transcribe_args": {
      "blob": "<base64 of the audio file>",
      "language": "he",
      "diarize": false
    }
  }
}
→ { "id": "<job-id>", "status": "IN_QUEUE" }
```
Poll:
```
GET https://api.runpod.ai/v2/{ENDPOINT_ID}/status/{job-id}
→ { "status": "IN_QUEUE" | "IN_PROGRESS" | "COMPLETED" | "FAILED", "output": ... }
```
Output on COMPLETED (non-streaming): the handler yields `{ "result": [ batch, batch, ... ] }` where each
batch is a list of events `{ "type": "segments", "data": [ {start, end, text, ...} ] }` or
`{ "type": "progress", ... }`; errors appear as `{ "error": "..." }`. Because RunPod aggregates generator
output, `output` may be a list wrapping that object. **Parse defensively**: walk the JSON recursively,
collect every object with `type == "segments"`, concatenate `data[*].text` ordered by `start`, and
surface any `error` string.

**Implementer must verify** before coding the parser:
- The exact `blob` encoding (plain base64 vs data-URI) and whether `language` belongs in `transcribe_args`
  — read `ivrit` PyPI package source (`ivrit/audio.py`, the RunPod model class) which is the reference client.
- Run `tools/runpod_smoke.py` (write it: uses `pip install ivrit` with `engine="runpod"`) against a sample
  Hebrew m4a to capture a real response JSON; save it as `app/src/test/resources/runpod_completed.json`
  and unit-test the parser against it.

### 3.3 Size limits
- Base64 blob path is limited (~5–10 MB request). Samsung call recordings are AMR-WB/AAC ~0.1–1 MB/min,
  so ≤ ~10 min calls fit. Implement:
  - If `size ≤ 7 MB` → blob.
  - Else → mark `FAILED("too large for blob mode")` in v1, and document the v2 option: upload to a
    user-owned presigned URL (S3/R2) and pass `transcribe_args.url` instead.
- Optional: diarization (`core_engine`/engine `stable-whisper`, `diarize: true`) as a Settings toggle — gives
  speaker labels, useful for summaries. Default off (slower).

### 3.4 `IvritRunPodTranscriber.kt` (implements `Transcriber`)
- `suspend fun submit(uri): String /*jobId*/` → store `providerJobId`, status `TRANSCRIBING`.
- `suspend fun poll(jobId): Result` — poll every 3 s for up to 2 min within the worker; if still queued
  (cold start), return `Result.retry()` from the worker — on resume it **re-polls the stored jobId**
  instead of resubmitting.
- Also support optional "sync" mode via `/runsync` for debugging.
- Save `transcriptText` + raw JSON; status `TRANSCRIBED`; update the notification.

### 3.5 Phase 2 acceptance
- [ ] A real Hebrew call produces readable Hebrew text in the detail screen (render RTL:
      `CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl)` for the transcript).
- [ ] Killing the app mid-transcription → it resumes polling the same job, no duplicate charge.
- [ ] Bad key / endpoint → clear error in UI, status FAILED, retry button works.
- [ ] Phase 1 forwarding remains available as an option ("also forward to webhook").

---

## 4. Phase 3 — Claude summary + spam verdict

### 4.1 Call
Use the Anthropic Java SDK from Kotlin:
```kotlin
val client = AnthropicOkHttpClient.builder().apiKey(settings.anthropicKey).build()
val params = MessageCreateParams.builder()
    .model("claude-opus-5-5")
    .maxTokens(2000L)
    .outputConfig(OutputConfig.builder()
        .effort(OutputConfig.Effort.LOW)          // classification + short summary; low is enough
        .format(/* JSON schema below — structured outputs */)
        .build())
    .system(SYSTEM_PROMPT)
    .addUserMessage(userPrompt)
    .build()
val msg = client.messages().create(params)
```
Notes for the implementer:
- Model: `claude-opus-5-5` by default; make it a Setting with `claude-haiku-5-5` as the cheap option
  (a call summary is a light task; Haiku 5.5 costs ~$0.10/M input).
- Thinking is always on for Opus 5.5 — do **not** send `ThinkingConfigDisabled` or a thinking budget (400).
- Use **structured outputs** (`output_config.format` with a JSON schema) — not assistant prefill (400 on current models).
  Look up the exact Java builder for the JSON-schema format in the SDK docs/README before writing it; don't guess.
- Check `stop_reason`: if `refusal`, store verdict `unsure` with the reason; if `max_tokens`, retry once with a higher limit.
- Enable server-side refusal fallback (beta `server-side-fallback-2026-07-01`, `fallbacks: "default"`) via
  the beta messages endpoint if the SDK binding is straightforward; otherwise skip and rely on the `refusal` handling above.
- Handle errors with the SDK's typed exceptions (rate limit → WorkManager retry; 4xx auth → FAILED, no retry).

### 4.2 JSON schema
```json
{
  "type": "object",
  "additionalProperties": false,
  "required": ["summary_he", "verdict", "spam_confidence", "category", "caller_intent", "action_items"],
  "properties": {
    "summary_he":      {"type": "string", "description": "2–4 sentence Hebrew summary"},
    "verdict":         {"type": "string", "enum": ["spam", "not_spam", "unsure"]},
    "spam_confidence": {"type": "number", "description": "0.0–1.0 probability the call is unwanted"},
    "category":        {"type": "string", "enum": ["robocall", "telemarketing", "scam_or_phishing", "survey_or_political", "business", "personal", "delivery_or_service", "other"]},
    "caller_intent":   {"type": "string", "description": "One short Hebrew line: what the caller wants"},
    "action_items":    {"type": "array", "items": {"type": "string"}}
  }
}
```

### 4.3 Prompt
System (English instructions, Hebrew output):
> You analyse transcripts of phone calls received by an Israeli user. Transcripts are produced by
> automatic Hebrew speech recognition and may contain errors, missing words, or English fragments.
> Write the summary and caller_intent in Hebrew. Judge whether the call is unwanted: recorded/robotic
> messages, telemarketing, loans/insurance/investment offers, surveys and political campaigns, and
> anything asking for codes, ID numbers, or payment details count as spam (scam_or_phishing when
> credentials or money are requested). Calls from people the user plausibly knows, deliveries,
> appointments, and businesses the user deals with are not spam. If the transcript is too short or
> unclear, use "unsure". Never invent details that are not in the transcript.

User message: metadata block (counterparty name/number from filename, `labelKind` + raw Samsung label
— e.g. "Samsung caller-ID labelled this call: Potential fraud" — call duration, time of day) + the transcript
inside `<transcript>` tags. Tell the model in the system prompt that the Samsung label is a strong prior but can be
wrong (numbers get mislabelled); the transcript content decides. A CONTACT call should default to not_spam unless
the transcript clearly says otherwise.

**Settings toggle — "Which calls to process"**: All recordings (default) / Only labelled or unknown callers
(skip CONTACT) / Only Samsung-labelled spam & fraud. Filtering happens in `ScanWorker` before any upload,
so contacts' calls never leave the phone when the user chooses so.
Cap transcript at ~30k characters (calls rarely exceed this; if exceeded, tell the model it was cut).

### 4.4 Output
- Save fields to Room; status `ANALYZED`.
- Notification: title `🚫 ספאם (92%) · 053-1234567` or `📞 054-… · עסקי`; body = `caller_intent`;
  BigTextStyle with the summary; action "Open".
- Detail screen: verdict chip, summary, action items, full transcript (RTL, selectable), audio player
  (`MediaPlayer` on the content URI), "Re-run summary" button.

### 4.5 Phase 3 acceptance
- [ ] 5 sample transcripts (write them as test fixtures: robocall loan offer, scam asking for a code,
      delivery courier, family member, bank appointment) give the expected verdicts.
- [ ] End to end on the phone: recorded call → notification with Hebrew summary and verdict within ~2 min.

---

## 4a. Implementation status (2026-11-23)
Built and verified on a Galaxy S20 FE (Android 13, One UI 5.1) with 372 real recordings:
- Phases 1–3 implemented (see `app/`). 23+ unit tests pass (parser, rules, sync/hash identity,
  real RunPod response fixture).
- Scan: 372 files indexed in ~14 s on first run (hashing); later scans re-hash nothing.
- Samsung labels confirmed on One UI 5 too: `Call recording Suspected Spam_…`, `Call recording Potential Fraud_…`.
- Recordings are **mono** AAC 44.1 kHz ~128 kbps (~1 MB/min) → speaker separation needs diarization (§5).
- RunPod: real `/run` + `/status` round trips; 16 s clip = 3 s GPU; 14 min call (compressed on-phone to
  3.3 MB in ~96 s) = 14 s GPU ≈ $0.003.
- Claude SDK verified on-device (also after R8) with an invalid key → clean 401 handling. **Not yet run
  with a real key** — needs the user's Anthropic key.
- **Cost view** done: per-call transcription (GPU-seconds × user-set $/s, an estimate) and analysis
  (tokens × list price) costs, plus the total in the call list header.
- Not yet verified visually: UI screens (phone was locked during development).

## 4b. Done 2026-11-23 (second round)
- **Policies** replace include/exclude lists: per caller type and per contact — transcribe, summarize,
  recording retention, optional text retention (default: keep text). DB migration v1→v2 converts old lists.
- **Retention**: daily worker expires text (opt-in) and notifies about recordings due; review screen moves them to
  the MediaStore trash via `createTrashRequest` (one system confirmation, or none with `MANAGE_MEDIA`).
- **AI providers**: Gemini (RPM throttling, 429/503 retry) and OpenRouter (exact per-request cost from `usage.cost`)
  next to Claude, chosen in Settings. Live-tested with real keys (`LiveProvidersTest`, opt-in via `CALLSCRIBE_LIVE=1`).
- **Search** (name / number in any format / transcript & summary text) and a Transcribe button on every row.
- Verified on device: real transcription from the row button (with diarization) and policy/retention screens.

- **Policies v3** (docs/plans/policies.md): targets contact > contact group (phone / Google Contacts, user-ordered) >
  AI tag > caller type (spam+fraud merged, Samsung "other labels" folded into unknown numbers); the most specific policy wins as a
  whole; cloud retention (forever / N days / same as phone) stored and planned for the future Drive backup; soft
  warnings for odd combos; the call page names the policy that applied. DB v2→v3 (keeps all policies; spam+fraud rows merged keep-more).

## 4c. TODO
- **Google Drive backup & sync** — implemented on branch `claude/drive-backup-*`, see `docs/plans/drive-backup.md`
  (scope changed to `drive.file`, a visible folder). Needs on-device verification once an Android OAuth client exists.
- Monthly cost totals and a budget alert.
- **Automatic log collection** (opt-in, the log uploaded to the user's own Google Drive), see
  `docs/plans/log-collection.md`.

## 5. Phase 4 (optional, later)
- **Speaker separation ("who said what")** — show the transcript as a chat: *Me* vs *Caller* (or Speaker 1/2/3 on
  conference calls). The `Segment.speaker` field already exists, so this needs no schema change. Strategy chosen
  by what Phase 0 found:
  1. **Stereo recording, one side per channel** (best case): split the channels on the phone (MediaCodec decode →
     two mono PCM/WAV streams, or one transcription request per channel), transcribe each, merge segments by
     start time, label uplink = *Me*, downlink = *Caller*. Exact, no ML needed.
  2. **Mono recording**: use diarization in the transcriber — the ivrit.ai RunPod handler supports
     `diarize: true` with the `stable-whisper` engine (pyannote-based), returning speaker labels per segment.
     Diarization says *Speaker A/B*, not *who is who*: map them with a heuristic (who speaks first on an incoming
     call is usually *Me* answering, or the screening assistant on screened calls) and let the LLM confirm/override
     in `Analysis` (add `speakerRoles: Map<String, "me"|"caller"|"assistant">`). The user can swap labels by tapping
     a speaker chip; store the override on the row.
  3. Later: voice enrollment (record a short sample of the user's voice once, match speaker embeddings) for
     reliable *Me* labelling without the LLM.
  - Screened calls have a third voice — Samsung's AI assistant. Prompt the analyzer to recognise it so the
    summary focuses on what the caller said.
  - UI: chat-bubble transcript (RTL), color per speaker, tap a bubble to seek audio to `startSec`.
- **Caller number from call log**: `READ_CALL_LOG` (runtime, "dangerous" but allowed for sideloaded apps);
  match `CallLog.Calls.DATE + DURATION` to the recording's `DATE_ADDED` ±60 s. Show if number is in contacts.
- **Auto-actions**: if verdict = spam with confidence > 0.9, offer a notification action "Block number"
  (`BlockedNumberContract` requires being the default dialer — not possible; instead deep-link to the
  dialer's block screen) or "Report".
- **Home Assistant integration**: post the final JSON to an HA webhook → HA notification / history.
- **Large files**: presigned-URL upload for >7 MB.
- **On-device alternative**: whisper.cpp with an ivrit.ai GGML model (fully offline, slower; turbo model ≈ 1.6 GB).
- **Privacy**: setting to delete transcripts after N days; never upload when a contact name matches an
  "exclude" list (e.g. family).

---

## 6. Privacy & legal notes (put in README)
- License: CallScribe's code is Apache-2.0 (`SPDX-License-Identifier: Apache-2.0`). Third-party components and
  downloaded models keep their own licenses; see `THIRD_PARTY_NOTICES.md` (done 2026-11-23).
- Audio and transcripts leave the phone (RunPod GPU worker you control; Anthropic API). Only the user's
  own keys are used; nothing is sent elsewhere.
- In Israel, recording a call you take part in is generally permitted; the user is responsible for
  local law. Samsung already shows a recording notice to the other party in some regions.

## 7. Build/run instructions to include
- Android Studio (latest stable), JDK 17, `./gradlew assembleDebug`, install via `adb install -r`.
- `tools/receiver.py` for Phase 1 testing, `tools/runpod_smoke.py` for Phase 2.
- Settings to fill: path prefixes, webhook URL (P1), RunPod endpoint ID + key (P2), Anthropic key + model (P3).

## 8. Order of work for the implementer
1. Phase 0 findings doc: screened-call saving is already confirmed; collect real filenames per label (needs the phone).
2. Project skeleton, Room, Settings, permission gate, provider interfaces + registry (§1.1, no impls yet).
3. Scanner + call list + RuleEngine + contact lists + workers + webhook export → Phase 1 acceptance on device.
4. RunPod smoke script → capture real response → parser + tests → `IvritRunPodTranscriber` + Transcribe button → Phase 2 acceptance.
5. `ClaudeAnalyzer` with fixtures + Analyze button → Phase 3 acceptance.
6. Polish UI/notifications, README.
