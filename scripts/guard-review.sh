#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "scripts/guard-review.sh is deprecated; use scripts/review-guard review instead." >&2
exec "$SCRIPT_DIR/review-guard" review "$@"
