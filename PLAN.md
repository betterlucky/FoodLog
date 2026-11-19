# FoodLog Plan

FoodLog is a private, local-first Android food logging app. Its purpose is to make the user mindful of what they consume by recording calories, and later protein and fibre, with as little friction as possible.

The user has ADHD. Most journalling attempts stop after one or two entries, so the measure of success is sustained logging, not complete or perfect data. Every feature should be judged by how many taps, decisions, and remembered chores it adds to or removes from logging one item.

Room is the source of truth. Raw entries, food rows, daily weight, daily status, shortcuts, products, and exports must come from database state, not rendered UI text, chat history, or CSV files.

## Current State

Implemented local core:

- Kotlin native Android app using Compose, Room, coroutines, Flow, and MVVM-style screen state.
- Raw-entry audit records, deterministic shortcut/default parsing, pending resolution, logged-item editing/removal, daily totals, daily weight, daily status, and Lodestone CSV export.
- Today screen as the main daily working surface.
- Shortcut picker with search, immediate logging, add/edit/forget actions, and shortcut-backed compound logging.
- Pending and logged-item resolver flows that can stage recognised shortcut parts and ask only for unresolved parts.
- Room-backed history suggestions for repeated ambiguous pending foods, shown as reviewable candidates rather than silent auto-logs.
- Local on-device OCR label scan flow using ML Kit text recognition, conservative nutrition parsing, and a user-confirmed logging wizard.
- Full-history live journal CSV export from Room rows, with a chosen output file that is rewritten from current state and optional weight rows.

Known gaps:

- Calories are the only nutrient that reaches a logged row. The label parser extracts protein, fibre, carbohydrate, fat, sugars, and salt, but `logLabelProduct` stores none of them, and `FoodItemEntity` and `UserDefaultEntity` have no nutrient columns.
- Logged rows do not record which shortcut produced them, so later analysis can only match on name.
- Home-made food has no dedicated path. The user previously pasted ingredients into a chatbot and logged its estimate by hand.
- No AI or backend code exists yet. `FoodItemSource.ESTIMATE` and `ProductSource.ESTIMATE` are unused.

Out of scope:

- Barcode logging was tried and is parked. The schema intentionally has no barcode/cache/Open Food Facts fields.
- Leftovers, correction entities/flows, fuzzy product matching, and unsolicited conversational summaries.
- Treating exported CSV files as canonical data.

The retained `food_log_full_2026-04-04_to_2026-04-23.csv` file is synthetic fixture/context for the legacy Health Monitor CSV contract consumed by Lodestone. It must not contain real personal food history.

## Product Direction

Principles:

- **Friction first.** Prefer one-tap confirmation of a good draft over a multi-step form. Remove nags and daily chores rather than adding reminders.
- **Log now, enrich later.** Capture enough to identify what was eaten and how much (name, portion, grams/ml where known, provenance). Protein and fibre can be backfilled later from labels, shortcuts, or reviewed AI estimates. Do not make nutrient entry a required step at logging time.
- **Shortcuts stay the speed engine.** AI and label flows should end by offering a portion-first shortcut so the next log of the same food is one tap.
- **AI drafts, the user confirms.** AI output pre-fills the existing review flows. It never logs silently.
- **Lodestone is secondary.** The health-condition model is not the focus for this period. Keep the CSV export working but stop treating it as a daily obligation.

Priorities, in order:

1. **Backfill-ready data (local only).** Persist the nutrients the label parser already extracts on products and label-derived shortcuts, record ml as well as grams where known, and link food rows to the shortcut that produced them. One Room migration (17 → 18).
2. **AI estimate capture.** A provider-neutral backend (see AI Architecture) and an "estimate this" entry point that takes text or a photo and returns a reviewed draft in the logging wizard, with `source = ESTIMATE`, a confidence level, and provenance. This covers home-made food (paste or photograph the ingredients and enter the cooked batch weight to get a per-100g shortcut), plates, restaurant meals, and vague text.
3. **AI label reading.** Offer an AI label read alongside local OCR. Compare both on several real labels before choosing the default. Keep local OCR as the offline path.
4. **Nutrient backfill and display.** Fill protein and fibre for existing rows from product/shortcut data first, then from batched AI estimates marked as estimates. Show protein and fibre totals on Today once enough rows have values.
5. **Lower-friction entry points.** Android share target ("share photo to FoodLog") and/or a home-screen quick-add, so capture does not require opening the app and navigating.
6. **Demote daily close.** Remove the stale-export prompt from the main surface. Keep Lodestone export available as an explicit action.

Later:

- Full compound meals and recipe entities with ingredient-level breakdowns, if the AI batch-estimate path proves too coarse.
- User-initiated AI questions about recent logged data, meal ideas, and nutritional advice. No commentary on the user's day unless asked.
- In-app charts from Room-backed history.

Parked:

- Leftovers/container behavior.
- Correction audit flows and fuzzy matching.
- Direct Lodestone integration replacing the CSV handoff, export reminders, and richer closed-day semantics.
- A lightweight recovery journal for same-day resilience after reinstall/restore surprises. If revisited, keep it append-only, Room-grounded, and replayable only with explicit user review.

