# Date filter for the call list

**Goal:** narrow the call list by call date, AND-combined with the filter chips and the search bar.

**Logic (pure, unit-tested)** — `ui/DateFilter.kt`
- `DayRange(start, end)`: inclusive `LocalDate`s. `contains(callTimeSec, zone)` converts the epoch seconds to a local date in the given zone, so the whole first and last day are included.
- Presets (`DatePreset`): Today, Last 7 days (today and the 6 before), Last 30 days, This year (Jan 1 .. today). Resolved against `LocalDate.now(zone)`.
- Custom range: the Material3 `DateRangePicker` returns UTC-midnight millis. They are converted via `Instant.atZone(UTC).toLocalDate()` (not the phone zone, which would shift the day west of UTC), then treated as local calendar days.

**UI** — `CallListScreen.kt` (small hook only)
- A "Date" chip at the end of the existing scrolling chips row opens a dropdown: the four presets + "Custom range…" (+ "Any date" when active). Custom opens a `DatePickerDialog` containing `DateRangePicker`.
- When active, the chip shows the range (e.g. "Last 7 days" / "3 Nov – 9 Nov") with a close icon to dismiss.
- Header: "N recordings · …" unchanged; when any filter/search/date narrows the list it becomes "showing X of N · …".
- State is `rememberSaveable`; no DB changes. Layout uses start/end semantics, so it is RTL-safe.

**Tests** — preset boundaries, inclusive edges at 00:00 / 23:59:59, custom UTC-millis conversion (incl. non-UTC zones like Asia/Jerusalem and America/Los_Angeles), single-day range, year boundary.
