# FoodLog

Private, local-first Android food logging app.

The canonical implementation plan is in [PLAN.md](PLAN.md). The retained CSV file is sample fixture data and documents the legacy health-monitor export format.

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

The local Android SDK path is stored in ignored `local.properties`.
