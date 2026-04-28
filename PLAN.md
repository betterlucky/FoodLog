# FoodLog Phase 1 Plan

## Summary

FoodLog is a private, local-first native Android food logging app in Kotlin. The app should feel like a small chat-style food log, but the source of truth is a deterministic local Room database, not the rendered chat UI, previous conversational text, or exported CSV.

Phase 1 proves the local data path:

- user submits text
- raw text is saved immediately
- deterministic local parsing resolves known shortcuts
- consumed items are stored as structured database rows
- today view and CSV exports are rendered from Room rows only

The sample file `food_log_full_2026-04-04_to_2026-04-23.csv` is retained as synthetic fixture data and as the legacy health-monitor CSV export contract. It should not contain real personal food history.

## Phase 1 Requirements

Use:

- Kotlin
- native Android
- Jetpack Compose
- Room
- Coroutines and Flow
- MVVM-style structure
- local-only storage

Do not implement in Phase 1:

- OpenAI calls
- backend/server integration
- nutrition-label photo extraction
- leftovers
- corrections
- product matching
- conversational summaries

Core behavior:

- Save every submitted text as `RawEntryEntity` before interpretation.
- Store consumed foods only as `FoodItemEntity` rows generated from deterministic local logic.
- Unsupported inputs become pending raw entries and create no food item.
- Use a repository/database transaction for raw-entry insertion, parsing/default resolution, food-item insertion, and raw-entry status update.
- Raw entries are audit records and should not be deleted in normal app flows.
- Totals and exports use active `FoodItemEntity` rows.
- User-facing removal is called `Remove` and hard-deletes the selected `FoodItemEntity`.
- Removing a food item does not delete the associated `RawEntryEntity` audit record.
- Pending entries can be removed before resolution when the user chooses to enter the food another way; this hard-deletes only unresolved `RawEntryEntity` rows.

Seed tea as the first `UserDefaultEntity`:

- `trigger = "tea"`
- `name = "Tea"`
- `calories = 25`
- `unit = "cup"`
- `notes = "English tea with skimmed milk and half a teaspoon of sugar"`
- `source = USER_DEFAULT`
- `confidence = HIGH`

Tea is the first example of the shortcut/default mechanism, not one-off hardcoded business logic. Similar compound items such as cranberry mix should eventually use the same default mechanism; until configured, they remain pending.

## Parsing And Dates

Implement a small deterministic parser for Phase 1.

Normalization:

- trim leading/trailing whitespace
- collapse repeated whitespace
- match case-insensitively
- do not fuzzy-match arbitrary text

Supported shortcut phrases:

- `tea`
- `a tea`
- `cup of tea`
- `1 tea`
- `100g sourdough with thin butter` when `sourdough with thin butter` exists as a per-gram shortcut/default
- `2 slices sourdough` when `sourdough` exists as a per-slice shortcut/default

Supported date prefixes:

- `today tea`
- `yesterday tea`
- `YYYY-MM-DD tea`

Supported time prefixes/suffixes:

- `1pm tea`
- `13:00 tea`
- `tea at 1pm`
- `tea 13:00`

Supported quantity behavior:

- Plain numeric prefixes such as `2 teas` multiply the saved shortcut/default amount and calories.
- Gram prefixes such as `100g sourdough` and `100 g sourdough` map to `amount = 100`, `unit = g`, and multiply per-gram shortcut/default calories.
- Unit quantity prefixes such as `2 slices sourdough` map to `amount = 2`, `unit = slice`, and multiply per-unit shortcut/default calories.

Supported compound shortcut behavior:

- Split obvious meal entries on commas, plus signs, ampersands, slashes, semicolons, and the word `and`, for example `banana, satsuma and tea` or `150g sourdough + tea`.
- Resolve each part through the same shortcut/default mechanism.
- If every part resolves, save one raw entry and create one `FoodItemEntity` per part.
- If any part is unsupported, keep the whole raw entry pending and create no food rows.
- Date prefixes apply to every resolved part in the compound entry.

Date behavior:

