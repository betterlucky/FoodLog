# FoodLog Plan

FoodLog is a local-first Android food logging app. The current goal is to make the existing daily workflow dependable enough for ordinary use, then add optional AI extras for harder capture cases, recent-data questions, meal ideas, and nutritional advice.

Room is the source of truth. Raw entries, food rows, daily weight, daily status, shortcuts, products, and exports must come from database state, not rendered UI text, chat history, or CSV files.

## Current State

Implemented local core:

- Kotlin native Android app using Compose, Room, coroutines, Flow, and MVVM-style screen state.
- Raw-entry audit records, deterministic shortcut/default parsing, pending resolution, logged-item editing/removal, daily totals, daily weight, daily status, and Lodestone CSV export.
- Today screen as the main daily working surface.
- Shortcut picker with search, immediate logging, add/edit/forget actions, and shortcut-backed compound logging.
- Pending and logged-item resolver flows that can stage recognised shortcut parts and ask only for unresolved parts.
- Local on-device OCR label scan flow using ML Kit text recognition, conservative nutrition parsing, and a user-confirmed logging wizard.

Out of scope for the current local core:

- Barcode logging was tried and is parked. The current product schema intentionally has no barcode/cache/Open Food Facts fields.
- Leftovers, correction entities/flows, fuzzy product matching, and unsolicited conversational summaries.
- Treating exported CSV files as canonical data.

The retained `food_log_full_2026-04-04_to_2026-04-23.csv` file is synthetic fixture/context for the legacy Health Monitor CSV contract now consumed by Lodestone. It must not contain real personal food history.

## Product Direction

Near-term work should make daily use faster, clearer, and harder to get subtly wrong.

Current priorities:

1. Label/manual product polish: make scanned and manually entered packaged foods reliable, understandable, and quick to repeat.
2. Shortcut/product polish: make common foods easy to save, find, edit, forget, and log without creating duplicate or confusing defaults.
3. Daily close/export polish: make the Lodestone handoff obvious, current, and trustworthy.

## Next Horizon

After daily workflow polish, begin optional AI integration. AI should help where deterministic local flows are weak, while staying grounded in Room data and user review.

AI capture assistance:

- fuzzy or unknown meals, such as homemade food at a friend's house
- a picture of a plate of food
- OCR-unreadable or partial nutrition labels
- ambiguous text entries that deterministic shortcuts cannot resolve

Optional AI conversation:

- user-initiated questions about recent logged data
- meal ideas
- nutritional advice
- no commentary on the user's day unless they specifically ask for it

AI calls should eventually be served to the app through a Cloudflare Worker or similarly thin backend proxy.

## Non-Negotiable Rules

- Save raw submitted text before interpretation whenever a text entry is submitted.
- Preserve raw-entry audit records in normal flows.
- Use repository/database transactions for workflows that create or update raw entries and food rows together.
- Unsupported deterministic text inputs become pending raw entries and create no food rows.
- Exports are generated from active Room rows only.
- Totals use active `FoodItemEntity` rows only.
- Daily weight is separate daily metadata, not a food item, and never affects calories.
- Keep parsing, export generation, totals, label parsing, and product calorie calculations out of composables where practical.
- Keep Phase 1 local-first and deterministic unless this plan is explicitly changed.
- Do not commit real food logs, personal exports, SDK paths, keystores, tokens, or API keys.

## Core Data Contracts

Primary entities:

- `RawEntryEntity`: immutable-ish audit record for submitted or staged text.
- `FoodItemEntity`: confirmed consumed food row used for totals and exports.
- `UserDefaultEntity`: shortcut/default definition used by deterministic parsing.
- `ProductEntity`: barcode-neutral reusable product/label metadata.
- `ProductPhotoEntity`: photo metadata for product/label capture.
- `ContainerEntity`: retained local model support; active leftover/container behavior is later-phase.
- `DailyWeightEntity`: one optional weight record per food day.
- `DailyStatusEntity`: export and change status per food day.
- `AppSettingsEntity`: local app settings such as food-day boundary.

Product storage should remain barcode-neutral for now. It may store source metadata, package size, item count, serving size/unit, calories per 100g, calories per serving, optional nutrients, and last-logged grams. Do not add barcode/cache fields unless barcode lookup is deliberately reopened as a fresh experiment.

The seeded tea default remains the first shortcut/default example:

- `trigger = "tea"`
- `name = "Tea"`
- `calories = 25`
- `unit = "cup"`
- `notes = "English tea with skimmed milk and half a teaspoon of sugar"`
- `source = USER_DEFAULT`
- `confidence = HIGH`

## Daily Workflow

The Today screen is the main product surface. It should support:

- selected food day and day navigation
- text entry for deterministic shortcut logging
- shortcut picker near the input area
- today's confirmed food rows and calorie total
- pending entries and resolver flows
- logged-item edit/remove controls
- daily weight entry
- local label scan/manual product logging
- daily close/readiness status
- Lodestone export action

