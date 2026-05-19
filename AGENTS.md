# FoodLog Agent Notes

FoodLog is a private, local-first Android app. Treat Room as the source of truth: raw entries, food rows, daily weight, daily status, shortcuts, and exports should flow from database state, not rendered UI text or CSV files.

## Current Product State

- Phase 1 is local-only Kotlin/Compose/Room.
- Deterministic parsing, shortcuts/defaults, pending resolution, logged-item editing/removal, daily weight, daily close status, Lodestone CSV export, and reviewable history suggestions are implemented.
- Local on-device OCR label scanning, the logging wizard, and Room-backed live journal export are implemented.
- Barcode logging was tried and is dropped for now. Do not add barcode/cache fields or Open Food Facts work unless the plan is explicitly reopened.
- OpenAI/backend features, leftovers, corrections, fuzzy matching beyond simple Room-backed history suggestions, and conversational summaries are out of scope for the current local core.

## Product Direction

- Near-term work should make the existing daily workflow more dependable before adding new capture modes.
- Prefer shortcut/product polish, label/manual product polish, daily close/export polish, live journal stability, then history suggestion quality before AI integration.
- Shortcuts should be portion-first for users. Save a repeatable user-facing portion with the shortcut where possible; use gram/ml math internally only when needed, and offer a non-intrusive "update shortcut" toggle when a logged edit should become the new default.
- Treat barcode lookup as a parked experiment, not a missing required feature.
- Keep the Lodestone CSV export as the daily handoff contract. Lodestone still imports from the historical `Downloads/FoodLogData` folder for now.

## Working Rules

- Read `PLAN.md` before making product changes; it is the canonical plan.
- Use the `FoodLog` kanban board for tracked work. Follow `docs/kanban-workflow.md` for next-card selection, lifecycle/status updates, review waits, and docs-update expectations.
- Check whether a task is already implemented before changing code, and update docs when product direction, workflow, or verification expectations change.
- After each board item is completed, commit the finished change, push a branch, open a PR, and wait for review unless the change clearly does not need one. CodeRabbit usually autoreviews within 10-15 minutes; if it reports a review quota delay, wait for the delay, then check comments yourself and trigger `@coderabbitai review` if needed. Once review is resolved or silent/clean, merge the PR and continue to the next board item. If scope or acceptance is unclear, ask for more context before continuing; use `/grill-me` style scrutiny when a task would benefit from adversarial review.
- If CodeRabbit is unavailable, quota-limited, or skipped by policy, use `scripts/guard-review.sh --context "<task/change summary>"` for the multi-review guard rather than a single fallback model. The wrapper runs the two local oMLX reviewers (`Qwen3.6-27B-4bit` and `gemma-4-E4B-it-MLX-8bit`) sequentially with lock/load/unload safety, plus Gemini where available; follow `docs/kanban-workflow.md` for details and record the fallback result template as a kanban card note before merging. The three reviewers have been observed to catch different subsets of issues, so merge and deduplicate their findings, resolve valid comments, and rerun the relevant local verification commands before merging. Gemini-only review is acceptable when the local oMLX server/models are unavailable, or when the change is docs-only and does not alter commands, scripts, workflow policy, data contracts, or verification expectations; it is still advisory and still requires valid findings to be resolved plus local verification before merge.
- During review waits or other timed sleeps, state that you are waiting and then stay quiet until there is a result or a blocker.
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

After the smoke script launches the app, follow `docs/android-smoke-scenario.md` to log one OCR/label-derived food row, add one daily weight, export the Lodestone CSV, and confirm the day reports as exported/current.