- Date prefixes set `RawEntryEntity.logDate` and `FoodItemEntity.logDate`.
- If a date prefix is present but the remaining phrase is unsupported, save a pending raw entry using the parsed `logDate`.
- `createdAt` is always the actual timestamp when the entry is saved.
- Explicit time prefixes/suffixes set `RawEntryEntity.consumedTime` and `FoodItemEntity.consumedTime`.
- `consumedTime` defaults to the current local time when no explicit time is present.
- Pending review pre-fills any deterministic parser hints it can extract, including item text, amount/unit, and consumed time.
- Pending review lets the user edit the consumed time before resolving the row.
- `logDate` defaults to the current local date when no supported date prefix is present.
- The Today screen blocks free-text entries whose parsed date does not match the selected date; users should navigate to the intended date before adding prior-session entries.

Examples:

| Input | Result |
| --- | --- |
| `tea` | parsed raw entry and one Tea food item for today |
| `a tea` | parsed raw entry and one Tea food item for today |
| `cup of tea` | parsed raw entry and one Tea food item for today |
| `1 tea` | parsed raw entry and one Tea food item for today |
| `1pm tea` | parsed raw entry and one Tea food item at 13:00 |
| `tea at 13:00` | parsed raw entry and one Tea food item at 13:00 |
| `yesterday tea` | parsed raw entry and one Tea food item for yesterday |
| `2026-04-23 tea` | parsed raw entry and one Tea food item for 2026-04-23 |
| `yesterday curry` | pending raw entry for yesterday, no food item |
| `cranberry mix` | pending raw entry for today, no food item |

## Data Model

Define Room entities and DAOs for:

- `RawEntryEntity`
- `FoodItemEntity`
- `ProductEntity`
- `ProductPhotoEntity`
- `ContainerEntity`
- `UserDefaultEntity`

Keep product, photo, and container support minimal in Phase 1. Database setup and DAOs are enough; no UI or behavior is required for those concepts yet.

`CorrectionEntity` is a later-phase TODO unless it is trivial to include as an inert placeholder. Phase 1 must not implement correction behavior.

Required converters:

- `Instant`
- `LocalDate`
- `LocalTime`
- enums

Android compatibility:

- Require API 26+, or enable Java time desugaring for `java.time` support.

Recommended high-level package structure:

```text
app/src/main/java/.../foodlogger/
  data/
    dao/
    db/
    entities/
    repository/
  domain/
    export/
    parser/
    totals/
  ui/
    today/
    components/
  util/
```

Keep parsing, export generation, and totals outside composables.

## UI Scope

Create a mobile-first Compose Today screen with:

- current selected date
- chat-style text input
- submit/log button
- shortcut picker opened from the input area
- today's logged food items
- today's calorie total
- pending entries section
- daily-close/export status and action
- logged item edit/remove controls
- daily weight control near the close-day/export section

The UI should render from Room-backed state. It must not parse visible tables, chat bubbles, or exported CSV back into canonical data. Direct manual add is not exposed in the main UI; known-calorie manual resolution should happen through pending/staged resolution flows.

Date and time input behavior:

- The selected date control opens a calendar picker with Material's calendar/text-entry mode toggle.
- Time fields remain editable as text and also offer a picker action.
- Time text fields should use the shared time parser, so typed values such as `13:45`, `1pm`, and `8:05am` behave consistently with free-text log parsing.

Current shortcut behavior:

- Shortcuts are opened from a button near the food input instead of listed inline in the daily scroll.
- The shortcut picker supports search/filtering.
- Tapping a shortcut logs it immediately and keeps the picker open for multi-item meals.
- The picker includes edit, forget, and add-shortcut actions.

Current logged-item behavior:

- Logged food items can be edited from the Today screen.
- Logged food items can be viewed by time, by calories, or in a clumped view that groups identical rows while preserving access to individual edits.
- Editing supports item name, amount, unit, calories, consumed time, and notes.
- If edited calories are present, the edit is treated as a single manual row update.
- If edited calories are blank, FoodLog re-runs deterministic parsing on the edited item text:
  - if every parsed part resolves to active shortcuts/defaults, the original row is replaced with one default-derived row per part
  - if some parts resolve and some do not, the edit dialog stages the recognised parts in memory and asks for only the unresolved parts one at a time
  - each manually completed staged part can be saved as a shortcut/default for future entries
  - the original row is not replaced until every part has been completed; cancelling the dialog leaves the original logged row unchanged
