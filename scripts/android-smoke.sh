#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home}"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT_DIR/.gradle-user-home}"
ADB="${ADB:-adb}"
PACKAGE="com.betterlucky.foodlog"
SCREENSHOT_PATH="${1:-/tmp/foodlog-smoke.png}"
DATA_BACKUP=""
KEEP_DATA_BACKUP=0
APK_BACKUP_DIR=""

cleanup() {
    if [[ -d "$GRADLE_USER_HOME" ]]; then
        (cd "$ROOT_DIR" && env "JAVA_HOME=$JAVA_HOME" "GRADLE_USER_HOME=$GRADLE_USER_HOME" ./gradlew --stop >/dev/null 2>&1) || true
    fi
    if [[ -n "$DATA_BACKUP" && "$KEEP_DATA_BACKUP" != "1" ]]; then
        rm -f "$DATA_BACKUP"
    fi
    if [[ -n "$APK_BACKUP_DIR" ]]; then
        rm -rf "$APK_BACKUP_DIR"
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

package_installed() {
    "$ADB" shell pm path "$PACKAGE" 2>/dev/null | tr -d '\r' | grep -q '^package:'
}

verify_package_installed() {
    if ! package_installed; then
        echo "$1" >&2
        return 1
    fi
}

backup_installed_apks() {
    if ! package_installed; then
        return 0
    fi

    APK_BACKUP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/foodlog-apk-backup.XXXXXX")"
    local count=0
    local remote_path
    while IFS= read -r remote_path; do
        remote_path="${remote_path#package:}"
        remote_path="${remote_path//$'\r'/}"
        if [[ -z "$remote_path" ]]; then
            continue
        fi
        count=$((count + 1))
        if ! "$ADB" pull "$remote_path" "$APK_BACKUP_DIR/$count.apk" >/dev/null; then
            rm -rf "$APK_BACKUP_DIR"
            APK_BACKUP_DIR=""
            echo "Could not back up installed APK from $remote_path." >&2
            return 1
        fi
    done < <("$ADB" shell pm path "$PACKAGE" 2>/dev/null | tr -d '\r')

    if [[ "$count" -eq 0 ]]; then
        rm -rf "$APK_BACKUP_DIR"
        APK_BACKUP_DIR=""
        echo "Could not find installed APK paths for $PACKAGE." >&2
        return 1
    fi

    echo "Backed up currently installed APK for rollback." >&2
}

restore_installed_apks() {
    if [[ -z "$APK_BACKUP_DIR" || ! -d "$APK_BACKUP_DIR" ]]; then
        return 1
    fi

    local apks=("$APK_BACKUP_DIR"/*.apk)
    if [[ ! -e "${apks[0]}" ]]; then
        return 1
    fi

    if [[ "${#apks[@]}" -eq 1 ]]; then
        if ! "$ADB" install -r "${apks[0]}"; then
            return 1
        fi
    else
        if ! "$ADB" install-multiple -r "${apks[@]}"; then
            return 1
        fi
    fi
}

backup_app_data() {
    DATA_BACKUP="$(mktemp "${TMPDIR:-/tmp}/foodlog-data-backup.XXXXXX.tar")"
    if "$ADB" exec-out run-as "$PACKAGE" sh -c \
        "cd /data/data/$PACKAGE || exit 1; paths=''; for d in databases shared_prefs files no_backup; do [ -e \"\$d\" ] && paths=\"\$paths \$d\"; done; if [ -n \"\$paths\" ]; then tar -cf - \$paths; fi" \
        > "$DATA_BACKUP"; then
        if [[ -s "$DATA_BACKUP" ]]; then
            echo "Backed up app-private data before reinstall." >&2
        else
            echo "No app-private data directories found to back up." >&2
        fi
        return 0
    fi

    rm -f "$DATA_BACKUP"
    DATA_BACKUP=""
    return 1
}

restore_app_data() {
    if [[ ! -s "$DATA_BACKUP" ]]; then
        return 0
    fi

    if ! "$ADB" exec-in run-as "$PACKAGE" sh -c "cd /data/data/$PACKAGE && tar -xf -" < "$DATA_BACKUP"; then
        return 1
    fi
    echo "Restored app-private data after reinstall." >&2
}

safe_restore_previous_install() {
    echo "Attempting to roll back to the APK that was installed before this script ran." >&2
    if restore_installed_apks && verify_package_installed "Rollback install completed, but $PACKAGE is still not installed."; then
        if restore_app_data; then
            echo "Rolled back to the previous install and restored app-private data." >&2
            return 0
        fi
        KEEP_DATA_BACKUP=1
        echo "Rolled back to the previous APK, but app-private data restore failed." >&2
        echo "Preserved data backup at: $DATA_BACKUP" >&2
        return 1
    fi

    KEEP_DATA_BACKUP=1
    echo "Rollback failed. Preserved data backup at: $DATA_BACKUP" >&2
    return 1
}

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
    if ! backup_installed_apks; then
        echo "Refusing to uninstall $PACKAGE because the current APK could not be backed up for rollback." >&2
        exit 1
    fi

    if ! backup_app_data; then
        if [[ "${FOODLOG_ALLOW_DATA_LOSS:-0}" == "1" ]]; then
            echo "Warning: could not back up app data; continuing because FOODLOG_ALLOW_DATA_LOSS=1." >&2
        else
            echo "Refusing to uninstall $PACKAGE because app data could not be backed up." >&2
            echo "Set FOODLOG_ALLOW_DATA_LOSS=1 only if wiping local FoodLog data is acceptable." >&2
            exit 1
        fi
    fi

    "$ADB" uninstall "$PACKAGE" >/dev/null || true
    if ! "$ADB" install -r app/build/outputs/apk/debug/app-debug.apk; then
        echo "Fresh install failed after uninstall." >&2
        safe_restore_previous_install || true
        exit 1
    fi
    verify_package_installed "Reinstall command completed, but $PACKAGE is not installed." || {
        safe_restore_previous_install || true
        exit 1
    }

    if ! restore_app_data; then
        KEEP_DATA_BACKUP=1
        echo "Fresh install succeeded, but app-private data restore failed." >&2
        echo "Preserved data backup at: $DATA_BACKUP" >&2
        exit 1
    fi
fi

"$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
"$ADB" shell wm dismiss-keyguard >/dev/null 2>&1 || true
"$ADB" shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 2
"$ADB" exec-out screencap -p > "$SCREENSHOT_PATH"

echo "Wrote screenshot: $SCREENSHOT_PATH"