Explicitly dropped:

- Barcode lookup and Open Food Facts integration.
- Unsolicited conversational summaries or commentary on the user's day.

If a parked item becomes important, reopen it deliberately with a small plan, migration notes where needed, and a clear user workflow.

## AI Architecture

The app must not depend on any particular AI provider. Providers are plugged in behind a thin Cloudflare Worker, and the app only speaks FoodLog's own versioned contract.

Layers:

- **App ↔ Worker contract.** The app calls FoodLog endpoints such as `POST /v1/estimate` and `POST /v1/label`. Requests and responses are strict, versioned JSON. The app never sees provider names in its request shape and never holds provider API keys.
- **Worker provider adapters.** Each adapter translates the FoodLog request into one provider's API and normalises the reply back into the FoodLog response. Adding a provider means adding one adapter; the app does not change.
- **Routing config.** Worker configuration (environment variables or a small config object) chooses the adapter and model per task, for example one model for text estimates and another for vision. It may define an ordered fallback list, and may optionally fan out to several providers for side-by-side comparison during evaluation.
- **Provenance.** Every response records the contract version, provider, and model that produced it. The app stores this with the draft so later estimates and comparisons remain traceable.

Sketch of the estimate response (to be finalised with the first implementation):

```json
{
  "contractVersion": 1,
  "items": [
    {
      "name": "Chicken curry with rice",
      "amount": 1,
      "unit": "plate",
      "grams": 450,
      "kcal": 720,
      "proteinG": 38,
      "fibreG": 6,
      "confidence": "MEDIUM",
      "notes": "Assumed takeaway-style portion"
    }
  ],
  "provenance": { "provider": "example", "model": "example-model" }
}
```

Guardrails:

- Send only what the user chose to submit: food text, food photos, and label photos. Do not send logged history, weight, or other personal data unless a future user-initiated question feature explicitly needs it.
- Authenticate the app to the Worker with a revocable app token stored outside source control.
- Validate every response against the contract in the app. Invalid or failed responses show a clear message and create no food row; the user can fall back to manual, label, or shortcut flows.
- Treat AI calories and nutrients as estimates with confidence and provenance. Require user review before logging.
- Prefer deterministic routes and local history before AI when they already give a good candidate.
- Never call AI during export, totals, or daily status calculation.
- AI must never infer the day's food from chat history or rendered UI text.

## Non-Negotiable Rules

- Save raw submitted text before interpretation whenever a text entry is submitted.
- Preserve raw-entry audit records in normal flows.
- Use repository/database transactions for workflows that create or update raw entries and food rows together.
- Unsupported deterministic text inputs become pending raw entries and create no food rows.
- Exports are generated from active Room rows only.
- Totals use active `FoodItemEntity` rows only.
- Daily weight is separate daily metadata, not a food item, and never affects calories.
- Keep parsing, export generation, totals, label parsing, AI response mapping, and product calorie calculations out of composables where practical.
- Keep food data local-first. Network calls are limited to the AI Worker, are user-initiated, and produce reviewed drafts only.
- Do not commit real food logs, personal exports, SDK paths, keystores, tokens, Worker secrets, or API keys.

## Core Data Contracts

Primary entities:

- `RawEntryEntity`: immutable-ish audit record for submitted or staged text.
- `FoodItemEntity`: confirmed consumed food row used for totals and exports.
- `UserDefaultEntity`: shortcut/default definition used by deterministic parsing.
- `ProductEntity`: barcode-neutral reusable product/label metadata.
- `ProductPhotoEntity`: photo metadata for product/label capture (currently unused).
- `ContainerEntity`: retained local model support; active leftover/container behavior is parked.
- `DailyWeightEntity`: one optional weight record per food day.
- `DailyStatusEntity`: export and change status per food day.
- `AppSettingsEntity`: local app settings such as food-day boundary.

Planned additions (priority 1 and 4):

- Per-100g/100ml protein and fibre (and other parsed label nutrients) on products and label-derived shortcuts.
- A nullable shortcut reference on `FoodItemEntity`.
- Nullable protein and fibre on `FoodItemEntity` with a nutrient provenance marker (label, shortcut, estimate) so backfilled values are distinguishable from label-derived ones.
- AI provenance (contract version, provider, model) on estimate-derived rows or their raw entries.

Product storage should remain barcode-neutral. It may store source metadata, package size, item count, serving size/unit, calories per 100g/100ml, calories per serving, optional nutrients, and last-logged portion details.

The seeded tea default remains the first shortcut/default example:

- `lookupKey = "tea"`
- `name = "Tea"`
- `calories = 25`
- `unit = "cup"`
- `notes = "English tea with skimmed milk and half a teaspoon of sugar"`
- `source = USER_DEFAULT`
- `confidence = HIGH`

`lookupKey` is an internal, normalized match key derived from the shortcut name. The user-facing shortcut label and the logged item name should stay the same field unless a future feature has a concrete need for separate display text.