- The edit dialog clears the prefilled calories when the item name changes unless the user has manually edited the calories field.
- Default-reparse edits reuse the original row's raw entry and date, reuse the edited time for every replacement row, and update the raw entry text.
- Consumed time is required for edited food rows; users may type supported time text or use the time picker.
- Logged food items can be removed after confirmation.
- Removing a logged item hard-deletes the `FoodItemEntity`; the raw entry remains.

Current pending-entry behavior:

- Pending food entries can be manually resolved by the user from the Today screen.
- Pending rows and review dialogs show parsed time separately from the food text when an explicit time was entered.
- Compound pending entries use the same itemised staged resolver as logged-item edits when any part already matches a shortcut/default.
- Recognised staged parts are held in memory; unresolved parts are completed one at a time and can be saved as shortcuts/defaults.
- Choosing `Keep pending` or dismissing the staged resolver leaves the original pending entry unchanged so Resolve can restart the staged flow later.
- The pending-entry `Save as shortcut` checkbox is disabled and unchecked while calories are blank.
- The pending-entry dialog has one `Save` action:
  - if item name and valid calories are present, it resolves the entry into a food row
  - if calories are blank, it re-runs deterministic parsing on the edited text
  - if that parser pass now resolves fully, it creates food rows from shortcuts/defaults
  - if that parser pass still cannot resolve fully, it saves edited text/notes back to the pending queue
- Manual resolution requires at least an item name and calories.
- Manual resolutions create `FoodItemEntity` rows with `source = MANUAL_OVERRIDE` and `confidence = HIGH`.
- Resolving a pending entry marks the associated raw entry as `PARSED` without deleting the audit record.
- Leaving an entry pending is the "keep pending" behavior; it remains unresolved and excluded from food exports until handled.
- Removing a pending entry hard-deletes the unresolved raw entry and creates no food rows.
- Manual resolutions can optionally be saved as reusable user defaults so future matching inputs can be logged deterministically.
- Saved defaults represent calories per unit. If the user resolves `2 slices` as `190 kcal`, the stored shortcut is `95 kcal` per `slice`.
- Active shortcuts can be reviewed from the Today screen, edited in place, or forgotten by soft-deactivating the default.
- Forgetting a shortcut requires confirmation.
- Tapping an active shortcut logs one serving for the selected day using the same raw-entry and food-row path as typing the shortcut trigger.

## CSV Export

Implement two CSV exporters from Room rows only.

Daily export is the primary workflow for Phase 1 and the near-term Health Monitor handoff. The user should be encouraged to export the day's report regularly rather than treating the export as an unbounded canonical log.

Exported CSV files are output artifacts, not source data. If food rows change after export, FoodLog should generate a fresh export from Room rather than editing an existing external CSV file.

FoodLog writes the Health Monitor CSV to:

```text
Downloads/FoodLogData
```

Health Monitor should import the standard daily file from that subfolder.

Daily export status is tracked locally with a date-keyed status row:

- `legacyExportedAt` is updated when the standard Health Monitor CSV is exported.
- `legacyExportFileName` records the generated Health Monitor CSV filename.
- `auditExportedAt` is updated when the audit CSV is exported.
- `auditExportFileName` records the generated audit CSV filename.
- `lastFoodChangedAt` is updated when confirmed food rows are added, edited, manually resolved, or removed.
- The Today screen shows whether each export has happened for the selected date.
- The Today screen shows when food rows changed after the last export.
- The Today screen labels the primary handoff as `Health Monitor` while preserving the standard Health Monitor CSV contract.
- The Today screen highlights pending entry count before export.
- The Today screen shows a daily close readiness summary:
  - `No food logged` when the selected day has no confirmed food rows, no daily weight, and no pending entries.
  - `Resolve pending entries` when unresolved raw entries remain for the selected day.
  - `Ready to export` when the selected day has confirmed food rows or a daily weight and the Health Monitor export is missing or stale.
  - `Already exported` when the selected day has confirmed food rows or a daily weight and the Health Monitor export is current.
