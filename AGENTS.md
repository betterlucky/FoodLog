# FoodLog Agent Notes

FoodLog is a private, local-first Android app. Treat Room as the source of truth: raw entries, food rows, daily weight, daily status, shortcuts, and exports should flow from database state, not rendered UI text or CSV files.

## Current Product State

- Phase 1 is local-only Kotlin/Compose/Room.
- Deterministic parsing, shortcuts/defaults, pending resolution, logged-item editing/removal, daily weight, daily close status, and Lodestone CSV export are implemented.
- Local on-device OCR label scanning and the logging wizard are implemented.
- Barcode logging was tried and is dropped for now. Do not add barcode/cache fields or Open Food Facts work unless the plan is explicitly reopened.
- OpenAI/backend features, leftovers, corrections, fuzzy matching, and conversational summaries are out of scope for the current local core.

## Product Direction

- Near-term work should make the existing daily workflow more dependable before adding new capture modes.
- Prefer shortcut/product polish, label/manual product polish, daily close/export polish, then append-log export before AI integration.
- Shortcuts should be portion-first for users. Save a repeatable user-facing portion with the shortcut where possible; use gram/ml math internally only when needed, and offer a non-intrusive "update shortcut" toggle when a logged edit should become the new default.
- Treat barcode lookup as a parked experiment, not a missing required feature.
- Keep the Lodestone CSV export as the daily handoff contract. Lodestone still imports from the historical `Downloads/FoodLogData` folder for now.

## Working Rules

- Read `PLAN.md` before making product changes; it is the canonical plan.
- Keep changes local-first and deterministic unless the plan says otherwise.
- Save raw text before interpretation and preserve raw-entry audit records.
- Use repository/database transactions for flows that create or update raw entries and food rows together.
- Exports must be generated from Room rows only.
- Keep parsing, export generation, totals, and label/product calculations out of composables where practical.
- Do not commit real food logs, personal exports, SDK paths, keystores, tokens, or API keys.

## Important Paths

- `app/src/main/java/com/betterlucky/foodlog/data/repository/FoodLogRepository.kt` coordinates database-backed workflows.
- `app/src/main/java/com/betterlucky/foodlog/domain/parser/DeterministicParser.kt` handles deterministic food text parsing.
- `app/src/main/java/com/betterlucky/foodlog/domain/label/LabelNutritionParser.kt` handles conservative OCR label parsing.
- `app/src/main/java/com/betterlucky/foodlog/domain/label/LabelPortionResolver.kt` resolves label amounts into calories/grams.
- `app/src/main/java/com/betterlucky/foodlog/ui/today/TodayViewModel.kt` owns Today screen state and wizard behavior.
- `app/src/main/java/com/betterlucky/foodlog/ui/today/TodayScreen.kt` is the current main UI surface.
- `app/src/main/java/com/betterlucky/foodlog/domain/export/LegacyHealthCsvExporter.kt` defines the legacy Health Monitor CSV contract now consumed by Lodestone.

## Verification

Use the checked-in Gradle wrapper:

```sh
JAVA_HOME=/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest
```

For connected/instrumented tests on an attached Android device:

```sh
scripts/android-connected-test.sh
```

The connected-test wrapper wakes the device, asks Android to dismiss the keyguard, keeps power on while tests run, and pauses with a clear prompt if the phone still appears locked. Compose tests can fail with "No compose hierarchies found" if the lock screen or notification shade steals focus from the test activity.

For an Android smoke pass:

```sh
scripts/android-smoke.sh /tmp/foodlog-smoke.png
```

The smoke script should preserve app data across ordinary reinstalls. It uses the normal Android debug keystore by default so it matches plain Gradle/connected-test installs. If it hits a signature mismatch, it backs up app-private data before uninstalling and refuses to continue if that backup is unavailable, unless `FOODLOG_ALLOW_DATA_LOSS=1` is explicitly set.

Do not fix install/signature problems with a plain `adb uninstall com.betterlucky.foodlog`; that wipes local FoodLog data. Use the smoke script's guarded reinstall path or take an app-private data backup first.

Good next smoke scenario: log one OCR/label-derived food row, add one daily weight, export the Lodestone CSV, and confirm the day reports as exported/current.
