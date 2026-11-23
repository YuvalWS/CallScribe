# Keep forever (per-call flag)

**Goal:** let the user mark a single call "keep forever"; no retention policy may ever trash its audio or delete its transcript/analysis.

**Data:** `RecordingEntity.keepForever: Boolean = false`. Room DB v2 → v3 via `MIGRATION_2_3`
(`ALTER TABLE recordings ADD COLUMN keepForever INTEGER NOT NULL DEFAULT 0`); schema `3.json` exported.
A dedicated `RecordingDao.setKeepForever(id, value)` query updates only that column, so it can't clobber concurrent pipeline writes.
*Merge note:* other branches also bump the version / add migrations — reconcile at merge.

**Retention:** `Retention.plan` skips rows with `keepForever` for both audio and text. Unit tests in `RetentionTest` cover audio, text, and a mixed list.

**UI:** a toggle `IconButton` (bookmark outline / filled; content description "Keep forever" / "Kept forever") on each call-list row next to the Transcribe button (its own click, so the row doesn't open). The same toggle plus a one-line explanation on the call page. Optional "Kept" filter chip only if trivial.

**Verify:** `:app:testDebugUnitTest :app:assembleDebug`; brief look on the test phone, no data changes.
