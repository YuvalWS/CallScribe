# Secrets in call recordings (plan — not implemented)

People read card numbers, ID numbers (תעודת זהות), bank details, one-time codes and sometimes passwords out loud
on the phone. Once CallScribe transcribes a call, those secrets become **searchable text** and can travel to
cloud services. This plan covers where they end up today, what we can realistically detect and when, how to
handle them, and a phased build order.

Status: planning only (2026-11-23). Nothing here is built. Open decisions for the user are in §8.

---

## 1. Threat model — where a spoken secret ends up today

Code paths checked on `main` (commit 7409e6a).

| # | Place | Who controls it | What is there | Today |
|---|---|---|---|---|
| 1 | Samsung's `.m4a` in `Recordings/Call` | Samsung / the user. **We don't own it**; we can only read it, or ask the user to trash it (`MediaStore.createTrashRequest`, already used by retention). | Full audio | Untouched by us. Samsung's own backup/cloud (Samsung Cloud, Smart Switch) may copy it — out of our scope, but worth one line in the README. |
| 2 | Audio upload to the user's RunPod endpoint (`IvritRunPodTranscriber`, base64 in the job input) | The user's own RunPod account (third-party GPU host) | Full audio, then the transcript in the job output | Every transcribed call. RunPod keeps serverless job input/output for a limited time after completion (check the current retention before quoting a number in the README). |
| 3 | Room DB `recordings.transcriptText` + `transcriptJson` (segments) | The phone | Full text, digits included | Every transcribed call. Also shown in the call-list row preview (`CallListScreen.kt:178`) and searched (`CallListScreen.kt:68`). |
| 4 | **Android Auto Backup** | Google (backup is end-to-end encrypted with the lock-screen secret on Android 9+ when a screen lock is set) | The whole Room DB | **Leak today:** `allowBackup="true"` and the backup rules exclude only `secrets.xml`, so `callscribe.db` (transcripts, summaries) is included in cloud backup and device transfer. |
| 5 | Transcript sent to the AI provider (`AnalysisPrompt.user`) | Gemini / OpenRouter (+ the routed model vendor) / Anthropic | Up to 30,000 chars of transcript + caller metadata | Every analyzed call. **Gemini free tier may use prompts to improve Google's products and may be read by human reviewers.** OpenRouter routes to a third party whose retention depends on the provider/model. |
| 6 | AI output stored locally (`summary`, `callerIntent`, `analysisJson` incl. `action_items`) | The phone | The model may echo the secret ("ask the caller to send code 4821") | Every analyzed call. |
| 7 | Notifications (`Notifier.notifyResult`) | Lock screen, smartwatch, notification history, notification-listener apps | Caller intent / summary, or the **first 200 chars of the transcript** when not analyzed | Default visibility = shown on the lock screen unless the user's system setting hides content. |
| 8 | Webhook export (`ProcessWorker.export`) | Whatever URL the user set (e.g. Home Assistant) | The **audio file** + caller metadata (no transcript today) | When a webhook URL is configured. |
| 9 | Google Drive backup (being built in another session, branch `claude/drive-backup-1903db`) | The user's Drive (Google) | Probably DB export and/or audio | Future. Must honour the flag in §5. |
| 10 | Error strings / logcat | The phone | `ProviderError` on a bad model reply includes `text.take(200)` of the model output; stored in `error` and shown in the failure notification | Rare; small. |

Takeaways:
- **The AI provider (5) is the most important boundary**: it is a third party, the text is ready-made, and on the
  Gemini free tier it may be used for training. It also does not *need* the digits — a summary or spam verdict
  is just as good with `[מספר כרטיס]` in place of the number.
- **Audio has already left by the time we have text** (2) unless transcription is on-device (§2.4).
- (4) is an existing leak independent of this feature and is cheap to close.

---

## 2. Detection — options, limits and *when* each can run