- Future ongoing-log append mode should use a separate append ledger so already-appended food rows are not duplicated.
- FoodLog has a persisted day-boundary setting foundation:
  - `null` day boundary means normal calendar-day logging.
  - A configured boundary such as `03:00` means unprefixed entries before that time are assigned to the previous food day.
  - Explicit date prefixes such as `today`, `yesterday`, and `YYYY-MM-DD` override the default food-day fallback.
  - The Today screen starts on the current food day when the app opens.
- The Today screen exposes the day-boundary setting with:
  - a compact boundary status row
  - an early-morning boundary toggle
  - a boundary time field with text entry and picker support, defaulting to `03:00` when enabled
- The Today screen shows a daily close prompt:
  - no export needed when the selected day is empty
  - resolve pending entries before Health Monitor export when pending entries remain
  - export the Health Monitor CSV when confirmed rows or a daily weight exist and the Health Monitor export is missing or stale
  - confirm the Health Monitor export is current after export
- Daily close is the only main-screen Health Monitor export action; avoid showing a second standalone export button.
- The audit exporter is retained for developer/data tracing, but is not shown on the main Today screen.

### Daily Weight

Daily weight is optional daily metadata for Health Monitor handoff:

- Store one `DailyWeightEntity` per `logDate`.
- Store the canonical value as kilograms (`weightKg`).
- The initial UI accepts stone and pounds because that is the preferred input format for now.
- The user can add or edit the selected day's weight from the Today screen.
- `measuredTime` is always stored; blank UI time falls back to the current local time.
- Daily weight is not a `FoodItemEntity` and never contributes to calorie totals.
- Saving or editing weight marks the day as changed for export readiness.
- The Health Monitor export emits weight as a timestamped row with:
  - `item = weight`
  - `quantity = {weightKg} kg`
  - blank `calories_kcal`
  - `notes = Recorded weight`
- Future polish can add a setting to hide weight logging for users who do not want it.

### `LegacyHealthCsvExporter`

This exporter matches the retained sample CSV and is the Phase 1 health-monitor handoff format.

Header:

```csv
date,time_local,item,quantity,calories_kcal,notes
```

Mapping:

- `date` = `FoodItemEntity.logDate`
- `time_local` = `FoodItemEntity.consumedTime`, blank when null
- `item` = `FoodItemEntity.name`
- `quantity` = amount plus unit when available, otherwise unit or blank
- `calories_kcal` = calories formatted deterministically
- `notes` = notes or blank
- Optional daily weight rows use `date = DailyWeightEntity.logDate`, `time_local = measuredTime`, `item = weight`, `quantity = {weightKg} kg`, blank calories, and `notes = Recorded weight`.

Generated filename:

```text
food_log_YYYY-MM-DD.csv
```

### `AuditCsvExporter`

This richer export preserves provenance for debugging and future import paths.

Header:

```csv
log_date,consumed_time,item,amount,unit,grams,calories,source,confidence,product,notes,raw_entry_id,created_at
```

Rules for both exporters:

- Export food data from Room `FoodItemEntity` rows only.
- Export optional daily weight from Room `DailyWeightEntity` only.
- Export currently active rows only.
- Sort by `logDate`, then `consumedTime`, then `createdAt`.
- Escape commas, quotes, and newlines correctly.
- Preserve blank times as blank CSV fields.
- Do not call AI during export.
- Do not use rendered UI text as export input.

Later export behavior should consider:

- a setting or toggle for appending exports to a long-term log file for users who want an ongoing personal ledger
- a daily export/reset reminder or status indicator
- a configurable day-boundary grace period so midnight or early-morning snacks can still be assigned to the previous daily report when appropriate
- clear handling of whether an exported day is considered closed, especially before any older-day editing is allowed

## Tests

Add focused tests for Phase 1 behavior:

