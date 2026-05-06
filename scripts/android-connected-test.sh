#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home}"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT_DIR/.gradle-user-home}"
ADB="${ADB:-adb}"

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

env "${GRADLE_ENV[@]}" ./gradlew connectedDebugAndroidTest "$@"
