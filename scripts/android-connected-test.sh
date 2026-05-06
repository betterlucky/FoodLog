#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home}"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT_DIR/.gradle-user-home}"
ADB="${ADB:-adb}"
PACKAGE="com.betterlucky.foodlog"
TEST_PACKAGE="com.betterlucky.foodlog.test"
TEST_RUNNER="androidx.test.runner.AndroidJUnitRunner"
INSTRUMENTATION_OUTPUT=""

cleanup() {
    if [[ -n "$INSTRUMENTATION_OUTPUT" ]]; then
        rm -f "$INSTRUMENTATION_OUTPUT"
    fi
}
trap cleanup EXIT

cd "$ROOT_DIR"

mkdir -p "$GRADLE_USER_HOME"
GRADLE_ENV=(
    "JAVA_HOME=$JAVA_HOME"
    "GRADLE_USER_HOME=$GRADLE_USER_HOME"
)
if [[ -n "${ANDROID_USER_HOME:-}" ]]; then
    mkdir -p "$ANDROID_USER_HOME"
    GRADLE_ENV+=("ANDROID_USER_HOME=$ANDROID_USER_HOME")
fi

device_window_state() {
    "$ADB" shell dumpsys window 2>/dev/null | tr -d '\r' || true
}

device_appears_locked() {
    local window_state
    window_state="$(device_window_state)"
    [[ "$window_state" == *"mDreamingLockscreen=true"* ||
        "$window_state" == *"mShowingLockscreen=true"* ||
        "$window_state" == *"mKeyguardShowing=true"* ||
        "$window_state" == *"isStatusBarKeyguard=true"* ]]
}

wake_device() {
    "$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    "$ADB" shell wm dismiss-keyguard >/dev/null 2>&1 || true
    "$ADB" shell svc power stayon true >/dev/null 2>&1 || true
}

package_installed() {
    "$ADB" shell pm path "$1" 2>/dev/null | tr -d '\r' | grep -q '^package:'
}

wake_device

if device_appears_locked; then
    echo "The attached phone still appears to be locked or dreaming." >&2
    echo "Please unlock it now; connected Compose tests need the test activity to stay foregrounded." >&2
    for _ in {1..30}; do
        sleep 1
        wake_device
        if ! device_appears_locked; then
            break
        fi
    done
fi

if device_appears_locked; then
    echo "The phone still appears locked. Unlock it and rerun this script." >&2
    exit 1
fi

env "${GRADLE_ENV[@]}" ./gradlew assembleDebug assembleDebugAndroidTest

APP_APK="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

echo "Installing FoodLog debug APK with adb install -r, preserving app data." >&2
if ! "$ADB" install -r "$APP_APK"; then
    echo "Could not install $PACKAGE with adb install -r." >&2
    echo "Refusing to uninstall the app because that would wipe local FoodLog data." >&2
    exit 1
fi

echo "Installing instrumentation APK." >&2
if ! "$ADB" install -r "$TEST_APK"; then
    echo "Could not install $TEST_PACKAGE. Removing only the test package and retrying once." >&2
    "$ADB" uninstall "$TEST_PACKAGE" >/dev/null 2>&1 || true
    "$ADB" install -r "$TEST_APK"
fi

set +e
INSTRUMENTATION_OUTPUT="$(mktemp "${TMPDIR:-/tmp}/foodlog-instrumentation.XXXXXX.txt")"
"$ADB" shell am instrument -w -r "$@" "$TEST_PACKAGE/$TEST_RUNNER" 2>&1 | tee "$INSTRUMENTATION_OUTPUT"
test_status=$?
set -e
if [[ "$test_status" -eq 0 ]] && ! grep -q '^OK ([0-9][0-9]* tests*)' "$INSTRUMENTATION_OUTPUT"; then
    test_status=1
fi

echo "Removing instrumentation APK; leaving FoodLog installed." >&2
"$ADB" uninstall "$TEST_PACKAGE" >/dev/null 2>&1 || true

if ! package_installed "$PACKAGE"; then
    echo "$PACKAGE is not installed after instrumentation; this should not happen in the custom runner." >&2
    exit 1
fi

exit "$test_status"
