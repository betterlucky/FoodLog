#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home}"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT_DIR/.gradle-user-home}"
ADB="${ADB:-adb}"
PACKAGE="com.betterlucky.foodlog"
DATA_BACKUP=""
RESTORE_DATA_BACKUP=0
KEEP_DATA_BACKUP=0

cleanup() {
    if [[ -n "$DATA_BACKUP" && "$KEEP_DATA_BACKUP" != "1" ]]; then
        rm -f "$DATA_BACKUP"
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
    "$ADB" shell pm path "$PACKAGE" 2>/dev/null | tr -d '\r' | grep -q '^package:'
}

backup_app_data() {
    if ! package_installed; then
        return 0
    fi

    DATA_BACKUP="$(mktemp "${TMPDIR:-/tmp}/foodlog-connected-test-data.XXXXXX.tar")"
    if ADB="$ADB" PACKAGE="$PACKAGE" "$ROOT_DIR/scripts/android-backup-app-data.sh" "$DATA_BACKUP" >&2; then
        RESTORE_DATA_BACKUP=1
        echo "Backed up app-private data before connected tests." >&2
        return 0
    fi

    rm -f "$DATA_BACKUP"
    DATA_BACKUP=""
    return 1
}

restore_app_after_tests() {
    local apk_path="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
    if ! package_installed; then
        if [[ ! -f "$apk_path" ]]; then
            echo "FoodLog was removed during connected tests, and no debug APK is available to reinstall." >&2
            return 1
        fi
        echo "FoodLog was removed during connected tests; reinstalling debug APK." >&2
        "$ADB" install -r "$apk_path" >/dev/null
    fi

    if [[ "$RESTORE_DATA_BACKUP" == "1" && -s "$DATA_BACKUP" ]]; then
        if "$ADB" exec-in run-as "$PACKAGE" sh -c "cd /data/data/$PACKAGE && tar -xf - 2>/dev/null" < "$DATA_BACKUP"; then
            echo "Restored app-private data after connected tests." >&2
        else
            KEEP_DATA_BACKUP=1
            echo "Could not restore app-private data after connected tests." >&2
            echo "Preserved data backup at: $DATA_BACKUP" >&2
            return 1
        fi
    fi
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

if ! backup_app_data; then
    if [[ "${FOODLOG_ALLOW_DATA_LOSS:-0}" == "1" ]]; then
        echo "Warning: could not back up app data; continuing because FOODLOG_ALLOW_DATA_LOSS=1." >&2
    else
        echo "Refusing to run connected tests because app-private data could not be backed up." >&2
        echo "Set FOODLOG_ALLOW_DATA_LOSS=1 to override." >&2
        exit 1
    fi
fi

set +e
env "${GRADLE_ENV[@]}" ./gradlew connectedDebugAndroidTest "$@"
test_status=$?
set -e

restore_app_after_tests
restore_status=$?

if [[ "$test_status" -ne 0 ]]; then
    exit "$test_status"
fi
exit "$restore_status"
