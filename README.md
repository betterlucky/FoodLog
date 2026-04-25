# FoodLog

Private, local-first Android food logging app.

The canonical implementation plan is in [PLAN.md](PLAN.md). The retained CSV file is sample fixture data and documents the legacy health-monitor export format.

## Data Hygiene

Keep this repo private. Do not commit real food logs, personal exports, local SDK paths, keystores, tokens, or API keys. Use synthetic fixtures for CSV examples and tests.

## Current Status

This repository contains the first Phase 1 implementation slice:

- Kotlin native Android app
- Jetpack Compose UI
- Room database
- deterministic shortcut parsing
- local CSV export
- no backend and no OpenAI calls in Phase 1

## Local Development

This project uses the checked-in Gradle wrapper.

```sh
JAVA_HOME=/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest
```

To build, install, launch, and capture a phone screenshot:

```sh
scripts/android-smoke.sh /tmp/foodlog-smoke.png
```

The local Android SDK path is stored in ignored `local.properties`.