- Default tea shortcut is seeded.
- `tea`, `a tea`, `cup of tea`, and `1 tea` create parsed raw entries and one food item.
- Tea food item has `25 kcal`, `source = USER_DEFAULT`, and `confidence = HIGH`.
- `today tea`, `yesterday tea`, and `YYYY-MM-DD tea` set the expected `logDate`.
- `1pm tea`, `13:00 tea`, `tea at 1pm`, and `tea 13:00` set the expected `consumedTime`.
- `1 tea` remains a quantity shortcut and does not parse as 01:00.
- UI submissions with an explicit date outside the selected date are blocked with a switch-date prompt message.
- `yesterday curry` creates a pending raw entry with yesterday's `logDate` and no food item.
- Unsupported input creates a pending raw entry and no food item.
- Compound shortcut input creates one raw entry and multiple food rows when all parts are known.
- Compound input with any unknown part stays pending and creates no food rows.
- Plus-separated compound pending input stages recognised parts separately, for example `150g sourdough with thin butter + tea`.
- Per-gram shortcuts scale calories correctly for different gram amounts.
- Per-unit shortcuts scale calories correctly for different parsed unit amounts, for example `2 slices sourdough`.
- Single pending review for `13:45 10g fruit and nut mix` pre-fills `fruit and nut mix`, `10 g`, and `13:45`.
- Manual pending resolution can override the parsed consumed time.
- Pending entries can be removed with a hard delete while unresolved.
- Daily total reflects active food rows.
- Logged item manual edits update totals and exports.
- Logged item blank-calorie edits can reparse known shortcuts/defaults into replacement food rows.
- Logged item removal deletes the food row and updates totals/exports.
- Daily weight can be saved, edited, exported as a `weight` row, and does not affect calorie totals.
- `LegacyHealthCsvExporter` header matches the sample CSV exactly.
- `AuditCsvExporter` header matches the rich schema exactly.
- CSV export includes active rows, handles blank times, and escapes commas/quotes/newlines.
- Repository submit flow keeps raw and food rows consistent.

Prefer local unit tests for parser, export, and totals logic. Use instrumented tests only where Room or Android framework behavior makes that necessary.

## Later Phase TODOs

Later phases may add:

- richer shortcut/default management screens
- structured OpenAI text parser behind an interface
- nutrition-label photo extraction
- product review and confirmation screens
- product matching with stale-cache protection
- active leftover/container handling
- correction handling with audit trail
- conversational day summaries from database-derived context
- direct health-monitor integration beyond CSV export
- optional long-term append-log export mode
- daily close/export reminders with a midnight or early-morning grace period

Future AI features must query structured database state first. They must not infer the day's food from chat history.

### Later AI Service Architecture

Backend-backed AI remains out of scope until the local-first app works well for daily personal use. If FoodLog later adds hosted AI features for release:

- Keep food logs local by default; do not store personal food logs on the backend.
- Use a thin backend proxy so API keys never ship in the Android app.
- Sign app requests and verify signatures before doing paid work.
- Consider Play Integrity attestation as a later hardening layer for release builds.
- Rate-limit by anonymous install ID, IP, and request class before calling paid providers.
- Cache normalized requests and structured results before calling an AI provider.
- Prefer deterministic routes first: shortcut/defaults, local history, barcode/product lookup, nutrition databases, and on-device OCR.
- Use AI only as a fallback for ambiguous text, label parsing failures, or richer review/summarization.
- Require strict JSON responses; never parse markdown tables or prose as the app contract.
- Version the request and response schema so cache entries can be invalidated safely.
- Treat AI calories as estimates with confidence and provenance, requiring review when confidence is not high.

Initial hosting preference for a thin proxy/cache is Cloudflare Workers plus a small hosted key-value/cache store. Reconsider Railway, Fly.io, or a VPS only if the backend grows beyond simple request verification, caching, routing, and provider calls.

## Assumptions

- `25 kcal` is the Phase 1 seeded tea default.
- Historical sample rows with `18 kcal` tea entries are legacy data and do not override the new seed default.
- Shortcut/default editing can wait until after Phase 1.
- The sample CSV is context for the legacy export contract, not the new internal schema.
- The app should remain convenient, but never silently wrong: use deterministic shortcuts when known, otherwise save pending entries for review.
