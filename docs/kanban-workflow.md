# FoodLog Kanban Workflow

The `FoodLog` kanban board is the active work queue. `PLAN.md` remains the product source of truth, and Room remains the app data source of truth. Do not let the board, docs, rendered UI text, or CSV exports become alternate product state.

## Starting Work

1. Read `PLAN.md` before product changes.
2. Check the board and prefer cards closest to completion before starting new work: `test`, `impl_review`, `impl`, `plan_review`, `plan`, then `todo`.
3. For a `todo` card, read the task description and check whether the requested behavior is already implemented before changing code.
4. If scope or acceptance is unclear, ask for context before implementing.
5. Move the card through the kanban lifecycle as work actually progresses.

## Lifecycle

Use the board columns as the current state of the task:

- `todo`: known work that has not started.
- `plan`: requirements are being clarified or a lightweight approach is being written.
- `plan_review`: review the approach before implementation, mainly for larger or riskier work.
- `impl`: implementation or documentation changes are being made.
- `impl_review`: code/docs are ready for review.
- `test`: verification is running or awaiting confirmation.
- `done`: merged, verified, and no follow-up is needed for that card.

For FoodLog's usual L2/L3 cards, prefer a lightweight written plan before implementation. L1 docs or housekeeping cards may move directly to implementation when the task is clear.

## Triage Attention

Brain-dump and triage-source cards may use attention tags and `ATTENTION:` notes from `docs/brain-dump-triage.md` to show what kind of user or agent attention is needed without adding board columns. The attention tag is metadata; `plan_review` is the board-column enforcement point for triage-source cards that need user/reviewer attention.

Use these states with the normal lifecycle:

- `needs-context`: ask the narrowest blocking question and keep the card out of implementation until answered.
- `needs-grill-me`: move to `plan_review` when adversarial review or user judgment is the next useful step.
- `ready-to-read`: move to `plan_review` when a report, research result, or triage output is ready for the user to inspect.
- `ready-to-convert`: move to `plan_review`; keep generated tasks as drafts until approved, unless the direct-creation rules in `docs/brain-dump-triage.md` apply.
- `converted`: note the generated task ids, verify back-references on those cards, then move the source card to `done` when no source-card follow-up remains.
- `abandoned`: record the reason and no-follow-up decision, then move the source card to `done`; do not hard-delete unless explicitly requested.

These attention tags do not make a card implementation-ready by themselves. If a dump produces real product or workflow work, create separate bounded cards or explicitly approve conversion before moving that work through `impl`.

Every `needs-context`, `needs-grill-me`, `ready-to-read`, `ready-to-convert`, `converted`, and `abandoned` tag requires a current `ATTENTION:` note. For `triage-source` cards, move `needs-context`, `needs-grill-me`, `ready-to-read`, and `ready-to-convert` states to `plan_review`; do not continue along the normal implementation lifecycle until the attention state is resolved or converted into separate cards. If the attention tag and board column conflict, fix the note or move the card before starting implementation.

## Documentation

Update docs in the same card when the change affects product direction, data contracts, user workflow, review process, verification commands, or operational safety. Avoid duplicating the task board in `PLAN.md`; record durable direction there and keep short-lived status on the board.

Useful anchors:

- `PLAN.md`: product direction, boundaries, backlog shape, data contracts, and verification expectations.
- `AGENTS.md`: agent working rules and project-specific safety notes.
- `README.md`: short repo orientation and links for humans.
- `docs/`: workflow checklists or deeper operational notes.
- `docs/brain-dump-triage.md`: workflow for turning rough dumps into reviewable task candidates.

## Branch, PR, Review, Merge

After each completed board item:

1. Commit the finished change on a `codex/` branch.
2. Push the branch and open a PR.
3. Decide whether CodeRabbit is required for this PR. CodeRabbit auto-review is disabled in `.coderabbit.yaml`; request it manually with `@coderabbitai review`, or `@coderabbitai full review` when a complete fresh pass is needed. Skipped-review status comments and commit statuses are also disabled to avoid notification email noise, so silence from CodeRabbit is expected unless it was explicitly requested. If a requested CodeRabbit review does not respond within 15 minutes or reports quota/unavailability, treat it as unavailable and run the guard.
4. If CodeRabbit is not requested, unavailable, or quota-limited and waiting would block progress, run the multi-review guard before merge. A deliberate no-CR decision or quota/unavailable response is enough to start the guard; do not wait indefinitely for CodeRabbit. Use all three reviewers where available because they catch different subsets of issues.
5. Merge and deduplicate review findings, resolve valid comments, rerun the relevant verification, merge the PR, and only then move on to the next card.

During timed review waits, state that you are waiting and stay quiet until there is a result or a blocker.

