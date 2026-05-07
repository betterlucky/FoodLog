#!/usr/bin/env bash
set -euo pipefail

ADB="${ADB:-adb}"
PACKAGE="${PACKAGE:-com.betterlucky.foodlog}"
OUTPUT="${1:-${TMPDIR:-/tmp}/foodlog-data-backup-$(date +%Y%m%d-%H%M%S).tar}"

mkdir -p "$(dirname "$OUTPUT")"

if ! "$ADB" shell pm path "$PACKAGE" >/dev/null 2>&1; then
    echo "$PACKAGE is not installed on the connected device." >&2
    exit 1
fi

# Include no_backup here intentionally: this script is a test-data safety net, not Android's cloud backup contract.
if ! "$ADB" exec-out run-as "$PACKAGE" sh -c \
    "cd /data/data/$PACKAGE || exit 1; paths=''; for d in databases shared_prefs files no_backup; do [ -e \"\$d\" ] && paths=\"\$paths \$d\"; done; if [ -n \"\$paths\" ]; then tar -cf - \$paths; fi" \
    > "$OUTPUT"; then
    rm -f "$OUTPUT"
    echo "Could not back up app-private data for $PACKAGE." >&2
    exit 1
fi

if [[ ! -s "$OUTPUT" ]]; then
    rm -f "$OUTPUT"
    echo "No app-private data directories were found for $PACKAGE." >&2
    exit 1
fi

echo "Backed up app-private data to: $OUTPUT"
