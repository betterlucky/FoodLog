# FoodLog

Private, local-first Android food logging app.

The canonical implementation plan is in [PLAN.md](PLAN.md). Agent-specific working notes are in [AGENTS.md](AGENTS.md). The retained CSV file is sample fixture data and documents the legacy Health Monitor export format now imported by Lodestone.

## Data Hygiene

Keep this repo private. Do not commit real food logs, personal exports, local SDK paths, keystores, tokens, or API keys. Use synthetic fixtures for CSV examples and tests.

## Current Status

This repository contains the first Phase 1 implementation slice:

- Kotlin native Android app
- Jetpack Compose UI
- Room database
- deterministic shortcut parsing
- pending-entry and logged-item resolution wizard
- local on-device OCR label scan flow
- daily weight and daily close/export status
- local CSV export
- no backend and no OpenAI calls in Phase 1
- barcode logging was tried and is dropped for now

## Current Direction

Near-term work should polish the existing local daily workflow: OCR/manual product logging, shortcuts/defaults, and Lodestone export readiness. Barcode lookup is parked unless the plan is explicitly reopened.

## Local Development

This project uses the checked-in Gradle wrapper.

```sh
JAVA_HOME=/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest
```

To run connected/instrumented tests on an attached Android device:

```sh
JAVA_HOME=/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home ./gradlew connectedDebugAndroidTest
```

To build, install, launch, and capture a phone screenshot:

```sh
scripts/android-smoke.sh /tmp/foodlog-smoke.png
```

The smoke script uses `adb install -r` first, which preserves app data. It uses the normal Android debug keystore by default so it matches plain Gradle/connected-test installs. If Android still requires a reinstall because of a signature mismatch, the script attempts to back up and restore app-private data before uninstalling; it refuses to wipe data unless `FOODLOG_ALLOW_DATA_LOSS=1` is set.

Useful smoke scenario: log one OCR/label-derived food row, add one daily weight, export the Lodestone CSV, and confirm the day reports as exported/current.

Lodestone still imports from the historical `Downloads/FoodLogData` folder for now; keep that folder and filename contract stable unless the importer is updated at the same time.

The local Android SDK path is stored in ignored `local.properties`.
