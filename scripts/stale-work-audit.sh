#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'USAGE'
Usage: scripts/stale-work-audit.sh [options]

Reports stale work signals without deleting or modifying branches/worktrees:
  - open GitHub PRs
  - remote codex/* branches
  - local branches whose upstream is gone
  - git worktrees and dirty worktree status
  - kanban cards outside done

Options:
  --project NAME       Kanban project name (default: FoodLog)
  --remote NAME        Git remote to inspect/fetch (default: origin)
  --kanban-url URL     Kanban board base URL (default: http://127.0.0.1:5174)
  --fetch              Run git fetch before reporting; does not prune refs
  -h, --help           Show help
USAGE
}

project="FoodLog"
remote="origin"
kanban_url="${KANBAN_URL:-http://127.0.0.1:5174}"
do_fetch=0

die() {
    echo "stale-work-audit: $*" >&2
    exit 1
}

command -v git >/dev/null 2>&1 || die "git is not installed"
command -v python3 >/dev/null 2>&1 || die "python3 is not installed"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --project)
            [[ $# -ge 2 ]] || { echo "missing value for $1" >&2; exit 1; }
            project="$2"
            shift 2
            ;;
        --kanban-url)
            [[ $# -ge 2 ]] || { echo "missing value for $1" >&2; exit 1; }
            kanban_url="$2"
            shift 2
            ;;
        --remote)
            [[ $# -ge 2 ]] || { echo "missing value for $1" >&2; exit 1; }
            remote="$2"
            shift 2
            ;;
        --fetch)
            do_fetch=1
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "unknown option: $1" >&2
            usage >&2
            exit 1
            ;;
    esac
done

repo_root="$(git rev-parse --show-toplevel 2>/dev/null)" || {
    echo "stale-work-audit: not inside a git work tree" >&2
    exit 1
}
cd "$repo_root"

if [[ "$do_fetch" == "1" ]]; then
    git fetch --quiet "$remote"
fi

echo "# Stale Work Audit"
echo
echo "Repository: $repo_root"
echo "Project: $project"
echo "Remote: $remote"
echo "Generated: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
echo

echo "## Open PRs"
if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    pr_json="$(gh pr list --state open --limit 100 --json number,title,headRefName,updatedAt,isDraft,url 2>/dev/null || true)"
    if [[ -n "$pr_json" && "$pr_json" != "[]" ]]; then
        python3 - "$pr_json" <<'PY'
import json, sys
for pr in json.loads(sys.argv[1]):
    draft = " draft" if pr.get("isDraft") else ""
    print(f"- #{pr['number']}{draft} {pr['headRefName']}: {pr['title']} ({pr['updatedAt']})")
    print(f"  {pr['url']}")
PY
    else
        echo "- none"
    fi
else
    echo "- unavailable: gh is missing or not authenticated"
fi
echo

echo "## Remote codex/* Branches"
remote_branches="$(git for-each-ref --format='%(refname:short) %(committerdate:iso8601) %(subject)' "refs/remotes/${remote}/codex" 2>/dev/null || true)"
if [[ -n "$remote_branches" ]]; then
    printf '%s\n' "$remote_branches" | sed 's/^/- /'
else
    echo "- none"
fi
echo

echo "## Local Branches With Gone Upstream"
gone_branches="$(git branch -vv --format='%(refname:short)|%(upstream:track)|%(worktreepath)' | awk -F'|' '$2 ~ /gone/ {print $1}')"
if [[ -n "$gone_branches" ]]; then
    printf '%s\n' "$gone_branches" | sed 's/^/- /'
else
    echo "- none"
fi
echo

echo "## Worktrees"
worktree_text="$(git worktree list --porcelain)"
python3 - "$worktree_text" <<'PY'
import pathlib, subprocess, sys

blocks = sys.argv[1].strip().split("\n\n")
if not blocks or blocks == [""]:
    print("- none")
    raise SystemExit

for block in blocks:
    data = {}
    for line in block.splitlines():
        key, _, value = line.partition(" ")
        data.setdefault(key, value)
    path = data.get("worktree", "")
    branch = data.get("branch", "(detached)").replace("refs/heads/", "")
    dirty = "unknown"
    if path and pathlib.Path(path).exists():
        try:
            proc = subprocess.run(
                ["git", "-C", path, "status", "--short"],
                text=True,
                capture_output=True,
                timeout=10,
                check=False,
            )
            dirty = "dirty" if proc.stdout.strip() else "clean"
        except Exception:
            dirty = "unavailable"
    print(f"- {path} [{branch}] {dirty}")
PY
echo

echo "## Active Kanban Cards"
board_url="${kanban_url%/}/api/board?project=${project}&summary=true"
if command -v curl >/dev/null 2>&1 && board_json="$(curl -sf --max-time 5 "$board_url" 2>/dev/null)"; then
    python3 - "$board_json" <<'PY'
import json, sys
try:
    board = json.loads(sys.argv[1])
except Exception as exc:
    print(f"- unavailable: kanban API returned invalid JSON: {exc}")
    raise SystemExit
columns = ["todo", "plan", "plan_review", "impl", "impl_review", "test"]
found = False
for col in columns:
    tasks = board.get(col) or []
    if tasks:
        found = True
        print(f"- {col}: {len(tasks)}")
        for task in tasks:
            print(f"  - #{task['id']} {task['title']}")
if not found:
    print("- none")
PY
else
    echo "- unavailable: kanban API not reachable at $board_url"
fi
echo

echo "## Safe Next Steps"
echo "- Close or merge PRs only after review status is understood."
echo "- Delete branches only after confirming they are merged or intentionally abandoned."
echo "- Remove worktrees only after checking they are clean or their changes are preserved."
echo "- Move kanban cards only after their real project state is verified."