### 2.1 The ordering problem
```
cloud path:   audio ──upload──▶ RunPod ──text──▶ [detector] ──▶ Room / AI / notify / webhook / Drive
on-device:    audio ──▶ on-device ASR ──text──▶ [detector] ──▶ (decide whether anything leaves at all)
```
- Before upload we only have **audio and metadata** (caller, Samsung label, duration). Nothing in that tells us
  a card number was spoken. Metadata can only drive coarse policy ("never send calls with my bank to the cloud").
- With **cloud transcription** the earliest point we can detect is *after* RunPod returns. From there we can still
  protect **every later sink** (3–10). We cannot un-send the audio to RunPod. That is acceptable for the first
  version because RunPod is the user's own endpoint (bring-your-own) and stateless apart from short job retention.
- With **on-device transcription** (other session: sherpa-onnx + ivrit-ai Zipformer, 72 MB, ~20–40× faster than
  Whisper Turbo on the phone) detection runs before anything leaves. Note its model card: *no punctuation,
  numbers inconsistent* — the normaliser (§2.3) has to handle number words, not just digits.
- A **"screening pass"** combines both: when the on-device model is downloaded but the user prefers RunPod for
  quality, run the fast on-device model first, detect sensitive spans, then mute those spans in the audio we
  upload (we already decode → re-encode in `AudioCompressor`) or keep the whole call on-device. Cost: one extra
  on-device pass per call (seconds for a typical call; measure). This is the only way to protect (2) without
  giving up cloud quality.

### 2.2 Rule-based detectors on text (pure Kotlin, JVM-testable, run anywhere)
No ready-made Hebrew PII library exists for JVM/Android: Presidio is Python and has no Israeli/Hebrew
recognizers (it supports custom ones — same idea as ours); Google ML Kit Entity Extraction runs on-device and
finds payment cards/IBANs, but has no Hebrew model and pulls in Play-services model downloads. Commercial DLP
products (Palo Alto, Forcepoint) have Israeli-ID patterns with Hebrew keywords — confirming the approach
below (pattern + checksum + nearby keyword) is the industry norm. So: small hand-written detectors.

