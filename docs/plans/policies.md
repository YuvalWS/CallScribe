# Policies v3 — customizable without contradictions

Goal: rules per caller type, per AI relationship tag and per contact, with separate phone and cloud (Drive)
retention, and **no way to build contradicting rules**.

## Model

One **policy** = one row for one **target**. A target is exactly one of:

| Target | Matches a call when | Example |
|---|---|---|
| **Contact** | the call's name or number (last 9 digits) equals the policy's | "Mom", "…541234567" |
| **Tag** | the call's AI relationship tag equals the policy's tag (`family_friends`, `work`, `business_suppliers`, `other`) | "Work calls" |
| **Caller type** | the Samsung caller type (`LabelKind`) equals the policy's | "Unknown numbers", "Labelled spam" |
| *Built-in default* | nothing else matched (only if a caller-type row is missing) | — |

Every policy has the same fields:

| Field | Values | Default |
|---|---|---|
| Transcribe automatically | on / off | on, except Contacts |
| Summarize & judge spam | on / off (needs transcribe) | same as transcribe |
| Keep recording on phone | forever / N days | forever |
| Keep transcript & summary | forever / N days — opt-in | forever |
| Keep in cloud backup | forever / N days / **same as phone** | same as phone |

## Precedence (fixed, documented, shown in the app)

**Contact > Tag > Caller type > Built-in default.**
The single most specific matching policy wins **as a whole** — fields are never merged across policies. So a
call is always governed by exactly one row, and the call's page can name it.

On top of that, a recording marked **Keep forever** is never deleted by retention (phone, text or cloud),
whatever its policy says. Processing (transcribe/summarize) still follows the policy.

Why no field-by-field merge: merging is what creates "contradictions" ("contact says keep 7 days, group says
transcribe, which wins for the summary?"). Whole-row wins is one sentence to explain, and the editor copies the
current effective policy when you create a more specific one, so you start from what already applied.

### Tags and timing
The AI tag is only known after a call has been analyzed. So:
- **Auto-transcribe/summarize of a new call** is decided from Contact > Caller type (the tag is unknown yet).
  Tag policies therefore only show retention fields in the editor.
- **Retention** (daily) uses the full order Contact > Tag > Caller type.

Until recordings carry a tag (another branch adds it), the tag target exists in the model, DB and resolver, but
the editor hides the "Relationship tags" section and calls resolve with tag = null.

## Cloud retention
Drive backup is not built yet (PLAN §4c). The setting is stored and shown ("applies when Drive backup is on"),
and `Retention.plan` already computes `cloudAudio` / `cloudText`: backup copies past their cloud period.
- **Same as phone**: the cloud copy of the recording goes when the phone recording is due; the cloud copy of the
  text goes when the phone text is due. With phone "forever" it is displayed as *Forever (same as phone)*.
- **N days**: both cloud copies go after N days.
- **Forever**: never removed from the cloud.

## Validation: warn, don't block
The editor shows soft warnings under a policy for odd combinations (`PolicyChecks.warnings`, unit-tested):
- Recording kept N days but not transcribed → "Recordings will be deleted without a transcript."
- Text deleted before the recording → "The transcript goes before the recording." (allowed)
- Cloud N days shorter than the phone recording period → "The cloud copy expires before the phone copy."
- Summarize is forced off when transcribe is off (that one *is* enforced: it cannot work).

## Explaining the decision
The call page shows: *"Policy: contact "Mom" (contact beats tag and caller type)"*, the effective values,
and, if the call is marked Keep forever, that this overrides retention. `PolicyResolver.explain` produces it.

## Data & migration (DB v2 → v3)
`policies` keeps its columns (`groupKind` = caller-type target, `displayName`/`numbers` = contact target) and gains:
- `tag TEXT` (nullable) — tag target,
- `cloudRetentionDays INTEGER NOT NULL DEFAULT -2` (-1 forever, -2 same as phone, N days).

Add-only migration: every existing policy is preserved with its values; cloud = "same as phone", which is exactly
what the user would get with backup today. The SQL lives in `PolicyMigrations` and is unit-tested on SQLite (JDBC).
**Merge note:** the keep-forever and AI-tag branches also bump to v3 — reconcile versions/migrations at merge.

## Hooks for the other branches
- `RecordingEntity.keptForever()` and `RecordingEntity.aiTag()` in `rules/RuleEngine.kt` return `false`/`null`
  today. When `keepForever` / the tag column land, change those two one-liners.

## Examples
1. **Spam, unknown numbers short, family forever.** Caller type *Labelled spam*: transcribe + summarize, keep
   recording 7 days, cloud same as phone. A spam call is gone from phone and cloud after 7 days.
   Caller type *Contacts*: don't transcribe, keep forever, cloud forever.
2. **One contact overrides.** Caller type *Contacts* = don't transcribe; contact *Plumber* = transcribe + summarize,
   keep recording 90 days, cloud forever. A call from the plumber is transcribed (contact beats caller type), its
   audio leaves the phone after 90 days but stays in Drive.
3. **Tag + keep forever.** Tag *work* = keep recording 30 days, text forever, cloud 365 days. A work call from an
   unknown number is transcribed per *Unknown numbers*, tagged *work* by the AI, and from then on its retention
   follows *work*. The user stars one important work call as Keep forever → it is never deleted anywhere.
