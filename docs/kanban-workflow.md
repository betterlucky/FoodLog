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

## Documentation

Update docs in the same card when the change affects product direction, data contracts, user workflow, review process, verification commands, or operational safety. Avoid duplicating the task board in `PLAN.md`; record durable direction there and keep short-lived status on the board.

Useful anchors:

- `PLAN.md`: product direction, boundaries, backlog shape, data contracts, and verification expectations.
- `AGENTS.md`: agent working rules and project-specific safety notes.
- `README.md`: short repo orientation and links for humans.
- `docs/`: workflow checklists or deeper operational notes.

## Branch, PR, Review, Merge

After each completed board item:

1. Commit the finished change on a `codex/` branch.
2. Push the branch and open a PR.
3. Wait for CodeRabbit review unless the change clearly does not need one.
4. If CodeRabbit is skipped, unavailable, or quota-limited and waiting would block progress, run the multi-review guard. A confirmed skip or quota/unavailable response is enough to start the guard; do not wait indefinitely for CodeRabbit. Use all three reviewers where available because they catch different subsets of issues.
5. Merge and deduplicate review findings, resolve valid comments, rerun the relevant verification, merge the PR, and only then move on to the next card.

During timed review waits, state that you are waiting and stay quiet until there is a result or a blocker.

## Multi-Review Guard

For implementation review fallback, do not rely on one replacement reviewer. Prefer the three-way guard used by the kanban Shield step:

- `Qwen3.6-27B-4bit` via the local oMLX server.
- `gemma-4-E4B-it-MLX-8bit` via the local oMLX server.
- Gemini via `gemini-shield` for Shield/test-advisor output, or `gemini-review` for PR diff review.

Run the guard sequentially. oMLX model load/unload calls are shared machine state, so guard runs should take an interprocess lock such as `/tmp/omlx-guard.lock` before loading a local model. Do not assume concurrent sessions will queue safely at the API layer. Hold the lock through load, settle, review inference, unload, and cleanup; with the usual `flock` subshell pattern, the lock is released when that guarded block exits.

When swapping local oMLX models, load one model, wait about 10 seconds for it to settle, run its review, unload it, then move to Gemini before loading the next oMLX model. After the final local-model review, unload that model too so the next guard run starts from a clean oMLX state. Prefer `oMLX -> Gemini -> oMLX` ordering when practical, because the Gemini pass usually gives the local server enough time between model swaps; if Gemini is skipped or returns unusually quickly, ensure about 10 seconds has passed or perform a health check before loading the second oMLX model.

Treat all three as advisory. The agent remains responsible for judging findings, checking the code directly, applying fixes, and rerunning tests. If the oMLX server is unreachable, a model fails to load, or a reviewer command fails or times out, record that reviewer as unavailable and continue with the remaining reviewers rather than blocking indefinitely.

## Verification

Choose verification based on the changed surface. The default local check for this repo uses the JDK path documented in `README.md` and `AGENTS.md`; on another machine, use an equivalent installed JDK path:

```sh
JAVA_HOME=/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest
```

Use broader compile, connected, or smoke checks when Android, Room, Compose, export behavior, or device install behavior is touched. For docs-only workflow changes, link checks, `git diff --check`, and review may be enough.

## Local Config

Keep local board config and generated board files out of git:

- `.codex/kanban.json`
- `.claude/kanban.json`
- `kanban-board/`

Global board auth and URL live outside the repo in `~/.claude/kanban-auth`.
