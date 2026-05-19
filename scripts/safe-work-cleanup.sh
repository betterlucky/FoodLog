#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'USAGE'
Usage: scripts/safe-work-cleanup.sh [options]

Safely proposes or removes old agent branches/worktrees.
Default mode is dry-run. Destructive cleanup requires --apply --yes.

Options:
  --apply              Perform eligible local cleanup actions
  --yes                Required with --apply
  --remote NAME        Git remote to inspect (default: origin)
  --main-ref REF       Ref used for ancestry-merged checks (default: <remote>/main)
  --delete-remote      Also delete eligible remote codex/* branches; requires --apply --yes
  -h, --help           Show help

Safety rules:
  - Never removes dirty worktrees
  - Never removes the current branch
  - Never removes a branch unless it is an ancestor of the main ref
  - Never deletes remote branches unless --delete-remote is present
USAGE
}

apply=0
yes=0
delete_remote=0
remote="origin"
main_ref=""

die() {
    echo "safe-work-cleanup: $*" >&2
    exit 1
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --apply)
            apply=1
            shift
            ;;
        --yes)
            yes=1
            shift
            ;;
        --remote)
            [[ $# -ge 2 ]] || die "missing value for $1"
            remote="$2"
            shift 2
            ;;
        --main-ref)
            [[ $# -ge 2 ]] || die "missing value for $1"
            main_ref="$2"
            shift 2
            ;;
        --delete-remote)
            delete_remote=1
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            die "unknown option: $1"
            ;;
    esac
done

command -v git >/dev/null 2>&1 || die "git is not installed"
command -v python3 >/dev/null 2>&1 || die "python3 is not installed"

repo_root="$(git rev-parse --show-toplevel 2>/dev/null)" || die "not inside a git work tree"
cd "$repo_root"

[[ "$apply" == "0" || "$yes" == "1" ]] || die "--apply requires --yes"
[[ "$delete_remote" == "0" || "$apply" == "1" ]] || die "--delete-remote requires --apply --yes"

main_ref="${main_ref:-${remote}/main}"
git rev-parse --verify "$main_ref" >/dev/null 2>&1 || die "$main_ref is not available; fetch first"
if [[ "$delete_remote" == "1" ]]; then
    git fetch --quiet "$remote"
fi

current_branch="$(git branch --show-current)"

is_merged() {
    local ref="$1"
    git merge-base --is-ancestor "$ref" "$main_ref" 2>/dev/null
}

branch_has_worktree() {
    local branch="$1"
    git worktree list --porcelain | awk -v target="refs/heads/$branch" '
        $1 == "branch" && $2 == target { found = 1 }
        END { exit found ? 0 : 1 }
    '
}

echo "# Safe Work Cleanup"
echo
if [[ "$apply" == "1" ]]; then
    echo "Mode: APPLY"
else
    echo "Mode: dry-run"
fi
echo "Remote: $remote"
echo "Main ref: $main_ref"
echo

echo "## Worktrees"
worktree_text="$(git worktree list --porcelain)"
python3 - "$main_ref" "$apply" "$worktree_text" <<'PY'
import pathlib, subprocess, sys

main_ref, apply, worktree_text = sys.argv[1], sys.argv[2] == "1", sys.argv[3]
blocks = worktree_text.strip().split("\n\n")
if not blocks or blocks == [""]:
    print("- none")
    raise SystemExit

for block in blocks:
    data = {}
    for line in block.splitlines():
        key, _, value = line.partition(" ")
        data.setdefault(key, value)
    path = data.get("worktree", "")
    branch_ref = data.get("branch", "")
    branch = branch_ref.replace("refs/heads/", "") if branch_ref else "(detached)"
    if not path:
        continue
    primary = pathlib.Path(path, ".git").is_dir()
    status = subprocess.run(["git", "-C", path, "status", "--short", "--untracked-files=all"], text=True, capture_output=True, check=False)
    dirty = bool(status.stdout.strip())
    merged = False
    if branch_ref:
        merged = subprocess.run(["git", "-C", path, "merge-base", "--is-ancestor", branch_ref, main_ref], check=False).returncode == 0
    eligible = bool(branch_ref and merged and not dirty and not primary)
    action = "remove" if eligible else "keep"
    reasons = []
    if dirty:
        reasons.append("dirty")
    if branch_ref and not merged:
        reasons.append("not merged")
    if not branch_ref:
        reasons.append("detached")
    if primary:
        reasons.append("primary")
    print(f"- {action}: {path} [{branch}]" + (f" ({', '.join(reasons)})" if reasons else ""))
    if apply and eligible:
        proc = subprocess.run(["git", "worktree", "remove", path], text=True, capture_output=True, check=False)
        if proc.returncode != 0:
            print(f"  failed: {proc.stderr.strip() or proc.stdout.strip() or 'git worktree remove failed'}")
PY
echo

echo "## Local Gone-Upstream Branches"
gone_branches="$(git branch -vv --format='%(refname:short)|%(upstream:track)' | awk -F'|' '$2 ~ /gone/ {print $1}')"
if [[ -z "$gone_branches" ]]; then
    echo "- none"
else
    while IFS= read -r branch; do
        [[ -n "$branch" ]] || continue
        if [[ "$branch" == "$current_branch" ]]; then
            echo "- keep: $branch (current branch)"
        elif branch_has_worktree "$branch"; then
            echo "- keep: $branch (checked out in a worktree)"
        elif is_merged "$branch"; then
            echo "- delete: $branch (gone upstream, merged)"
            if [[ "$apply" == "1" ]]; then
                if ! git branch -d "$branch"; then
                    echo "  failed: could not delete local branch $branch" >&2
                fi
            fi
        else
            echo "- keep: $branch (gone upstream, not merged)"
        fi
    done <<< "$gone_branches"
fi
echo

echo "## Remote codex/* Branches"
remote_branches="$(git for-each-ref --format='%(refname:short)' "refs/remotes/${remote}/codex" 2>/dev/null || true)"
if [[ -z "$remote_branches" ]]; then
    echo "- none"
else
    while IFS= read -r remote_branch; do
        [[ -n "$remote_branch" ]] || continue
        short_branch="${remote_branch#"$remote/"}"
        if is_merged "$remote_branch"; then
            if [[ "$delete_remote" == "1" ]]; then
                echo "- delete remote: $short_branch (merged)"
                if ! git push "$remote" --delete "$short_branch"; then
                    echo "  failed: could not delete remote branch $short_branch" >&2
                fi
            else
                echo "- candidate: $short_branch (merged; pass --delete-remote with --apply --yes to delete)"
            fi
        else
            echo "- keep: $short_branch (not merged)"
        fi
    done <<< "$remote_branches"
fi
