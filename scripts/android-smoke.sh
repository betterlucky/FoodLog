#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home}"
SCREENSHOT_PATH="${1:-/tmp/foodlog-smoke.png}"

cd "$ROOT_DIR"

JAVA_HOME="$JAVA_HOME" ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p com.betterlucky.foodlog -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 2
adb exec-out screencap -p > "$SCREENSHOT_PATH"

echo "Wrote screenshot: $SCREENSHOT_PATH"
