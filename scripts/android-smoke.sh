#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home}"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT_DIR/.gradle-user-home}"
ADB="${ADB:-adb}"
PACKAGE="com.betterlucky.foodlog"
SCREENSHOT_PATH="${1:-/tmp/foodlog-smoke.png}"
DATA_BACKUP=""

cleanup() {
    if [[ -d "$GRADLE_USER_HOME" ]]; then
        (cd "$ROOT_DIR" && env "JAVA_HOME=$JAVA_HOME" "GRADLE_USER_HOME=$GRADLE_USER_HOME" ./gradlew --stop >/dev/null 2>&1) || true
    fi
    if [[ -n "$DATA_BACKUP" ]]; then
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

env "${GRADLE_ENV[@]}" ./gradlew assembleDebug

INSTALL_OUTPUT=""
if ! INSTALL_OUTPUT="$("$ADB" install -r app/build/outputs/apk/debug/app-debug.apk 2>&1)"; then
    echo "$INSTALL_OUTPUT" >&2
    if [[ "$INSTALL_OUTPUT" != *"INSTALL_FAILED_UPDATE_INCOMPATIBLE"* &&
        "$INSTALL_OUTPUT" != *"INSTALL_FAILED_INCONSISTENT_CERTIFICATES"* &&
        "$INSTALL_OUTPUT" != *"INSTALL_FAILED_VERSION_DOWNGRADE"* ]]; then
        echo "Install failed for a reason that is not safe to fix with uninstall/reinstall." >&2
        echo "Refusing to uninstall $PACKAGE." >&2
        exit 1
    fi

    echo "Install failed with an update/signature incompatibility; attempting data-preserving reinstall for $PACKAGE." >&2
    DATA_BACKUP="$(mktemp "${TMPDIR:-/tmp}/foodlog-data-backup.XXXXXX.tar")"
    if "$ADB" exec-out run-as "$PACKAGE" sh -c \
        "cd /data/data/$PACKAGE || exit 1; paths=''; for d in databases shared_prefs files no_backup; do [ -e \"\$d\" ] && paths=\"\$paths \$d\"; done; if [ -n \"\$paths\" ]; then tar -cf - \$paths; fi" \
        > "$DATA_BACKUP"; then
        if [[ -s "$DATA_BACKUP" ]]; then
            echo "Backed up app-private data before reinstall." >&2
        else
            echo "No app-private data directories found to back up." >&2
        fi
    else
        rm -f "$DATA_BACKUP"
        DATA_BACKUP=""
        if [[ "${FOODLOG_ALLOW_DATA_LOSS:-0}" == "1" ]]; then
            echo "Warning: could not back up app data; continuing because FOODLOG_ALLOW_DATA_LOSS=1." >&2
        else
            echo "Refusing to uninstall $PACKAGE because app data could not be backed up." >&2
            echo "Set FOODLOG_ALLOW_DATA_LOSS=1 only if wiping local FoodLog data is acceptable." >&2
            exit 1
        fi
    fi

    "$ADB" uninstall "$PACKAGE" >/dev/null || true
    "$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
    if ! "$ADB" shell pm list packages "$PACKAGE" | grep -qx "package:$PACKAGE"; then
        echo "Reinstall command completed, but $PACKAGE is not installed." >&2
        exit 1
    fi

    if [[ -s "$DATA_BACKUP" ]]; then
        "$ADB" exec-in run-as "$PACKAGE" sh -c "cd /data/data/$PACKAGE && tar -xf -" < "$DATA_BACKUP"
        echo "Restored app-private data after reinstall." >&2
    fi
fi

"$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
"$ADB" shell wm dismiss-keyguard >/dev/null 2>&1 || true
"$ADB" shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 2
"$ADB" exec-out screencap -p > "$SCREENSHOT_PATH"

echo "Wrote screenshot: $SCREENSHOT_PATH"
