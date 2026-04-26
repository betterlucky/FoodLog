#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home}"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT_DIR/.gradle-user-home}"
ANDROID_USER_HOME="${ANDROID_USER_HOME:-$ROOT_DIR/.android-user-home}"
ADB="${ADB:-adb}"
SCREENSHOT_PATH="${1:-/tmp/foodlog-smoke.png}"

cd "$ROOT_DIR"

mkdir -p "$GRADLE_USER_HOME" "$ANDROID_USER_HOME"

JAVA_HOME="$JAVA_HOME" \
    GRADLE_USER_HOME="$GRADLE_USER_HOME" \
    ANDROID_USER_HOME="$ANDROID_USER_HOME" \
    ./gradlew assembleDebug

if ! "$ADB" install -r app/build/outputs/apk/debug/app-debug.apk; then
    "$ADB" uninstall com.betterlucky.foodlog >/dev/null || true
    "$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
fi

"$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
"$ADB" shell wm dismiss-keyguard >/dev/null 2>&1 || true
"$ADB" shell monkey -p com.betterlucky.foodlog -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 2
"$ADB" exec-out screencap -p > "$SCREENSHOT_PATH"

echo "Wrote screenshot: $SCREENSHOT_PATH"