Important behavior:

- Date prefixes and food-day boundary logic should set the intended `logDate`; explicit dates override default food-day fallback.
- The app should avoid silently logging to a different selected day from the one the user is viewing.
- Time parsing should be shared between free-text parsing and editable time fields where practical.
- Logged-item removal deletes the selected `FoodItemEntity`; it does not delete the associated raw audit record.
- Pending-entry removal can hard-delete unresolved raw entries when the user intentionally discards them.
- Manual resolutions require at least item name and calories.
- Optional "save as shortcut" should create reusable deterministic defaults without storing confusing per-total values; parsed quantities should normalize to per-unit or per-gram defaults.

## Label And Product Logging

Label OCR is the current packaged-food path:

- Use local on-device ML Kit Latin text recognition.
- Let the user take a photo or choose an image.
- Parse conservatively and require user confirmation before logging.
- Prefer clear partial-success behavior over guessing.
- OCR failures should show a clear message and create no food row.
- Label-derived products use `source = SAVED_LABEL`.
- Optional captured nutrients may include protein, fibre, carbohydrate, fat, sugars, and salt.

Near-term polish:

- Improve wizard copy and validation for partial labels, especially when calories are present but serving amount/unit is ambiguous.
- Make repeat use of saved label/manual products more visible and less fiddly.
- Add focused tests around OCR reader handoff where Android/ML Kit boundaries allow it.

## Lodestone Export

The Lodestone CSV is the daily handoff contract. FoodLog still writes to the historical import folder until Lodestone changes:

```text
Downloads/FoodLogData
```

Keep the standard daily filename:

```text
food_log_YYYY-MM-DD.csv
```

`LegacyHealthCsvExporter` keeps the inherited Health Monitor schema:

```csv
date,time_local,item,quantity,calories_kcal,notes
```

Export rules:

- Export food from active `FoodItemEntity` rows only.
- Export optional daily weight from `DailyWeightEntity` only.
- Sort by date, consumed time, then creation time.
- Escape commas, quotes, and newlines correctly.
- Preserve blank times as blank CSV fields.
- Do not call AI during export.
- Do not parse UI text or old CSV files as export input.

Daily close/readiness should remain simple:

- Empty day: no export needed.
- Pending entries: resolve pending entries before handoff.
- Confirmed food or weight with missing/stale export: ready to export.
- Confirmed food or weight with current export: already exported/current.
- Daily close is the main-screen Lodestone export action; avoid a duplicate standalone export button.

The richer audit CSV exporter is retained for developer/data tracing, but it should not become the main user workflow.

## Verification

Use the checked-in Gradle wrapper:

```sh
JAVA_HOME=/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest
```

For connected/instrumented tests on an attached Android device:

```sh
JAVA_HOME=/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home ./gradlew connectedDebugAndroidTest
```

For an Android smoke pass:

```sh
scripts/android-smoke.sh /tmp/foodlog-smoke.png
```

Do not fix install/signature problems with a plain `adb uninstall com.betterlucky.foodlog`; that wipes local FoodLog data. Use the smoke script's guarded reinstall path or take an app-private data backup first.

Good next smoke scenario: log one OCR/label-derived food row, add one daily weight, export the Lodestone CSV, and confirm the day reports as exported/current.

Prefer local unit tests for parser, export, totals, daily close, day-boundary, label parsing, and portion resolution logic. Use instrumented tests when Room, Android framework behavior, or Compose interaction is the thing being verified.

## Working Backlog

Active:

- Polish label/manual product wizard validation and copy.
- Improve repeat logging for saved products and recently logged label-derived items.
- Smooth shortcut management for common daily foods.
- Keep daily close/export status easy to trust after edits, removals, pending resolution, and weight changes.
- Expand focused tests only where the behavior is active and risky.

Soon:

- Backend/OpenAI features.
- Leftovers/container behavior.
- Long-term append-log export mode. The export is for our sister app, users wanting this as a standalone will prefer a longer form version of the export containing all the data for analysis

Parked:

- Correction audit flows.
- Fuzzy matching.
- Direct Lodestone/Health Monitor integration intended to replace the CSV handoff.
- Export reminders and richer closed-day semantics.

Explicitly dropped:

- Barcode lookup and Open Food Facts integration.
- Unsolicited conversational summaries or commentary on the user's day.

If a parked item becomes important, reopen it deliberately with a small plan, migration notes where needed, and a clear user workflow. Do not treat parked work as missing required scope.

## AI Guardrails

Backend-backed AI is the next horizon after local daily use is dependable:

- Keep personal food logs local by default.
- Use a thin backend proxy; do not ship provider API keys in the Android app.
- Query structured database state first.
- Prefer deterministic routes before AI.
- Require strict versioned JSON contracts.
- Treat AI calories as estimates with confidence and provenance.
- Require user review when confidence is not high.

AI must never infer the day's food from chat history or rendered UI text.