Use CodeRabbit manually for broad product code, data contracts, export behavior, database migrations, workflow scripts, security-sensitive changes, PRs touching more than three production source files, or anything already producing ambiguous guard findings. "CodeRabbit not requested" is valid only for work outside those categories, and only when the multi-review guard completes or any unavailable reviewer is recorded. For all PR-backed board work where CodeRabbit is not used, run the multi-review guard and record that CodeRabbit was not requested.

## Done Checklist

Before moving a card to `done`, complete these checks in order:

- The branch has been pushed, the PR is merged or explicitly not needed, and GitHub shows the PR closed/merged when one was opened.
- CodeRabbit review is resolved/clean when requested, or the fallback review result note records that CodeRabbit was not requested, quota-limited, or unavailable and the guard was run.
- Valid review findings were fixed or explicitly rejected with a reason.
- Relevant verification was rerun after the final change, and the result is recorded on the card.
- Docs were updated when workflow, product direction, or verification expectations changed, or the card notes say docs were not needed.
- The local worktree is clean on `main` after merge/pull, with no unrelated user changes staged or reverted.
- The board has the implementation/test/review notes needed for the next agent to understand what happened.
- Refresh the project brief if the card changed workflow policy, product direction, or current project state; otherwise leave a short note when the decision is not obvious.

## Project Brief Refresh

Refresh the kanban project brief after a meaningful batch of completed cards: after about three completed workflow/product cards, after any workflow policy or product-direction change, or whenever the next likely work changes. The done checklist is the per-card reminder; this section is the batch summary update. The brief should stay short enough to be useful as startup context without rereading all docs; 200-500 characters is a guideline, not a reason to drop essential context.

Use this shape:

```text
<current product/workflow state>. Recent decisions: <1-2 decisions>. Current direction: <next likely work>.
```

To update it:

1. Read the current project record and recently completed cards.
2. Draft a 200-500 character replacement that captures current state, recent decisions, and active direction.
3. Patch the project brief through the kanban project API:

```sh
curl -s -X PATCH "$BASE_URL/api/projects/FoodLog" \
  -H 'Content-Type: application/json' \
  -d '{"brief":"<updated brief>"}'
```

If the API is unreachable or the patch fails, do not mark the card done as if the brief was refreshed. Add a card note with the attempted brief and the failure so the next agent can retry.

4. Add a card note saying whether the brief was updated or why it was left alone.

## Multi-Review Guard

For implementation review fallback, do not rely on one replacement reviewer. Prefer the three-way guard used by the kanban Shield step:

- `Qwen3.6-27B-4bit` via the local oMLX server.
- `gemma-4-E4B-it-MLX-8bit` via the local oMLX server.
- Gemini CLI in read-only `--approval-mode plan` for Shield/test-advisor output, or `gemini-review` for manual PR diff review.

Use the repo wrapper for normal guard runs:

```sh
scripts/review-guard review --context "Short description of the change"
```

`scripts/review-guard review` delegates to the shared Kanban review-guard backend. Use `scripts/review-guard doctor` to validate the project policy in `.codex/review-guard.json`, and `scripts/review-guard run --dry-run` to inspect configured local verification commands. The configured Gradle commands assume `JAVA_HOME` is already exported in the shell when this machine requires a specific JDK.

The review backend captures raw reviewer output in a private `mktemp` directory under the system temp path, writes a merged JSON summary, and prints deduplicated findings. Include new files in the reviewed diff before running it; for untracked files, stage them or use `git add -N` so reviewers do not see docs without the implementation. The Gemini leg is called directly in sandboxed plan mode with its own timeout and `GEMINI_CLI_TRUST_WORKSPACE=true` for headless execution; do not use broad auto-approval, YOLO, or command-execution approval modes for automated guard review.

Run the guard sequentially. oMLX model load/unload calls are shared machine state, so guard runs should take an interprocess lock such as `/tmp/omlx-guard.lock.d` before loading a local model. Do not assume concurrent sessions will queue safely at the API layer. Hold the lock through load, settle, review inference, unload, and cleanup; the shared Kanban review backend uses `OMLX_LOCK_DIR` as a portable lock directory, records holder metadata, reclaims locks whose holder pid is dead, and releases the lock when the guarded block exits. Linux `flock` is not required for the repo wrapper, so fresh macOS agents do not need an extra Homebrew package to participate.

To inspect another session's local-model guard run without taking the lock:

```sh
scripts/review-guard --lock-status
```

The status output includes the lock path, holder pid and liveness, reviewer source, model, reviewed diff label, acquisition time, age, missing metadata fields, process command, and raw holder metadata. While waiting for a busy lock, the wrapper prints this same holder summary about every 10 seconds. If the holder pid is dead, the next guard run will reclaim the lock automatically.

For manual recovery, first run `scripts/review-guard --lock-status`, then inspect any reported live pid with:

```sh
ps -p <pid> -o pid,ppid,command
```

