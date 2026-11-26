# Automatic log collection — plan (not built)

Goal: when something goes wrong on a phone, the log reaches whoever debugs it without the user doing anything —
no cable, no share sheet. Today (0.1.16) the log is on the phone only: Settings → Troubleshooting → View log
(copy / save to Downloads / share).

## How other apps do it

| Approach | What it collects | Cost / setup | Fit for CallScribe |
|---|---|---|---|
| **Google Play Android vitals** (Play Console) | Crashes and ANRs only, from users who allow "usage & diagnostics". No app logs. | Free, no code. Only for apps installed from Google Play. | Only if CallScribe is published on Play. Doesn't show *why* a call failed (those aren't crashes). |
| **Firebase Crashlytics** (the most common choice) | Crashes, non-fatal errors you report, and the last 64 KB of custom log lines attached to each report. | Free. Needs a Firebase project and `google-services.json` built into the app. | Poor: every user's reports go to the developer's Firebase project, against "bring your own services" and "privacy first". |
| **Sentry / Bugsnag** | Errors with breadcrumbs (recent log lines), releases, alerts. | Hosted (Sentry free tier ~5k errors / month) or self-hosted Sentry. A DSN is configured in the app. | Possible as an opt-in with the user's own DSN, but it's another account to create. |
| **ACRA** (open source) | Crash reports with logcat and custom files, sent by HTTP or email. | Free; needs a receiving server (Acrarium) or email. | Close in spirit, but the user needs a server, or must tap Send in an email. |
| **Upload to the user's own cloud** | Whatever the app chooses: the whole log. | Uses a storage account the user already connected. | **Best fit**: CallScribe already has Google Drive backup. |

## Recommendation: send the log to the user's own Google Drive

An opt-in switch in Settings → Troubleshooting, "Send the log to my Google Drive", shown when Drive backup is
connected (same account, same `drive.file` scope, so no new permission).

- **What**: `Logx.text()`, the existing log (no names, numbers, transcripts or keys; already enforced by `Logx`).
- **Where**: `CallScribe/<phone>/logs/callscribe-log-YYYY-MM-DD.txt` in the backup folder. One file per day,
  overwritten on each upload, the last 14 days kept.
- **When**:
  - shortly after a warning or error (a unique WorkManager job, debounced to at most one upload per 10 minutes);
  - daily with the backup;
  - after a crash, on the next start (the uncaught-exception handler already writes the crash to the log).
- **Network**: any network for a log under 1 MB, following the backup's Wi-Fi-only setting for anything larger.
- **Reading it**: open the file in Drive on any computer, or Claude reads it through the Google Drive connector
  (no cable, no share step).
- **Onboarding**: none. It's off by default and isn't required setup. If it later becomes a policy default,
  update the onboarding per CLAUDE.md.

### Steps
1. `LogUploadWorker` (unique, KEEP, 10-min initial delay) uploads today's log file via `DriveApi`; it is enqueued
   from `Logx.w/e` when the switch is on (through a small hook, so `Logx` stays dependency-free).
2. Folder lookup and creation reuse the backup's device folder (`BackupFormat.deviceFolderName`).
3. Clean-up of log files older than 14 days runs in the daily backup.
4. Settings switch with a "Last sent: …" line; "Send now" button.
5. Tests: the debounce and the file naming/retention are pure functions; `DriveApi` is already faked in the backup
   tests.

### Optional additions later
- **Crash-only telemetry to Play vitals** comes for free if the app is published on Google Play; nothing to build.
- **Bring-your-own Sentry DSN** for users who want alerts and grouping: a text field plus the Sentry Android SDK
  added only when a DSN is set (≈ 1 MB of app size; it can't be stripped out at runtime, so weigh the size).
