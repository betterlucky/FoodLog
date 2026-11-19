# FoodLog Agent Notes

FoodLog is a private, local-first Android app. Treat Room as the source of truth: raw entries, food rows, daily weight, daily status, shortcuts, and exports should flow from database state, not rendered UI text or CSV files.

## Current Product State

- The local core is Kotlin/Compose/Room with no backend yet.
- Deterministic parsing, shortcuts/defaults, pending resolution, logged-item editing/removal, daily weight, daily close status, Lodestone CSV export, and reviewable history suggestions are implemented.
- Local on-device OCR label scanning, the logging wizard, and Room-backed live journal export are implemented.
- Barcode logging was tried and is dropped for now. Do not add barcode/cache fields or Open Food Facts work unless the plan is explicitly reopened.
- Leftovers, corrections, fuzzy matching beyond simple Room-backed history suggestions, and conversational summaries are out of scope.

## Product Direction

- The goal is mindful intake tracking with minimal friction. The user has ADHD; sustained logging matters more than complete data. Judge changes by the taps, decisions, and chores they add or remove.
- Log now, enrich later: capture name, portion, and provenance; backfill protein and fibre afterwards rather than requiring nutrient entry at logging time.
- AI estimate capture is a near-term priority. Keep it provider-neutral: the app speaks FoodLog's versioned contract to a Cloudflare Worker, and provider adapters live in the Worker. Never hardcode a provider in the app or ship provider keys. See `PLAN.md` "AI Architecture".
- AI output is always a reviewed draft, never a silent log.
- Shortcuts should be portion-first for users. Save a repeatable user-facing portion with the shortcut where possible; use gram/ml math internally only when needed, and offer a non-intrusive "update shortcut" toggle when a logged edit should become the new default.
- Lodestone is secondary for now. Keep its CSV contract stable in `Downloads/FoodLogData`, but do not treat daily export as an obligation or add nutrient columns to it.
- Treat barcode lookup as a parked experiment, not a missing required feature.

## Working Rules

- Read `PLAN.md` before making product changes; it is the canonical plan.
- Read `docs/codex-handover.md` when starting from a fresh Codex project or when old thread context is missing.
- Kanban has been retired. Do not create or update board tasks, treat board state as a work gate, or replace it with another task-management system. Use the user's request, current repository state, and `PLAN.md` to choose work.
- Check whether a task is already implemented before changing code, and update docs when product direction, workflow, or verification expectations change.
- CodeRabbit and `scripts/review-guard review --context "<change summary>"` are optional review tools. Use them when the user asks or when an independent review is proportionate to the change. ReviewGuard uses Qwen, Antigravity, and E4B where available; inspect and verify accepted findings yourself.
- During requested review waits or other timed sleeps, state that you are waiting and then stay quiet until there is a result or a blocker.
- Keep changes local-first. Network calls are limited to the user-initiated AI Worker described in `PLAN.md`.
- Save raw text before interpretation and preserve raw-entry audit records.
- Use repository/database transactions for flows that create or update raw entries and food rows together.
- Exports must be generated from Room rows only.
- Keep parsing, export generation, totals, and label/product calculations out of composables where practical.
- Do not commit real food logs, personal exports, SDK paths, keystores, tokens, Worker secrets, or API keys.

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

After the smoke script launches the app, follow `docs/android-smoke-scenario.md` to log one OCR/label-derived food row, add one daily weight, export the Lodestone CSV, and confirm the day reports as exported/current.