## Daily Workflow

The Today screen is the main product surface. It should support:

- selected food day and day navigation
- text entry for deterministic shortcut logging
- shortcut picker near the input area
- today's confirmed food rows and calorie total (protein and fibre once backfill lands)
- pending entries and resolver flows
- logged-item edit/remove controls
- daily weight entry
- local label scan/manual product logging
- AI estimate entry point (planned)
- explicit, non-nagging Lodestone export action

Important behavior:

- Date prefixes and food-day boundary logic should set the intended `logDate`; explicit dates override default food-day fallback.
- The app should avoid silently logging to a different selected day from the one the user is viewing.
- Time parsing should be shared between free-text parsing and editable time fields where practical.
- Logged-item removal deletes the selected `FoodItemEntity`; it does not delete the associated raw audit record.
- Pending-entry removal can hard-delete unresolved raw entries when the user intentionally discards them.
- Manual resolutions require at least item name and calories.
- Optional "save as shortcut" should create reusable deterministic defaults around a user-understandable portion, not an unintuitive per-gram default where it can be avoided.
- Shortcuts should save the portion the user actually means to repeat, such as "one pot", "one wrap", "one bowl", or another clear serving label, with calories for that portion.
- Creating a shortcut should not silently overwrite an existing active shortcut. Use explicit edit/update flows when changing a saved default.
- If the user logs the same shortcut with an edited portion or calorie value, offer a non-intrusive "update shortcut" toggle so they can choose whether the shortcut should learn the new normal.

## Label And Product Logging

Label OCR is the current packaged-food path:

- Use local on-device ML Kit Latin text recognition.
- Let the user take a photo or choose an image.
- Parse conservatively and require user confirmation before logging.
- Prefer clear partial-success behavior over guessing.
- OCR failures should show a clear message and create no food row.
- Label-derived products use `source = SAVED_LABEL`.
- Persist captured nutrients (protein, fibre, carbohydrate, fat, sugars, salt) with the product and any shortcut saved from it.
- Do not treat ambiguous "per serving" calories as one arbitrary item. If the label gives serving calories plus a serving weight, g/ml entry may calculate the portion; otherwise ask the user for calories instead of guessing.
- Make repeat use of label/manual entries flow through portion-backed shortcuts, not a separate saved-products surface.

An AI label read (priority 3) should feed the same review step and produce the same product/shortcut data, with AI provenance recorded.

## Lodestone Export

Lodestone is not the focus for this period, but the CSV contract stays stable so it can resume later. FoodLog writes to the historical import folder:

```text
Downloads/FoodLogData
```

Standard daily filename:

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
- Do not add nutrient columns to this contract; nutrients belong in the live journal.
- Do not call AI during export.
- Do not parse UI text or old CSV files as export input.

Daily status tracking (changed-since-export) can stay in the data layer, but the main screen should not prompt the user to export each day.

The richer audit CSV exporter is retained for developer/data tracing, but it should not become the main user workflow.

## Live Journal Export

The live journal is the main export for standalone review and analysis.

- Generate from Room rows only.
- Treat the output as a replaceable live journal, not an auditable database or immutable ledger.
- Keep it as a full-history export. On each update, rewrite the whole chosen CSV from current Room data rather than appending or merging stale rows.
- Let the user create or choose the journal CSV file once, then update that same file on later exports.
- Exclude weight rows by default, with a user option to include them.
- Add protein, fibre, and nutrient provenance columns once those fields exist.
- Choose stable fields that could also support future in-app charts, such as dates, times, source/provenance, shortcut/product ids, current active/removed state, calories, nutrients, weight, and status timestamps where appropriate.
- Keep focused date-range/report exports separate from the main live journal file.
- Do not call AI during export.

## Verification

Use the checked-in Gradle wrapper:

```sh
JAVA_HOME=/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest
```

For connected/instrumented tests on an attached Android device:

```sh
scripts/android-connected-test.sh
```

For an Android smoke pass:

```sh
scripts/android-smoke.sh /tmp/foodlog-smoke.png
```

Do not fix install/signature problems with a plain `adb uninstall com.betterlucky.foodlog`; that wipes local FoodLog data. Use the smoke script's guarded reinstall path or take an app-private data backup first.

Current smoke scenario: after the script launches the app, follow `docs/android-smoke-scenario.md` to log one OCR/label-derived food row, add one daily weight, export the Lodestone CSV, and confirm the day reports as exported/current. Update the scenario when daily close is demoted and when AI capture lands.

Prefer local unit tests for parser, export, totals, daily close, day-boundary, label parsing, portion resolution, and AI contract mapping. Use instrumented tests when Room migrations, Android framework behavior, or Compose interaction is the thing being verified. Worker adapters should have their own tests against recorded provider responses, so contract changes are caught without live API calls.

## Working Rules For Changes

- Use the current user request and repository state to decide immediate work, after checking whether the behavior is already implemented.
- Keep focused tests only where the behavior is active and risky.
- Update this plan when product direction, scope, or verification expectations change.
