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

Supported date prefixes:

- `today tea`
- `yesterday tea`
- `YYYY-MM-DD tea`

Date behavior:

- Date prefixes set `RawEntryEntity.logDate` and `FoodItemEntity.logDate`.
- If a date prefix is present but the remaining phrase is unsupported, save a pending raw entry using the parsed `logDate`.
- `createdAt` is always the actual timestamp when the entry is saved.
- `consumedTime` defaults to the current local time for parsed food items unless a later phase adds explicit time parsing.
- `logDate` defaults to the current local date when no supported date prefix is present.

Examples:

| Input | Result |
| --- | --- |
| `tea` | parsed raw entry and one Tea food item for today |
| `a tea` | parsed raw entry and one Tea food item for today |
| `cup of tea` | parsed raw entry and one Tea food item for today |
| `1 tea` | parsed raw entry and one Tea food item for today |
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
- today's logged food items
- today's calorie total
- pending entries section
- export CSV controls
- logged item edit/remove controls

The UI should render from Room-backed state. It must not parse visible tables, chat bubbles, or exported CSV back into canonical data.

Current logged-item behavior:

- Logged food items can be edited from the Today screen.
- Editing supports item name, amount, unit, calories, consumed time, and notes.
- Consumed time is required for edited food rows and currently uses `HH:mm`.
- Logged food items can be removed after confirmation.
- Removing a logged item hard-deletes the `FoodItemEntity`; the raw entry remains.

Current pending-entry behavior:

- Pending food entries can be manually resolved by the user from the Today screen.
- Manual resolution requires at least an item name and calories.
- Manual resolutions create `FoodItemEntity` rows with `source = MANUAL_OVERRIDE` and `confidence = HIGH`.
- Resolving a pending entry marks the associated raw entry as `PARSED` without deleting the audit record.
- Leaving an entry pending is the "keep pending" behavior; it remains unresolved and excluded from food exports until handled.
- Manual resolutions can optionally be saved as reusable user defaults so future matching inputs can be logged deterministically.
- Saved defaults represent calories per unit. If the user resolves `2 slices` as `190 kcal`, the stored shortcut is `95 kcal` per `slice`.
- Active shortcuts can be reviewed from the Today screen, edited in place, or forgotten by soft-deactivating the default.
- Forgetting a shortcut requires confirmation.
- Tapping an active shortcut logs one serving for the selected day using the same raw-entry and food-row path as typing the shortcut trigger.

## CSV Export

Implement two CSV exporters from Room rows only.

Daily export is the primary workflow for Phase 1 and the near-term Health Monitor handoff. The user should be encouraged to export the day's report regularly rather than treating the export as an unbounded canonical log.

Exported CSV files are output artifacts, not source data. If food rows change after export, FoodLog should generate a fresh export from Room rather than editing an existing external CSV file.

Daily export status is tracked locally with a date-keyed status row:

- `legacyExportedAt` is updated when the legacy Health Monitor CSV is exported.
- `auditExportedAt` is updated when the audit CSV is exported.
- `lastFoodChangedAt` is updated when confirmed food rows are added, edited, manually resolved, or removed.
- The Today screen shows whether each export has happened for the selected date.
- The Today screen shows when food rows changed after the last export.
- The Today screen highlights pending entry count before export.
- The Today screen shows a daily close readiness summary:
  - `No food logged` when the selected day has no confirmed food rows and no pending entries.
  - `Resolve pending entries` when unresolved raw entries remain for the selected day.
  - `Ready to export` when the selected day has confirmed food rows and the legacy Health Monitor export is missing or stale.
  - `Already exported` when the selected day has confirmed food rows and the legacy Health Monitor export is current.
- Future ongoing-log append mode should use a separate append ledger so already-appended food rows are not duplicated.

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

### `AuditCsvExporter`

This richer export preserves provenance for debugging and future import paths.

Header:

```csv
log_date,consumed_time,item,amount,unit,grams,calories,source,confidence,product,notes,raw_entry_id,created_at
```

Rules for both exporters:

- Export from Room `FoodItemEntity` rows only.
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
- `yesterday curry` creates a pending raw entry with yesterday's `logDate` and no food item.
- Unsupported input creates a pending raw entry and no food item.
- Daily total reflects active food rows.
- Logged item edits update totals and exports.
- Logged item removal deletes the food row and updates totals/exports.
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

## Assumptions

- `25 kcal` is the Phase 1 seeded tea default.
- Historical sample rows with `18 kcal` tea entries are legacy data and do not override the new seed default.
- Shortcut/default editing can wait until after Phase 1.
- The sample CSV is context for the legacy export contract, not the new internal schema.
- The app should remain convenient, but never silently wrong: use deterministic shortcuts when known, otherwise save pending entries for review.
