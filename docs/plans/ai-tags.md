# Plan: AI relationship tags (closed list)

User request: *"I want the AI tags to be a closed list: family and friends, work (colleagues and so on),
business and suppliers, and other. You can offer more tags, but I want to keep it short and simple and to be
able to use it for retention policy and searches."*

## The list

| key (stored / JSON) | English | Hebrew | Who |
|---|---|---|---|
| `family_friends` | Family & friends | משפחה וחברים | Relatives, partners, friends, neighbours — personal relationships |
| `work` | Work | עבודה | Colleagues, managers, employees, clients *of the user's job*, recruiters calling about the user's job |
| `business` | Business & suppliers | עסקים וספקים | Businesses the user personally deals with: suppliers, contractors, shops, landlord, tradespeople, service providers the user hired |
| `services` | Services & official | שירותים ומוסדות | Institutions: banks, credit cards, insurance, health funds/clinics, government & municipality, utilities, couriers/deliveries, appointment reminders |
| `other` | Other | אחר | Everything else: strangers, wrong numbers, spam/telemarketing, unclear or too-short calls |

### Decision: add exactly one tag — `services` ("Services & official")
One-line reason: banks, clinics, government and deliveries are a large share of real calls and need a different
retention than suppliers (often "keep the details until the appointment / delivery"), and without their own tag
they would split unpredictably between `business` and `other`, making policies and searches unreliable.

### Decision: no "spam" tag — spam stays the separate verdict
The tag answers *who is this to me?*; the verdict answers *is it unwanted?*. They are orthogonal (a real supplier
can call with a sales pitch; a stranger can be legitimate). A spam tag would duplicate the verdict and create two
sources of truth for policies. Spam / telemarketing / scam calls get `other`, and policies/filters keep using the
verdict + Samsung label for spam.

Total: 5 tags. Not configurable by the user (closed list keeps policies, search and the prompt stable).

## Data model
- `enum class Relationship(key, en, he)` in `data/Relationship.kt` with `fromKey()` (unknown/blank → `OTHER`)
  and search aliases. This is the **public API for policies**: `Relationship.entries`, `RecordingEntity.relationship`.
- `RecordingEntity.relationship: Relationship?` (null = not tagged yet), `relationshipManual: Boolean`.
- Room v2 → v3: `ALTER TABLE recordings ADD COLUMN relationship TEXT` and
  `ADD COLUMN relationshipManual INTEGER NOT NULL DEFAULT 0`. Existing analysed rows stay untagged until re-analysed
  (no back-fill guessing from `category`). **Other branches also migrate 2→3 — reconcile versions at merge.**
- Retention "delete transcript & summary" keeps the tag (it is metadata, not call content) so tag-based policies
  keep working after text expiry.

## AI schema / prompt (`providers/AnalysisPrompt.kt`, shared by Gemini, OpenRouter, Claude)
- New required enum property `relationship` with the five keys, plus a definition block in the system prompt
  that is aware of Hebrew calls (e.g. "אמא", "אחי", "מה נשמע חביבי" → family_friends; "הישיבה", "הלקוח", "הבוס",
  "המשמרת" → work; "ההזמנה שלך", "הצעת מחיר", "האינסטלטור" → business; "קופת חולים", "בנק", "שליח", "ביטוח לאומי"
  → services). Contact name is a hint ("אמא", "Dr. …", "Bank …").
- `Analysis.relationship` defaults to `"other"` so old/odd JSON still parses; mapped with `Relationship.fromKey`.

## Pipeline
`ProcessWorker.analyze` writes `relationship` unless `relationshipManual` is true (manual override wins over
re-analysis).

## UI (kept small — other sessions edit the list filters)
- Call list row: a small chip with the tag (English label).
- Call list: a second, separate row of tag filter chips (toggle; none selected = all). Combined with the existing
  filter and the search with AND.
- Search: matches the tag's key, English and Hebrew labels and aliases (e.g. "משפחה", "family", "colleagues", "בנק").
- Call page: tag shown in the analysis card, plus a "Tag" row of chips to pick a tag manually; when set manually,
  shows "set by you" and a "Use AI tag" button that restores the AI's tag from the stored `analysisJson`.
  Works before analysis too (manual tagging of untranscribed calls).

## Not in scope
Policy UI (the policy-redesign session will add tag-based targets using `Relationship` and
`RecordingEntity.relationship`).

## Tests
- Unit: `Relationship.fromKey` (unknown → other, case-insensitive), `AnalysisPrompt.parse` with/without/unknown
  relationship, schema contains the enum, `CallSearch` matches Hebrew/English tag names, migration SQL statements.
- Live (opt-in, `CALLSCRIBE_LIVE=1`): Gemini `gemini-3.5-flash-lite` with 4 short made-up Hebrew transcripts
  (family, work, supplier, other) — spaced for the 15 rpm free tier.
