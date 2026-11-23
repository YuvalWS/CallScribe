# Google Drive backup & sync (PLAN §4c)

Goal: back up CallScribe's own data — and optionally the recordings — to the **user's own Google Drive**, incrementally,
and restore it on a new phone. No app servers; the user's Google account only. Off by default.

## Folder layout — one folder per phone
`CallScribe Backup/<device name>/` holds `settings.json`, `policies.json`, `manifest.json` and the subfolders
`Calls/` and `Recordings/`. The phone folder is matched by `cs_device` = a random id created on first backup
(stored in the `drive_backup` DataStore, excluded from Android backup), so a test phone and a daily phone on the same
Google account never overwrite each other's settings/policies, and cloud retention only ever touches the phone's own
folder. The folder name comes from Settings → About phone → Device name (`Settings.Global.DEVICE_NAME`).
Restore lists every phone's backup (name, date, counts from its manifest) and restores the one the user picks; the
restoring phone keeps backing up into its own new folder.

## What is backed up

| Item | Drive file | When uploaded |
|------|-----------|---------------|
| Each call row (metadata, transcript, segments, analysis, costs) | `Calls/<yyyy-MM-dd HH-mm> <who> <hash8>.json` | new, or its content fingerprint changed |
| Policies | `policies.json` | content changed |
| Settings (`AppSettings`) | `settings.json` | content changed |
| Audio (opt-in) | `Recordings/<original file name>` | not yet on Drive, file present locally, not due for cloud deletion |
| Manifest (format version, app version, time) | `manifest.json` | every run |

**Never backed up:** API keys / tokens (`SecretStore`, already excluded from Android backup). `AppSettings` holds no
secrets by construction; a unit test fails if a field name looks like a key/token/secret. Device-specific fields
(`id`, `mediaId`, `uri`, `fileMissing`, `updatedAt`) are normalised in the call JSON.

Every file carries `appProperties` (`cs_kind`, `cs_hash`, `cs_sum`) so the app finds its files by property, not by
name — the user may rename or move them inside the folder.

## Incremental, stateless
Each run lists the backup folders (two `files.list` calls, 1000 per page) and compares with the DB:
- call JSON: upload when missing or its `cs_sum` (SHA-256 of the normalised JSON) differs → update in place.
- audio: upload when no file with that `cs_hash` exists.
No local "what did I upload" table → **no Room schema change** (no 2→3 migration from this branch), and if the user
deletes something on Drive it is simply re-uploaded. Cost: one listing per run.

Large audio uses a resumable upload session streamed from the `ContentResolver` (no full file in memory).
Small JSON uses multipart upload.

## Scheduling
- `DriveBackupWorker`, unique periodic work `drive-backup`, daily. Constraints: network **unmetered** (setting
  "Wi-Fi only", default on) or connected; optional **requires charging** (default off). Re-enqueued with
  `ExistingPeriodicWorkPolicy.UPDATE` whenever the settings change; cancelled when backup is turned off.
- "Back up now" = one-time work with only a network constraint.
- Status (last success time, last result line, counts) stored in a separate DataStore (`drive_backup`), shown in
  Settings → Google Drive backup.

## Cloud retention
Consumed through the pure planner `Retention.plan(...)` (`rules/RuleEngine.kt`), which gains
`cloudAudio` / `cloudText` lists — the same names as the policies-redesign branch:
- Per-policy `cloudRetentionDays`: `-1` forever, `N` days, `-2` **same as local**.
- **Same as local** for audio = the cloud copy goes when the *local recording is actually deleted* by retention
  (`audioDeletedAt != null`), not merely due. For text: when local text was deleted (`textDeletedAt != null`).
- **N days**: due when the call is older than N days. **0 days** therefore means "never put this group's audio on
  Drive" — that is the per-policy opt-out for audio backup (no extra column needed).
- **Keep forever** (per call, other branch) → never in `cloudAudio`/`cloudText`.

The backup run acts on the plan: `cloudAudio` → trash the Drive audio and never (re)upload it; `cloudText` → the call
JSON is uploaded with transcript/analysis stripped (the row survives so a restore still knows the call).
Drive deletions use **trash** (recoverable 30 days in Drive), mirroring the local system trash.

**Merge note:** `main` has no `cloudRetentionDays` yet. This branch adds a one-line hook
`PolicyEntity.cloudDays()` (returns `SAME_AS_LOCAL` on main) and `RecordingEntity.keptForever()` (returns `false`).
At merge with policies-redesign / keep-forever, point the hooks at the real columns and keep this branch's
"same as local = actually deleted locally" rule in `Retention.plan`.

## Restore (new phone)
Settings → Google Drive backup → **Restore from Drive**:
1. Downloads `settings.json` (keeps the local `autoProcessFromSec` and backup settings), `policies.json` (replaces
   local policies), and every call JSON.
2. Merges calls by `contentHash`: existing row → fill in transcript/analysis only where the local row has none;
   unknown hash → insert with `fileMissing = true`, `mediaId = -1`.
