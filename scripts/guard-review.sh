#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'USAGE'
Usage: scripts/guard-review.sh [options]

Runs the FoodLog multi-review guard:
  1. Qwen via oMLX, with lock/load/settle/unload
  2. Gemini CLI in read-only plan mode
  3. E4B via oMLX, with lock/load/settle/unload

Options:
  --range RANGE          Git diff range to review, e.g. origin/main...HEAD
  --staged               Review staged changes
  --unstaged             Review unstaged working tree changes
  --context TEXT         Extra context for all reviewers
  --output-dir DIR       Directory for raw/merged results (default: /tmp/foodlog-guard-review.TIMESTAMP)
  --settle-seconds N     Seconds to wait after oMLX model load (default: 10)
  --lock-timeout N       Seconds to wait for /tmp/omlx-guard.lock.d (default: 600)
  --review-timeout N     Seconds for each local model inference (default: 180)
  --gemini-timeout N     Seconds for the Gemini reviewer (default: 180)
  --max-chars N          Max subject characters sent to reviewers (default: 60000)
  --skip-local           Skip oMLX reviewers
  --skip-gemini          Skip Gemini reviewer
  -h, --help             Show help

Environment:
  OMLX_URL               Default: http://127.0.0.1:8000
  OMLX_KEY               Default: 5555
  OMLX_LOCK_DIR          Default: /tmp/omlx-guard.lock.d
  OMLX_LOCK_FILE         Backward-compatible base path; script locks ${OMLX_LOCK_FILE}.d if OMLX_LOCK_DIR is unset
  GUARD_QWEN_MODEL       Default: Qwen3.6-27B-4bit
  GUARD_GEMINI_MODEL     Default: gemini-3-flash-preview
  GUARD_E4B_MODEL        Default: gemma-4-E4B-it-MLX-8bit
USAGE
}

die() {
    printf 'guard-review: %s\n' "$*" >&2
    exit 1
}

command -v git >/dev/null 2>&1 || die "git is not installed"
command -v python3 >/dev/null 2>&1 || die "python3 is not installed"
command -v curl >/dev/null 2>&1 || die "curl is not installed"

repo_root="$(git rev-parse --show-toplevel 2>/dev/null)" || die "not inside a git work tree"
cd "$repo_root"

