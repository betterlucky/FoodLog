# FoodLog Plan

FoodLog is a private, local-first food logging app, moving from Android to a native iPhone app (see Platform). Its purpose is to make the user mindful of what they consume by recording calories, and later protein and fibre, with as little friction as possible.

The user has ADHD. Most journalling attempts stop after one or two entries, so the measure of success is sustained logging, not complete or perfect data. Every feature should be judged by how many taps, decisions, and remembered chores it adds to or removes from logging one item.

The app's local database (Room on Android, its iOS equivalent after the port) is the source of truth. Raw entries, food rows, daily weight, daily status, shortcuts, products, and exports must come from database state, not rendered UI text, chat history, or CSV files.

## Current State

Implemented local core (Android):

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

## Platform

The user's daily phone is an iPhone, so FoodLog moves to a native SwiftUI app. A logging app only works if it is on the phone already in hand.

- **Native SwiftUI, not Kotlin Multiplatform, for now.** The user prefers Android and may return to it. If that happens, revisit Kotlin Multiplatform or a port back then rather than paying its build complexity now.
- **The Android app is frozen** as the reference implementation and the source of existing data. Its behavior, decisions, and unit tests are the spec for the iOS port. Apart from a data exporter, do not add Android features.
- **Repository layout.** Keep the Android project at the repository root. Add `ios/` for the iPhone app and `worker/` for the AI Worker.
- **What to port.** Deterministic parser, shortcuts with portion modes, pending resolution, logging wizard, logged-item edit/remove, history suggestions, label parser and portion resolver (fed by Apple's Vision text recognition instead of ML Kit), daily totals, daily weight, and the live journal export. Build nutrient columns and the food row to shortcut link into the iOS data model from the start.
- **What not to port yet.** The Lodestone daily CSV export and its daily-close prompt. Lodestone currently reads from the Android Downloads folder; revisit an iOS export only if Lodestone becomes a focus again.
- **iOS persistence.** Choose at the start of the port. It must support explicit, tested schema migrations.
- **iOS-native capture.** App Intents (Siri, Action Button, Shortcuts) live inside the main app and are the first low-friction entry points. A share extension and interactive widgets come later, subject to the signing limits below. Apple Health writes (energy, protein, fibre) and weight reads are an optional later addition.

Signing and installation:

- FoodLog uses personal-team (free) signing, renewed every 7 days by Lodestone2's existing scheduled LaunchAgent (`Lodestone2/scripts/scheduled_harvest.py`), following the Pacing Monitor pattern: a FoodLog `device.py`-style installer that backs up app Documents before an in-place update and never uninstalls, plus a FoodLog renewal lane in Lodestone2.
- Free signing allows three active apps per device. The slots are Lodestone Health, Pacing Monitor, and FoodLog. H10 Sessions was archived and removed from the iPhone on 2026-11-19 to free a slot.
- App extensions (share extension, widgets) may count against the same limit. Check this before building one.
- If another slot is needed, Lodestone Health is the next candidate to retire, possibly moving its recording to Android.
- Never uninstall FoodLog to fix signing; it holds the only copy of local data.

## Product Direction

Principles:

- **Friction first.** Prefer one-tap confirmation of a good draft over a multi-step form. Remove nags and daily chores rather than adding reminders.
- **Log now, enrich later.** Capture enough to identify what was eaten and how much (name, portion, grams/ml where known, provenance). Protein and fibre can be backfilled later from labels, shortcuts, or reviewed AI estimates. Do not make nutrient entry a required step at logging time.
- **Shortcuts stay the speed engine.** AI and label flows should end by offering a portion-first shortcut so the next log of the same food is one tap.
- **AI drafts, the user confirms.** AI output pre-fills the existing review flows. It never logs silently.
- **Lodestone is secondary.** The health-condition model is not the focus for this period. Keep the CSV export working but stop treating it as a daily obligation.

Priorities, in order:

1. **Android data export.** Done (2026-11-19): `scripts/export-android-data.py` turns a pulled Room database into a versioned JSON export of shortcuts, products, raw entries, food rows, and daily weight. See `docs/android-data-export.md`. The Android app needed no changes.
2. **iOS port of the core.** SwiftUI app with the scope in Platform, the Kotlin unit tests ported as its spec, import of the Android export, nutrients and the shortcut link built into the data model, and installation through the renewal lane.
3. **AI estimate capture.** A provider-neutral backend (see AI Architecture) and an "estimate this" entry point that takes text or a photo and returns a reviewed draft in the logging wizard, with `source = ESTIMATE`, a confidence level, and provenance. This covers home-made food (paste or photograph the ingredients and enter the cooked batch weight to get a per-100g shortcut), plates, restaurant meals, and vague text.
4. **AI label reading.** Offer an AI label read alongside on-device OCR. Compare both on several real labels before choosing the default. Keep on-device OCR as the offline path.
5. **Nutrient backfill and display.** Fill protein and fibre for existing rows, including imported Android history, from product/shortcut data first, then from batched AI estimates marked as estimates. Show protein and fibre totals on Today once enough rows have values.
6. **Lower-friction entry points.** App Intents first ("log tea" by Siri, Action Button, or Shortcuts), then a share extension and widgets if signing slots allow, so capture does not require opening the app and navigating.

Later:

- Full compound meals and recipe entities with ingredient-level breakdowns, if the AI batch-estimate path proves too coarse.
- User-initiated AI questions about recent logged data, meal ideas, and nutritional advice. No commentary on the user's day unless asked.
- In-app charts from Room-backed history.

Parked:

- Leftovers/container behavior.
- Correction audit flows and fuzzy matching.
- Lodestone CSV export on iOS, direct Lodestone integration, export reminders, and richer closed-day semantics.
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

Additions to build into the iOS data model (priority 2):

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
- live journal export (the Lodestone export stays Android-only for now)

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

- Use on-device text recognition: ML Kit Latin on Android, Apple's Vision framework on iOS. The label parser takes plain text, so it ports unchanged in behavior, but OCR line ordering may differ and needs checking against real labels.
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

Android smoke scenario: after the script launches the app, follow `docs/android-smoke-scenario.md` to log one OCR/label-derived food row, add one daily weight, export the Lodestone CSV, and confirm the day reports as exported/current.

iOS verification (to be set up with the port):

- Unit tests for ported domain logic, run with `xcodebuild test` on a simulator. Port the matching Kotlin tests first and keep their cases.
- Builds and UI checks in the iOS Simulator.
- Device installs only through the FoodLog installer, which backs up Documents first and never uninstalls.
- A migration test for every schema change.

Prefer local unit tests for parser, export, totals, daily close, day-boundary, label parsing, portion resolution, and AI contract mapping. Use instrumented tests when Room migrations, Android framework behavior, or Compose interaction is the thing being verified. Worker adapters should have their own tests against recorded provider responses, so contract changes are caught without live API calls.

## Working Rules For Changes

- Use the current user request and repository state to decide immediate work, after checking whether the behavior is already implemented.
- Keep focused tests only where the behavior is active and risky.
- Update this plan when product direction, scope, or verification expectations change.