3. Rescans: `SyncEngine` re-links rows to local audio by content hash (unchanged code path — `byHash`).
4. Optional checkbox **"Also download recordings missing on this phone"**: writes them via MediaStore into
   `Recordings/Call/` under their original name; the next scan hashes and re-links them.

## Auth — decision
**(a) Chosen: Google Identity `AuthorizationClient` (Play services) + Drive REST v3 over the existing OkHttp.**
- Scope **`drive.file`** (recommended) — the app only sees files it created; the backup is a normal visible folder
  "CallScribe Backup" in My Drive, so the user can see, download or delete their recordings and transcripts.
  `drive.appdata` would hide everything in the app-data folder (user can only delete it wholesale under
  Drive → Settings → Manage apps), which defeats "I want to see my files". Both are *non-sensitive* scopes:
  no Google verification needed, no "unverified app" warning.
- Trade-off of `drive.file`: if the user moves files out of the folder the app still owns them but won't look
  there; files the user uploads into the folder manually are invisible to the app. Both fine.
- No client ID or secret in the code: for Android OAuth clients Google matches **package name + signing SHA-1**.
  The builder registers an *Android* OAuth client in their own Cloud project (README steps). Release and debug
  builds signed with different keys need one OAuth client each (same project).
- Background runs call `authorize()` silently; if Google needs the user again (revoked / new account), the run stops
  with status "Sign-in needed" and a notification that opens the backup screen.
- On HTTP 401 the token is cleared (`clearToken`) and re-requested once.
- Dependency: `com.google.android.gms:play-services-auth` only (no Google API client / Drive SDK, which are many MB).
  AppAuth-style browser OAuth is not an option: Google no longer allows custom-scheme redirects for Android clients.

**(b) No-OAuth alternative: Storage Access Framework into the Drive app — rejected for sync.**
Google Drive's `DocumentsProvider` supports creating single documents (`ACTION_CREATE_DOCUMENT`) but not
`ACTION_OPEN_DOCUMENT_TREE`, so the app cannot keep a folder in sync, list what is there, or delete expired audio.
Writes are queued by the Drive app with no completion signal, and persisted grants to Drive documents are fragile in
background work. It would only allow a manual "export one archive file" — a possible later add-on
(it would also work with local storage / USB), not a sync.

## UI
Settings → **Google Drive backup** (new screen `ui/BackupScreen.kt`):
- Switch "Back up to Google Drive" (off by default). Turning it on runs the Google authorization (account picker +
  consent for "See, edit, create and delete only the specific Google Drive files you use with this app").
- Status: "Last backup: <time> · N calls, M recordings" or the last error.
- Switches: "Include recordings (audio)" (off), "Wi-Fi only" (on), "Only while charging" (off).
- Hint: per-group cloud retention lives in Policies; API keys never leave the phone.
- Buttons: Back up now · Restore from Drive (+ checkbox) · Disconnect.

## Onboarding (done)
Step `BACKUP("Back up to Google Drive?")` between `POLICY` and `DONE` — skippable, optional. Body: one sentence + the
same "Back up to Google Drive" / "Include recordings" switches as the backup screen (shared composable
`DriveBackupToggles`); done when backup is on and not waiting for sign-in (`SetupStatus.driveBackupOn`). The Done
checklist shows it as optional; it is not in the "missing required" banner.

## Code
- `backup/DriveApi.kt` — `DriveApi` interface + `DriveFile`, `UploadContent`.
- `backup/DriveRestApi.kt` — OkHttp implementation (list, create folder, multipart + resumable upload, download, trash).
- `backup/DriveAuth.kt` — `AuthorizationClient` wrapper (interactive + silent), token cache, `clearToken`.
- `backup/BackupFormat.kt` — JSON (kotlinx.serialization) for calls/policies/settings, fingerprint, file naming,
  text stripping, restore merge (pure).
- `backup/DriveBackupEngine.kt` — backup run + restore (pure Kotlin, takes `DriveApi` + a data source/sink).
- `backup/DriveBackupWorker.kt`, `backup/BackupSettings.kt` (separate DataStore), `ui/BackupScreen.kt`.
- `rules/RuleEngine.kt` — `cloudAudio`/`cloudText` in `RetentionPlan` + hooks.
- Tests: `DriveBackupTest` with an in-memory `FakeDriveApi`.

## Setup the builder must do (README)
Google Cloud project → enable Drive API → OAuth consent screen (External, app name, scope `drive.file`, publish to
"In production" or add yourself as test user) → Credentials → OAuth client ID → Android → package `app.callscribe`
+ SHA-1 of the signing key (`keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey
-storepass android`; for a release key, the same command on that keystore). Nothing to paste into the app.

## Done when
Plan doc, implementation behind the toggle, unit tests with a fake Drive, README section, commit on this branch.
On-device verification needs the OAuth client above.