Wait if the pid is alive and the command belongs to an active guard run. If the holder pid is dead, prefer the guarded cleanup command:

```sh
scripts/review-guard --clear-stale-lock
```

The cleanup command refuses unsafe lock paths, live pids, and holder metadata without a dead numeric pid. If the lock directory exists with no holder metadata, the command only clears it after the orphan-lock grace period, which defaults to 30 seconds and can be tuned with `GUARD_ORPHAN_LOCK_SECONDS`. If the holder metadata is corrupt, inspect running guard processes before any manual cleanup:

```sh
ps ax -o pid,ppid,command | grep '[g]uard-review.sh'
```

Expected edge cases: `--lock-status` exits successfully when no lock exists, reports missing metadata fields instead of failing on a partial holder file, and reports lock age when the directory exists without a holder file. `--clear-stale-lock` refuses live pids, unsafe paths such as `/tmp` or `/`, and young orphan locks.

When swapping local oMLX models, load one model, wait about 10 seconds for it to settle, run its review, unload it, then move to Gemini before loading the next oMLX model. After the final local-model review, unload that model too so the next guard run starts from a clean oMLX state. Prefer `oMLX -> Gemini -> oMLX` ordering when practical, because the Gemini pass usually gives the local server enough time between model swaps; if Gemini is skipped or returns unusually quickly, ensure about 10 seconds has passed or perform a health check before loading the second oMLX model.

Treat all three as advisory. The agent remains responsible for judging findings, checking the code directly, applying fixes, and rerunning tests. If the oMLX server is unreachable, a model fails to load, or a reviewer command fails or times out, record that reviewer as unavailable and continue with the remaining reviewers rather than blocking indefinitely.

If the guard is hard-killed while a local model is loaded, oMLX may retain residual model state. Check the oMLX server before the next local-model review and manually unload the model if needed.

Gemini-only review is acceptable when local oMLX is unavailable, or for docs-only changes that do not alter commands, scripts, workflow policy, data contracts, or verification expectations.

### Fallback Review Result Note

When CodeRabbit is not requested, quota-limited, or unavailable, add a kanban card note with this shape before merging:

```md
Fallback review result:
- CodeRabbit: not requested because <reason> | quota-limited until <time> | unavailable
- Guard command: `<exact command>`
- Reviewers completed: Qwen <result>, Gemini <result>, E4B/gemma-4-E4B-it-MLX-8bit <result>
- Reviewers unavailable/skipped: <none | reviewer and reason>
- Findings accepted: <finding -> fix commit/change>
- Findings rejected: <finding -> reason it was false positive/out of scope>
- Verification rerun: `<commands>` -> <result>
- Merge decision: <why this is safe to merge now>
```

Keep the note factual and short. If a reviewer emits raw risks that were judged false positives, name the reason instead of silently dropping them.

## Verification

Choose verification based on the changed surface. The default local check for this repo uses the JDK path documented in `README.md` and `AGENTS.md`; on another machine, use an equivalent installed JDK path:

```sh
JAVA_HOME=/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest
```

Use broader compile, connected, or smoke checks when Android, Room, Compose, export behavior, or device install behavior is touched. For docs-only workflow changes, link checks, `git diff --check`, and review may be enough.

## Stale Work Audit

Before cleanup work, or when multiple sessions have been active, run the non-destructive stale work audit:

```sh
scripts/stale-work-audit.sh --fetch
```

The audit reports open PRs, remote `codex/*` branches for the selected remote, local branches whose upstream is gone, worktree paths and dirty status, and kanban cards still outside `done`. It does not delete or move anything; even `--fetch` avoids pruning refs. That means `--fetch` updates new and changed refs, but it will not remove local tracking refs for branches deleted on the remote. If you need exact gone-upstream detection, decide deliberately whether to run a separate `git fetch --prune` first. Treat the audit output as a triage list: verify whether each item is merged, intentionally parked, or still active before running any cleanup command.

## Safe Work Cleanup

After auditing stale work, use the cleanup helper in dry-run mode first:

```sh
scripts/safe-work-cleanup.sh
```

It reports clean ancestry-merged worktrees, local gone-upstream branches, and ancestry-merged remote `codex/*` branch candidates. Destructive local cleanup requires `--apply --yes`; remote branch deletion additionally requires `--delete-remote`. The helper refuses the primary worktree, dirty worktrees, the current branch, branches checked out in worktrees, and branches that are not ancestors of the configured `<remote>/main` ref or the `--main-ref` override. Squash-merged branches usually are not ancestors of `main`, so the helper keeps them for manual verification instead of guessing.

## Local Config

Keep local board config and generated board files out of git:

- `.codex/kanban.json`
- `.claude/kanban.json`
- `kanban-board/`

Global board auth and URL live outside the repo in `~/.claude/kanban-auth`.
