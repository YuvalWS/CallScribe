# Policies v3 — customizable without contradictions

Goal: rules per caller type, per contact group (phone / Google Contacts), per AI relationship tag and per contact,
with separate phone and cloud (Drive) retention, and **no way to build contradicting rules**.

## Model

One **policy** = one row for one **target**. A target is exactly one of:

| Target | Matches a call when | Example |
|---|---|---|
| **Contact** | the call's name or number (last 9 digits) equals the policy's | "Mom", "…541234567" |
| **Contact group** | the caller is a contact in that group of the phone's address book (Samsung groups and Google Contacts labels both appear as Android contact groups) | "Family", "Work" |
| **Tag** | the call's AI relationship tag equals the policy's tag (`family_friends`, `work`, `business_suppliers`, `other`) | "Work calls" |
| **Caller type** | the call's caller type: Contacts, Unknown numbers, Private / hidden, Spam or fraud | "Spam or fraud" |
| *Built-in default* | nothing else matched (only if a caller-type row is missing) | — |

Caller types are a simplification of Samsung's labels for policies (the call list still shows the exact label):
- Samsung **spam** and **fraud** are one caller type, *Spam or fraud*.
- Samsung's **other labels** (telemarketing, robocall, survey, business) have no caller type of their own; those
  calls are numbers that aren't in contacts, so they follow *Unknown numbers*.

Every policy has the same fields:

| Field | Values | Default |
|---|---|---|
| Transcribe automatically | on / off | on, except Contacts |
| Summarize & judge spam | on / off (needs transcribe) | same as transcribe |
| Keep recording on phone | forever / N days | forever |
| Keep transcript & summary | forever / N days — opt-in | forever |
| Keep in cloud backup | forever / N days / **same as phone** | same as phone |

## Precedence (fixed, documented, shown in the app)

**Contact > Contact group > Tag > Caller type > Built-in default.**
The single most specific matching policy wins **as a whole** — fields are never merged across policies. So a
call is always governed by exactly one row, and the call's page can name it.

A contact group is your own curation, so it beats the AI's guess (tag).

**A contact in several groups:** group policies have a priority order the user sets (up/down arrows in the
editor). The highest-priority matching group wins; the call page says which other groups it is in, e.g.
*Contact group "Family" (also in Work, lower priority)*.

On top of that, a recording marked **Keep forever** is never deleted by retention (phone, text or cloud),
whatever its policy says. Processing (transcribe/summarize) still follows the policy.

Why no field-by-field merge: merging is what creates "contradictions" ("contact says keep 7 days, group says
transcribe, which wins for the summary?"). Whole-row wins is one sentence to explain, and the editor copies the
current effective policy when you create a more specific one, so you start from what already applied.

### Timing
- Contact groups are known when the call arrives, so they drive auto-transcribe/summarize and retention.
- The AI tag is only known after a call has been analyzed: auto-processing of a new call uses
  Contact > Contact group > Caller type; retention (daily) uses the full order. Tag policies therefore only show
  retention fields in the editor. Until recordings carry a tag (another branch adds it), the tag target exists in
  the model, DB and resolver, but the editor hides the tag section.

### Contacts permission
Reading groups needs `READ_CONTACTS` (everything stays on the phone). It is asked only when the user adds the
first group policy. Without it, group policies are shown with a note and don't apply; everything else works as
before (adding a single contact keeps using the permission-free picker).

## Cloud retention
Drive backup is not built yet (PLAN §4c). The setting is stored and shown ("applies when Drive backup is on"),
and `Retention.plan` already computes `cloudAudio` / `cloudText`: backup copies past their cloud period.
- **Same as phone**: the cloud copy of the recording goes when the phone recording is due; the cloud copy of the
  text goes when the phone text is due. With phone "forever" it is displayed as *Same as phone (forever)*.
- **N days**: both cloud copies go after N days.
- **Forever**: never removed from the cloud.

## Validation: warn, don't block
The editor shows soft warnings under a policy for odd combinations (`PolicyChecks.warnings`, unit-tested):
- Recording kept N days but not transcribed → "Recordings will be deleted without a transcript."
- Text deleted before the recording → "The transcript goes before the recording." (allowed)
- Cloud N days shorter than the phone recording period → "The cloud copy expires before the phone copy."
- Summarize is forced off when transcribe is off (that one *is* enforced: it cannot work).

## Explaining the decision
The call page shows which policy applied and why (`PolicyResolver.explain`), the effective values, and, if the
call is marked Keep forever, that this overrides retention.

## Data & migration (DB v2 → v3)
`policies` keeps its columns (`groupKind` = caller-type target, `displayName`/`numbers` = contact target) and gains:
- `tag TEXT` — tag target,
- `contactGroup TEXT` — contact-group target, matched by group title (so "Family" matches in any account),
- `priority INTEGER NOT NULL DEFAULT 0` — order among contact-group policies,
- `cloudRetentionDays INTEGER NOT NULL DEFAULT -2` (-1 forever, -2 same as phone, N days).

Existing policies: all kept with their values, cloud = "same as phone". Caller types:
- `SPAM` and `FRAUD` merge into `SPAM_FRAUD`, **keeping the more protective value of each field** (transcribe or
  summarize if either did; the longer retention, forever if either was forever), so the merge never deletes
  anything sooner than before.
- `OTHER_LABEL` is removed; those calls follow *Unknown numbers* from now on.

The SQL lives in `PolicyMigrations` and is unit-tested on SQLite (JDBC).
**Merge note:** the keep-forever and AI-tag branches also bump to v3 — reconcile versions/migrations at merge.

## Hooks for the other branches
- `RecordingEntity.keptForever()` and `RecordingEntity.aiTag()` in `rules/RuleEngine.kt` return `false`/`null`
  today. When `keepForever` / the tag column land, change those two one-liners and flip `TAG_POLICIES_ENABLED`.

## Examples
1. **Spam, unknown numbers short, family forever.** *Spam or fraud*: transcribe + summarize, keep recording
   7 days, cloud same as phone → gone from phone and cloud after 7 days. Group *Family*: don't transcribe, keep
   forever, cloud forever.
2. **One contact overrides its group.** Group *Work* = transcribe, keep 30 days; contact *Boss* = transcribe,
   keep 1 year, cloud forever. A call from the boss follows *Boss* (contact beats group).
3. **Two groups.** Dana is in *Family* (priority 1) and *Work* (priority 2). Her calls follow *Family*; her call
   page says "also in Work, lower priority". Moving *Work* up switches her to *Work* — one place to change it.
4. **Tag + keep forever.** Tag *work* = keep recording 30 days, cloud 365 days. A work call from an unknown number
   is transcribed per *Unknown numbers*, tagged *work* by the AI, then its retention follows *work*. Starring it
   as Keep forever means it is never deleted anywhere.