range=""
diff_kind="default"
context=""
output_dir=""
settle_seconds="${GUARD_SETTLE_SECONDS:-10}"
lock_timeout="${GUARD_LOCK_TIMEOUT:-600}"
review_timeout="${GUARD_REVIEW_TIMEOUT:-180}"
gemini_timeout="${GUARD_GEMINI_TIMEOUT:-180}"
max_chars="${GUARD_MAX_CHARS:-60000}"
skip_local=0
skip_gemini=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --range)
            [[ $# -ge 2 ]] || die "$1 requires a value"
            range="$2"
            diff_kind="range"
            shift 2
            ;;
        --staged)
            diff_kind="staged"
            shift
            ;;
        --unstaged)
            diff_kind="unstaged"
            shift
            ;;
        --context)
            [[ $# -ge 2 ]] || die "$1 requires a value"
            context="$2"
            shift 2
            ;;
        --output-dir)
            [[ $# -ge 2 ]] || die "$1 requires a value"
            output_dir="$2"
            shift 2
            ;;
        --settle-seconds)
            [[ $# -ge 2 ]] || die "$1 requires a value"
            settle_seconds="$2"
            shift 2
            ;;
        --lock-timeout)
            [[ $# -ge 2 ]] || die "$1 requires a value"
            lock_timeout="$2"
            shift 2
            ;;
        --review-timeout)
            [[ $# -ge 2 ]] || die "$1 requires a value"
            review_timeout="$2"
            shift 2
            ;;
        --gemini-timeout)
            [[ $# -ge 2 ]] || die "$1 requires a value"
            gemini_timeout="$2"
            shift 2
            ;;
        --max-chars)
            [[ $# -ge 2 ]] || die "$1 requires a value"
            max_chars="$2"
            shift 2
            ;;
        --skip-local)
            skip_local=1
            shift
            ;;
        --skip-gemini)
            skip_gemini=1
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

[[ "$settle_seconds" =~ ^[0-9]+$ ]] || die "--settle-seconds must be a number"
[[ "$lock_timeout" =~ ^[0-9]+$ ]] || die "--lock-timeout must be a number"
[[ "$review_timeout" =~ ^[0-9]+$ ]] || die "--review-timeout must be a number"
[[ "$gemini_timeout" =~ ^[0-9]+$ ]] || die "--gemini-timeout must be a number"
[[ "$max_chars" =~ ^[0-9]+$ ]] || die "--max-chars must be a number"

case "$diff_kind" in
    staged)
        diff_text="$(git diff --staged --find-renames)"
        label="staged changes"
        ;;
    unstaged)
        diff_text="$(git diff --find-renames)"
        label="unstaged changes"
        ;;
    range)
        diff_text="$(git diff --find-renames "$range")"
        label="$range"
        ;;
    default)
        merge_base="$(git merge-base origin/main HEAD 2>/dev/null || true)"
        if [[ -n "$merge_base" ]]; then
            diff_text="$(git diff --find-renames "$merge_base...HEAD")"
            label="origin/main...HEAD"
        else
            diff_text="$(git diff --find-renames)"
            label="working tree changes"
        fi
        ;;
esac

[[ -n "$diff_text" ]] || die "no diff found for $label"

truncated_note=""
if (( ${#diff_text} > max_chars )); then
    diff_text="${diff_text:0:max_chars}"
    truncated_note="Diff was truncated at ${max_chars} characters."
fi

umask 077
if [[ -z "$output_dir" ]]; then
    output_dir="$(mktemp -d "${TMPDIR:-/tmp}/foodlog-guard-review.XXXXXX")"
else
    mkdir -p "$output_dir"
fi

subject_file="$output_dir/subject.diff"
printf '%s\n' "$diff_text" > "$subject_file"

OMLX_URL="${OMLX_URL:-http://127.0.0.1:8000}"
OMLX_KEY="${OMLX_KEY:-5555}"
OMLX_LOCK_DIR="${OMLX_LOCK_DIR:-${OMLX_LOCK_FILE:-/tmp/omlx-guard.lock}.d}"
QWEN_MODEL="${GUARD_QWEN_MODEL:-Qwen3.6-27B-4bit}"
GEMINI_MODEL="${GUARD_GEMINI_MODEL:-gemini-3-flash-preview}"
E4B_MODEL="${GUARD_E4B_MODEL:-gemma-4-E4B-it-MLX-8bit}"

timeout_bin=""
if command -v gtimeout >/dev/null 2>&1; then
    timeout_bin="gtimeout"
elif command -v timeout >/dev/null 2>&1; then
    timeout_bin="timeout"
fi

json_fallback() {
    local source="$1"
    local issue="$2"
    python3 - "$source" "$issue" <<'PY'
import json, sys
print(json.dumps({
    "source": sys.argv[1],
    "findings": [{
        "severity": "medium",
        "file": "",
        "issue": sys.argv[2],
        "recommendation": "Treat this reviewer as unavailable and continue with remaining reviewers."
    }],
    "notes": []
}))
PY
}

run_local_reviewer() {
    local source="$1"
    local model="$2"
    local out_file="$3"
    local model_enc
    model_enc="$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$model")"

    (
        lock_dir="$OMLX_LOCK_DIR"
        have_lock=0
        cleanup_lock() {
            if [[ "$have_lock" == "1" ]]; then
                rm -rf "$lock_dir"
            fi
        }
        trap cleanup_lock EXIT

        deadline=$((SECONDS + lock_timeout))
        case "$lock_dir" in
            ""|"/"|"/tmp"|"/private/tmp")
                json_fallback "$source" "Unsafe oMLX lock directory: $lock_dir"
                exit 0
                ;;
        esac
        while ! mkdir "$lock_dir" 2>/dev/null; do
            if [[ -f "$lock_dir/holder" ]]; then
                holder_pid="$(sed -n 's/^pid=//p' "$lock_dir/holder" 2>/dev/null | head -1)"
                if [[ -n "$holder_pid" && "$holder_pid" =~ ^[0-9]+$ ]] && ! kill -0 "$holder_pid" 2>/dev/null; then
                    printf '[guard] removing stale oMLX lock held by dead pid %s\n' "$holder_pid" >&2
                    rm -rf "$lock_dir"
                    continue
                fi
            fi
            if (( SECONDS >= deadline )); then
                json_fallback "$source" "Could not acquire oMLX guard lock for $model"
                exit 0
            fi
            sleep 1
        done
        have_lock=1
        current_pid="$(python3 -c 'import os; print(os.getppid())' 2>/dev/null || printf '%s' "$$")"
        {
            printf 'pid=%s\n' "$current_pid"
            printf 'model=%s\n' "$model"
            printf 'source=%s\n' "$source"
            date -u '+acquired_at=%Y-%m-%dT%H:%M:%SZ'
        } > "$lock_dir/holder"

        printf '[guard] loading %s\n' "$model" >&2
        curl -sf --connect-timeout 5 --max-time 30 -X POST "$OMLX_URL/admin/api/models/${model_enc}/load" \
            -H "Authorization: Bearer $OMLX_KEY" >/dev/null \
            || { json_fallback "$source" "$model load failed"; exit 0; }
        sleep "$settle_seconds"

        local result
        if [[ -n "$timeout_bin" ]]; then
            result="$(GUARD_REVIEW_TIMEOUT="$review_timeout" "$timeout_bin" "$review_timeout" python3 - "$model" "$source" "$label" "$context" "$truncated_note" "$subject_file" "$OMLX_URL" "$OMLX_KEY" <<'PY'
import json, os, pathlib, sys, urllib.request
model, source, label, context, truncated_note, subject_file, omlx_url, omlx_key = sys.argv[1:9]
diff_text = pathlib.Path(subject_file).read_text()
system = "You are a strict review guard. Return valid JSON only. No markdown fences. No newlines inside string values."
user = f"""Review label: {label}
Context: {context or 'None'}
{truncated_note}

Review this diff for correctness risks, missing verification, workflow contradictions, unsafe operations, and operational ambiguity. Findings only. If there are no actionable findings, return an empty findings array.

Diff:
{diff_text}

Return JSON exactly shaped as: {{"source":"{source}","findings":[{{"severity":"low|medium|high","file":"path or empty","issue":"string","recommendation":"string"}}],"notes":["string"]}}"""
payload = json.dumps({
    "model": model,
    "max_tokens": 1000,
    "temperature": 0.1,
    "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
}).encode()
req = urllib.request.Request(
    f"{omlx_url.rstrip('/')}/v1/chat/completions",
    data=payload,
    headers={"Authorization": f"Bearer {omlx_key}", "Content-Type": "application/json"},
)
resp = json.loads(urllib.request.urlopen(req, timeout=max(30, int(float(__import__("os").environ.get("GUARD_REVIEW_TIMEOUT", "120"))))).read())
print(resp["choices"][0]["message"]["content"].strip())
PY
            )" || result="$(json_fallback "$source" "$model inference failed or timed out")"
        else
            result="$(python3 - "$model" "$source" "$label" "$context" "$truncated_note" "$subject_file" "$OMLX_URL" "$OMLX_KEY" <<'PY'
import json, pathlib, sys, urllib.request
model, source, label, context, truncated_note, subject_file, omlx_url, omlx_key = sys.argv[1:9]
diff_text = pathlib.Path(subject_file).read_text()
system = "You are a strict review guard. Return valid JSON only. No markdown fences. No newlines inside string values."
user = f"Review label: {label}\nContext: {context or 'None'}\n{truncated_note}\n\nReview this diff for correctness risks, missing verification, workflow contradictions, unsafe operations, and operational ambiguity. Findings only. If there are no actionable findings, return an empty findings array.\n\nDiff:\n{diff_text}\n\nReturn JSON exactly shaped as: {{\"source\":\"{source}\",\"findings\":[{{\"severity\":\"low|medium|high\",\"file\":\"path or empty\",\"issue\":\"string\",\"recommendation\":\"string\"}}],\"notes\":[\"string\"]}}"
payload = json.dumps({"model": model, "max_tokens": 1000, "temperature": 0.1, "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}]}).encode()
req = urllib.request.Request(f"{omlx_url.rstrip('/')}/v1/chat/completions", data=payload, headers={"Authorization": f"Bearer {omlx_key}", "Content-Type": "application/json"})
resp = json.loads(urllib.request.urlopen(req, timeout=120).read())
print(resp["choices"][0]["message"]["content"].strip())
PY
            )" || result="$(json_fallback "$source" "$model inference failed")"
        fi

        printf '[guard] unloading %s\n' "$model" >&2
        if ! curl -sf --connect-timeout 5 --max-time 30 -X POST "$OMLX_URL/admin/api/models/${model_enc}/unload" \
            -H "Authorization: Bearer $OMLX_KEY" >/dev/null; then
            result="$(python3 - "$result" "$source" "$model" <<'PY'
import json, re, sys
raw, source, model = sys.argv[1], sys.argv[2], sys.argv[3]
raw = re.sub(r"^```(?:json)?\s*|\s*```$", "", raw.strip(), flags=re.I | re.S)
try:
    data = json.loads(raw)
except Exception:
    data = {"source": source, "findings": [], "notes": [raw[:500]]}
data.setdefault("findings", []).append({
    "severity": "medium",
    "file": "",
    "issue": f"{model} unload failed; oMLX may still have residual model state",
    "recommendation": "Check the oMLX server/model state before the next guard run."
})
print(json.dumps(data))
PY
            )"
        fi

        printf '%s\n' "$result"
    ) > "$out_file"
}

run_gemini_reviewer() {
    local out_file="$1"
    if ! command -v gemini >/dev/null 2>&1; then
        json_fallback "Gemini" "Gemini CLI not found" > "$out_file"
        return
    fi
    local gemini_help
    gemini_help="$(gemini --help 2>/dev/null || true)"
    if [[ "$gemini_help" != *"--approval-mode"* || "$gemini_help" != *"--sandbox"* ]]; then
        json_fallback "Gemini" "Gemini CLI does not advertise required --approval-mode and --sandbox flags" > "$out_file"
        return
    fi

    local result
    result="$(python3 - "$GEMINI_MODEL" "$gemini_timeout" "$label" "$context" "$truncated_note" "$subject_file" <<'PY'
import json, os, pathlib, subprocess, sys

model, timeout_s, label, context, truncated_note, subject_file = sys.argv[1:7]
timeout_s = int(timeout_s)
diff_text = pathlib.Path(subject_file).read_text()
prompt = f"""Review label: {label}
Context: {context or 'None'}
{truncated_note}

You are the Gemini leg of a three-reviewer guard. Do not execute tools, shell commands, or repository mutations. Review only the diff included below. Suggest actionable tests and risk areas, and return JSON only.

Diff:
{diff_text}

Return JSON exactly shaped as: {{"tests":[{{"name":"string","scenario":"string","expected":"string"}}],"risk_areas":["string"]}}"""

cmd = ["gemini", "--model", model, "--approval-mode", "plan", "--sandbox", "-p", prompt]
env = os.environ.copy()
env["GEMINI_CLI_TRUST_WORKSPACE"] = "true"
try:
    proc = subprocess.run(cmd, text=True, capture_output=True, timeout=timeout_s, check=False, env=env)
except subprocess.TimeoutExpired:
    print(json.dumps({"tests": [], "risk_areas": ["Gemini reviewer timed out"]}))
    raise SystemExit(0)

if proc.returncode != 0 or not proc.stdout.strip():
    detail = (proc.stderr or proc.stdout or "Gemini reviewer failed").strip().splitlines()[:3]
    print(json.dumps({"tests": [], "risk_areas": ["; ".join(detail) or "Gemini reviewer failed"]}))
    raise SystemExit(0)

print(proc.stdout.strip())
PY
    )" || result="$(json_fallback "Gemini" "Gemini reviewer failed")"
    printf '%s\n' "$result" > "$out_file"
}

if [[ "$skip_local" == "0" ]] && curl -sf --max-time 2 "$OMLX_URL/v1/models" -H "Authorization: Bearer $OMLX_KEY" >/dev/null 2>&1; then
    run_local_reviewer "Qwen" "$QWEN_MODEL" "$output_dir/qwen.json"
    qwen_finished_at=$SECONDS
else
    json_fallback "Qwen" "oMLX unavailable or local reviewers skipped" > "$output_dir/qwen.json"
    qwen_finished_at=$SECONDS
fi

if [[ "$skip_gemini" == "0" ]]; then
    run_gemini_reviewer "$output_dir/gemini.json"
else
    json_fallback "Gemini" "Gemini reviewer skipped" > "$output_dir/gemini.json"
fi

elapsed_since_qwen=$((SECONDS - qwen_finished_at))
if (( skip_local == 0 && elapsed_since_qwen < settle_seconds )); then
    sleep $((settle_seconds - elapsed_since_qwen))
fi

if [[ "$skip_local" == "0" ]] && curl -sf --max-time 2 "$OMLX_URL/v1/models" -H "Authorization: Bearer $OMLX_KEY" >/dev/null 2>&1; then
    run_local_reviewer "E4B" "$E4B_MODEL" "$output_dir/e4b.json"
else
    json_fallback "E4B" "oMLX unavailable or local reviewers skipped" > "$output_dir/e4b.json"
fi

python3 - "$output_dir" "$label" <<'PY'
import json, pathlib, re, sys

out = pathlib.Path(sys.argv[1])
label = sys.argv[2]

def parse_jsonish(path):
    raw = path.read_text(errors="replace").strip()
    raw = re.sub(r"^```(?:json)?\s*|\s*```$", "", raw, flags=re.I | re.S).strip()
    try:
        return json.loads(raw)
    except Exception as exc:
        return {
            "source": path.stem,
            "findings": [{
                "severity": "medium",
                "file": "",
                "issue": f"{path.name} did not return parseable JSON: {exc}",
                "recommendation": "Read the raw reviewer output and decide whether any finding is valid."
            }],
            "notes": [raw[:500]],
        }

def norm(text):
    return re.sub(r"[^a-z0-9]+", "", (text or "").lower())

merged = []
notes = []
seen = set()
for source, filename in [("Qwen", "qwen.json"), ("Gemini", "gemini.json"), ("E4B", "e4b.json")]:
    data = parse_jsonish(out / filename)
    if "findings" in data:
        for finding in data.get("findings") or []:
            issue = finding.get("issue") or ""
            key = norm((finding.get("file") or "") + " " + issue)[:160]
            if key and key not in seen:
                seen.add(key)
                finding["source"] = finding.get("source") or source
                merged.append(finding)
    else:
        for risk in data.get("risk_areas") or []:
            key = norm(risk)[:100]
            if key and key not in seen:
                seen.add(key)
                merged.append({
                    "source": source,
                    "severity": "medium",
                    "file": "",
                    "issue": risk,
                    "recommendation": "Review this risk area and add/fix tests or docs if it applies.",
                })
        for test in data.get("tests") or []:
            name = test.get("name") or "Suggested test"
            scenario = test.get("scenario") or ""
            expected = test.get("expected") or ""
            key = norm(name + scenario + expected)[:100]
            if key and key not in seen:
                seen.add(key)
                notes.append(f"[{source}] {name}: {scenario} -> {expected}")
    for note in data.get("notes") or []:
        notes.append(f"[{source}] {note}")

severity_rank = {"high": 0, "medium": 1, "low": 2}
merged.sort(key=lambda f: (severity_rank.get((f.get("severity") or "").lower(), 3), f.get("file") or "", f.get("issue") or ""))

result = {
    "label": label,
    "findings": merged,
    "notes": notes,
}
(out / "merged.json").write_text(json.dumps(result, indent=2) + "\n")

print(f"# Guard Review: {label}")
print(f"Raw output: {out}")
print(f"Findings: {len(merged)}")
for finding in merged:
    source = finding.get("source", "?")
    severity = finding.get("severity", "?")
    file = finding.get("file") or "(no file)"
    issue = finding.get("issue") or ""
    recommendation = finding.get("recommendation") or ""
    print(f"- [{source}] {severity} {file}: {issue}")
    if recommendation:
        print(f"  Recommendation: {recommendation}")
if notes:
    print(f"Notes: {len(notes)}")
    for note in notes[:20]:
        print(f"- {note}")
PY