| Kind | Pattern (after normalisation to digits) | Validation | Needs a cue word? |
|---|---|---|---|
| Card (PAN) | 13–19 digits, maybe in groups of 4 | Luhn; IIN prefix (4 Visa, 51–55/2221–2720 MC, 34/37 Amex, 36/38 Diners) | No if Luhn + IIN + ≥15 digits; yes for shorter |
| Isracard domestic | 8–9 digits | Isracard check (weights 9..1 mod 11) | **Yes** — collides with ID numbers and landlines |
| Israeli ID (ת"ז) | 9 digits (spoken often as 8 + check digit, or with leading 0 dropped → pad to 9) | ID check digit (alternating ×1/×2, sum digits, mod 10) | **Yes** — 10% of random 9-digit strings pass, incl. landlines `0X-XXXXXXX` |
| Expiry (תוקף) | MM/YY or "שתיים עשרה עשרים ושמונה" | month 1–12, plausible year | Yes (תוקף, valid, בתוקף עד) |
| CVV | 3–4 digits | — | **Yes**, within a few words of CVV / שלוש ספרות / בגב הכרטיס |
| OTP / verification code | 4–8 digits | — | **Yes** (קוד, קוד אימות, הקוד שקיבלת, SMS, הודעה, סיסמה חד פעמית) |
| Bank account | bank 2 digits + branch 3 + account 4–9 | Bank code in a known list (10, 11, 12, 13, 14, 17, 20, 31, 46, 52, 54…) | Yes (בנק, סניף, חשבון, מספר חשבון) |
| IBAN | `IL` + 21 digits | ISO 13616 mod-97 | No (rarely spoken; cheap to add) |
| Password / PIN | anything | — | **Only cue-based**: mask the N tokens after "הסיסמה (שלי) היא", "סיסמה", "קוד סודי", "PIN", "password", "user name / שם משתמש" |

Cue lexicon (Hebrew + common English loanwords as ASR writes them): כרטיס אשראי, מספר כרטיס, ויזה, מאסטרקארד,
ישראכרט, אמריקן אקספרס, תוקף, CVV, שלוש הספרות, תעודת זהות, ת"ז, מספר זהות, ספרת ביקורת, קוד, קוד אימות,
הקוד שקיבלת, סיסמה, סיסמא (common misspelling), קוד סודי, PIN, password, בנק, סניף, מספר חשבון, העברה בנקאית,
IBAN. Cues are matched with optional prefixes (ה, ו, ב, ל, ש, מ, כש) and both spellings (מלא/חסר).

**Cue window:** a number counts as sensitive if a cue appears within ~12 words *before* it (or 4 after:
"…1234, זה הקוד"), across segment boundaries.

### 2.3 Hebrew spoken-number normalisation
ivrit.ai Whisper (cloud) mostly writes digits already, sometimes with spaces/dashes/commas inside a number.
Zipformer (on-device) writes words. The normaliser turns both into one digit run plus a mapping back to the
original character range (needed to mask the right characters).

- Units, both genders and construct forms: אפס, אחת/אחד, שתיים/שניים/שתי/שני, שלוש/שלושה/שלושת, ארבע/ארבעה,
  חמש/חמישה, שש/שישה, שבע/שבעה, שמונה, תשע/תשעה. Teens: אחת עשרה/אחד עשר … תשע עשרה. Tens: עשר, עשרים … תשעים.
  Hundreds: מאה, מאתיים, שלוש מאות … ; אלף, אלפיים, …אלפים.
- The connective **ו** prefix (ושלוש, ועשרים) and ASR splits ("ו שלוש").
- Reading styles: digit-by-digit ("ארבע חמש שש שבע"), pairs ("ארבעים וחמש שישים ושבע" → 4567), quads,
  and mixed digits+words ("4580 שתיים שלוש").
- A run continues across digit groups separated by spaces, `-`, `,`, `.` or a short pause word (אה, כן, רגע) and
  across segment boundaries when the gap is < ~2 s.
- Ambiguity: "מאה עשרים ושלוש" may mean 123 or 1-2-3 spoken badly — produce digit-by-digit and compositional
  candidates and keep the one that validates; if none validate, fall back to the cue rule.
- Prior art: a Node module `VoicenterTeam/hebrew-itn` does Hebrew ITN (check license/quality before borrowing
  ideas; we won't add a JS runtime). NeMo's WFST ITN has no Hebrew grammar. We write ~200 lines of Kotlin with
  a table-driven tokenizer; it is fully unit-testable on the JVM.

### 2.4 Which stage catches what (realistic)
| Stage | Input available | Can catch | Can protect |
|---|---|---|---|
| Before upload, metadata only | caller, label, duration | Nothing about content; only "this caller is my bank/clinic" policy | Everything incl. RunPod (by not uploading / transcribing on-device) |
| After cloud transcription, rules (§2.2) | full text | Cards/IDs with checksum (good recall), cue-based codes/CVV/bank (medium), passwords (low) | Room, AI, notifications, webhook, Drive, backup — **not RunPod** |
| AI analyzer flagging spans | full text, already sent | Paraphrased/odd cases rules miss | Nothing upstream of the AI — it already saw the text. Useful only as a *second opinion* on a **redacted** transcript ("does anything sensitive remain?") and to label the call `scam_or_phishing` |
| On-device ASR then rules | full text (weaker numbers) | Same as cloud rules, lower recall on numbers | Everything, including RunPod |
| On-device screening pass + cloud | on-device text, then cloud text | Union of both | RunPod gets muted spans; everything else gets the cloud-text detection too |

---

## 3. Handling options

| Option | What | Feasibility / cost | Recommend |
|---|---|---|---|
| **Redact text sent to the AI** | Replace spans with typed placeholders `[כרטיס אשראי]`, `[ת"ז]`, `[קוד אימות]`, `[סיסמה]` | Trivial once detection exists; no quality loss — the placeholder *helps* the scam verdict ("caller asked for an OTP") | **Phase 1, default on** |
| **Mask in stored transcript** | `•••• •••• •••• 1234` (cards: last 4), IDs fully masked, codes/passwords `••••`, in `transcriptText` **and** `transcriptJson` segments | Easy; loses the digits for the user forever (they still have Samsung's audio unless deleted) | Phase 1, default on (see decision D1) |
| Keep original locally, encrypted | Store unmasked text encrypted with a Keystore key; "Show" needs biometric | Medium; key management, backup exclusion, more UI | Not now; revisit if users ask |
| **Flag "contains sensitive data"** | Kinds found (CARD, ID, OTP, …) + span offsets, never the values | Small schema change | Phase 1 |
| Mask AI output | Run the detector on `summary`, `callerIntent`, `action_items`; prompt says never repeat digits of codes/cards/IDs | Easy | Phase 1 |
| Notifications | `VISIBILITY_PRIVATE` + a public version ("Call processed") for flagged calls; never preview raw transcript for flagged calls | Easy | Phase 1 |
| Webhook | Per setting: skip flagged calls (default) / send anyway. Today it sends **audio**, so masking text doesn't help | Easy | Phase 2 |
| Drive backup | Back up masked text only; skip audio of flagged calls by default | Depends on the Drive design — hand the flag to that session | Phase 2 |
| Android Auto Backup | Exclude `callscribe.db` from cloud backup (or the whole `database` domain) and rely on the Drive feature for backups | One XML change; users lose transcripts on phone migration unless Drive backup exists → coordinate | Phase 1 (decision D4) |
| Shorter retention for flagged calls | e.g. trash audio after N days even if the policy says keep | Reuses `RetentionWorker` | Phase 2, opt-in |
| Per-policy option | "Sensitive calls: mask / skip AI / don't process" per caller group or contact (e.g. bank, health fund) | Policies are being redesigned in another session — add as one column there later | Phase 3 |
| **Audio redaction (our copy)** | Mute spans in the compressed upload / an exported copy. Spans from segment timestamps (coarse, several seconds) or `word_timestamps: true` on RunPod (ivrit.ai's handler supports it; we send `false` today) or Zipformer token times | `AudioCompressor` already decodes to PCM and re-encodes to AAC, so zeroing PCM ranges is ~20 lines and adds no extra pass. Pad ±0.5 s. | Phase 3 (with screening pass) |
| Audio redaction (Samsung's original) | Replace Samsung's file with a muted copy | We don't own it: needs `createWriteRequest` user consent per file, changes the content hash (our identity key!), surprises users of Samsung's player | **No.** Offer "delete recording" (trash, recoverable 30 days) instead |
| **User review** | Banner on the call page: "This call may contain a card number" → **Keep as is** / **Mask** / **Delete recording** | Small UI | Phase 2 |

---

## 4. False positives / negatives, ASR errors, UX

**False positives**
- Phone numbers: Israeli mobiles are 10 digits starting `05`, landlines 9 digits starting `0[2-489]`, `1-800`/`*1234`
  service numbers. A 9-digit `0…` run passes the ID check 10% of the time → IDs **require a cue**; any run that
  looks like a phone number *and* has a phone cue (טלפון, נייד, תתקשר, מספר שלי) is not an ID.
- Prices/amounts (שקל, ש"ח, אלף, אחוז, תשלומים), dates (בינואר, תאריך, years 19xx/20xx), times (בשעה), addresses
  (רחוב, דירה), order/tracking numbers (מספר הזמנה, משלוח — these are often fine to keep) → negative cues veto.
- The *Samsung screening assistant* says "please tell me the reason of your call" — no digits; fine.
- Expected: Luhn + IIN + ≥15 digits gives very few false positives; cue-based kinds are noisier. Masking a
  harmless 6-digit order number costs little; nagging costs a lot → masking is silent, review is optional.

**False negatives**
- ASR drops or merges digits → checksum fails → cue rule is the fallback (mask the whole run near a cue,
  checksum or not).
- Number split by a long pause or the other speaker → join across segments if gap < 2 s.
- Passwords spoken as words or spelled in English letters ("אה בי סי") → only cue + next-N-tokens; low recall.
  Say so in the UI text ("CallScribe hides most card, ID and code numbers it recognises — not everything").
- Secrets read by the *user* to a legitimate business are as sensitive as ones requested by a scammer — same handling.

**UX that doesn't nag**
- No extra notification for detection. A small 🔒 chip on the call row; masked text looks like `••••`.
- Review banner shows once per call on the detail page, dismissed by any choice; no review queue nagging.
- One setting group *Privacy → Sensitive numbers* with: Hide in transcripts (on) · Hide from AI (on, locked on
  for Gemini free tier?) · Webhook/Drive for flagged calls (skip) · Delete audio of flagged calls after N days (off).
- The onboarding session adds a rule that settings changes update onboarding: add one onboarding line
  ("Card, ID and code numbers are hidden before anything is sent to AI").

---

## 5. Recommended phased approach

**Phase 0 — close existing leaks (no detection needed, ~½ day)**
1. Exclude `callscribe.db` from Android cloud backup / device transfer (decision D4; coordinate with Drive backup).
2. Notifications: `VISIBILITY_PRIVATE` with a public version for all call results (cheap, protects every call).
3. Stop putting model output in `ProviderError` messages shown in notifications.
4. README Privacy: say plainly that secrets spoken in calls reach RunPod and the AI provider, and that the
   Gemini free tier may use transcripts for training.

**Phase 1 — smallest safe step: detect + redact after transcription (~2–3 days)**
1. `privacy/HebrewNumberNormalizer.kt`, `privacy/SensitiveDetector.kt` (pure Kotlin; returns spans with kind,
   char range, optional segment index/time), `privacy/Redactor.kt` (masking + AI placeholders).
2. In `ProcessWorker.transcribe` at `TranscriptionState.Done`: detect on the text, store the flag/spans, store
   masked text+segments (if D1 = mask).
3. In `ProcessWorker.analyze`: send the redacted text always (placeholders); add one prompt line telling the
   model never to repeat numbers and that placeholders mean redacted data; run the detector on the output.
4. Notifier: never show raw transcript for flagged calls.
5. 🔒 chip on the row; search keeps working on masked text.
6. A one-off "Scan existing transcripts" action (and on upgrade, background) because old rows were stored unmasked;
   store `sensitiveScanVersion` so improved detectors can re-scan.

**Phase 2 — user control & other sinks**
Review banner (keep / mask / delete recording), webhook skip, Drive integration (flag → masked text, no audio),
optional shorter audio retention for flagged calls.

**Phase 3 — before-upload protection (after on-device transcription lands)**
Screening pass with the on-device model → mute spans in the RunPod upload, or route flagged calls fully on-device;
per-policy "sensitive handling" once the policy redesign is merged; `word_timestamps` for tighter muting.

**Not planned**: LLM-based detection as the primary gate (it needs the text sent out), modifying Samsung's files,
storing encrypted originals.

### Data-model changes (Room)
Additive only, so the migration is a few `ALTER TABLE recordings ADD COLUMN`:
```kotlin
/** Comma-separated kinds found, e.g. "CARD,OTP"; null = not scanned, "" = scanned, nothing found. */
val sensitiveKinds: String? = null,
/** JSON [{kind, start, end, segIndex?, startSec?, endSec?}] — offsets only, never values. */
val sensitiveSpansJson: String? = null,
/** Detector version that produced the above (re-scan when it increases). */
val sensitiveScanVersion: Int = 0,
/** NONE | MASKED | USER_KEPT (user chose keep) */
val redaction: String = "NONE",
```
- Other sessions (policies redesign, keep-forever flag, AI tags, Drive backup) are changing `RecordingEntity`,
  `PolicyEntity` and the DB version **in parallel**. Do not pick a version number now: take
  `current version + 1` when implementing, rebase onto whatever landed, and keep this migration independent
  (only `ADD COLUMN`s with defaults) so the order doesn't matter.
- Settings go into DataStore (`Settings.kt`), no migration.
- Per-policy option (Phase 3) = one nullable column on the redesigned policy table.

---

## 6. Test strategy (synthetic data only — the repo is public)
- **Never** real secrets or real people's numbers. Fixture rules:
  - Cards: published test PANs (`4111 1111 1111 1111`, `5555 5555 5555 4444`, `3782 822463 10005`).
  - IDs: generate in the test from a `0000xxxx` prefix + computed check digit (valid checksum, never issued).
  - IBAN: the ISO example `IL62 0108 0000 0009 9999 999`.
  - Bank accounts, codes, passwords: obviously fake (`12-345-678901`, `482193`, `סיסמה: חתול-ירוק-7`).
- `HebrewNumberNormalizerTest`: table of spoken → digits (genders, construct forms, ו prefix, pairs, mixed,
  across punctuation), and the char-range mapping round-trips.
- `SensitiveDetectorTest`: ~40 synthetic Hebrew transcript snippets in the style of ivrit.ai output *and*
  Zipformer output (no punctuation, number words). Positives per kind; negatives: phone numbers, prices
  ("אלף מאתיים ושלושים שקל"), dates, times, order numbers, addresses, a screened-call greeting.
- **ASR-noise mutations** (property-style): drop/duplicate one digit, split a word, swap שתיים/שניים, insert אה —
  assert the cue fallback still masks the run.
- Report precision/recall on the fixture corpus in the test output; fail the build if recall on checksum kinds
  drops below the set bar.
- `RedactorTest`: masks text and segments consistently; AI placeholder text contains no digit of the secret.
- `ProcessWorker` integration via the existing fake RunPod server (`tools/`, debug hooks): a fake job returns a
  transcript with a test card → assert DB row is masked, the AI request body (fake analyzer / OkHttp
  interceptor) contains no digits of it, and the notification text is the public version.
- Optional manual check: record a test call reading a test PAN and a fake ID aloud, run it through RunPod and
  Zipformer, add the (synthetic) transcripts as fixtures.

---

## 7. Coordination with parallel sessions
- **On-device transcription**: Phase 3 depends on it; detector must handle Zipformer's number words.
- **Drive backup**: should read `sensitiveKinds` and back up masked text / skip flagged audio; and decide with
  us whether Android Auto Backup of the DB is turned off (D4).
- **Policies redesign**: leave room for a per-policy "sensitive handling" field.
- **Onboarding**: new privacy setting ⇒ onboarding text update.
- **AI tags**: the analyzer schema is changing there; add the "never repeat numbers" prompt line on top of it.

## 8. Open decisions for the user
- **D1 — local storage**: mask secrets in the stored transcript (recommended; digits are lost, audio still has
  them) or keep them locally and only redact what leaves the phone?
- **D2 — AI for flagged calls**: send a redacted transcript (recommended) or skip AI analysis entirely for flagged calls?
- **D3 — Gemini free tier**: force redaction on (no opt-out) when the provider may train on prompts?
- **D4 — Android Auto Backup**: stop backing up the DB to Google (recommended; transcripts then survive a phone
  change only via the Drive backup feature) or keep it?
- **D5 — audio**: is "delete recording" in the review banner enough, or do you want muted copies (Phase 3) for
  webhook/Drive?
- **D6 — screening pass**: once on-device transcription exists, run it before every cloud upload (more battery,
  protects RunPod) or only for chosen caller groups?
