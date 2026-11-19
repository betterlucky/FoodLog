# Codex Handover

This note preserves useful context from older Codex project threads that may stay attached to the previous `Documents/FoodLog` project entry. It is not the source of truth. Prefer current code, `PLAN.md`, `AGENTS.md`, and explicit user instructions when they disagree.

## Current Product Understanding

- FoodLog is a private, local-first Android app for daily food logging.
- Room is the source of truth. Raw entries, confirmed food rows, shortcuts, products, daily weight, daily status, and exports should all be generated from database state rather than UI text or old CSV files.
- The app is intended to be dependable before it is clever. Existing local workflows should be made faster, clearer, and harder to misuse before adding AI features.
- The current core includes deterministic shortcut parsing, pending resolution, logged-item editing/removal, daily weight, daily close/export state, Lodestone CSV export, local OCR label logging, Room-backed history suggestions, and full-history live journal export.
- Barcode lookup/Open Food Facts was explored and deliberately parked. Do not reintroduce barcode/cache schema unless the product plan is explicitly reopened.

## Product Decisions From Prior Threads

- **Daily workflow first.** The Today screen is the main surface. Most polish should make everyday logging, resolving, editing, closing, and exporting more reliable.
- **Shortcuts are user portions.** A shortcut should represent a repeatable human portion such as a cup, wrap, pot, bowl, or serving where possible. Gram/ml math can exist internally, but saved defaults should not become unintuitive per-gram entries unless the user really wants that.
- **History suggestions are reviewed hints.** Historical Room rows can help resolve repeated ambiguous foods, but they should be presented as candidates with enough context to review. They should not silently auto-log.
- **Live journal is replaceable, not canonical.** The full-history journal export is for external review/analysis. It should be regenerated from Room, not treated as a second database or append-only truth.
- **Lodestone CSV remains the daily handoff.** Keep `Downloads/FoodLogData/food_log_YYYY-MM-DD.csv` stable until Lodestone changes its importer.
- **AI is later.** AI may eventually help with unknown meals, plate photos, partial labels, and user-initiated questions, but it should come after deterministic shortcuts, OCR/manual flows, local history suggestions, and export trust are solid.

## Completed Tracks Worth Remembering

- Day navigation and date handling were tightened so the selected food day matters and the app avoids silently logging to the wrong day.
- Logged item edit/remove flows and pending entry resolution are part of the core workflow, not edge cases.
- Daily close/export trust was a major concern: additions, edits, removals, pending resolutions, and weight changes should mark a previously exported day stale.
- The Android smoke script was made data-safety aware. Do not replace it with plain `adb uninstall`; preserve app data unless the user explicitly accepts data loss.
- OCR label scanning is local/on-device via ML Kit and should remain conservative. Partial label success is better than guessed nutrition.
- Room-backed history suggestions and live journal export are implemented and should be kept stable while quality improves.
- ReviewGuard is available as optional standalone quality tooling.

## Parked Or Later Ideas

- Barcode/Open Food Facts lookup.
- Leftovers and container flows.
- Recipe/compound-meal shortcuts.
- In-app charts and broader nutritional analysis.
- AI conversation, meal ideas, and nutrition advice.
- Recovery journal or replayable app-data rescue beyond the current guarded smoke/install workflow.

## Safety Notes

- Do not commit personal food logs, exported CSVs, pulled app databases, local SDK paths, keystores, tokens, or API keys.
- Treat `food_log_full_2026-04-04_to_2026-04-23.csv` as synthetic fixture/context only.
- For install/signature problems, use `scripts/android-smoke.sh` or take an app-private backup before uninstalling.
- Export tests should assert data comes from Room rows, not rendered UI text.

## Useful Starting Points

- Read `AGENTS.md` first for working rules.
- Read `PLAN.md` for product direction and data contracts.
- Use `docs/android-smoke-scenario.md` after running `scripts/android-smoke.sh`.

## Old Codex Thread Index

These old threads are useful background if they remain visible under the previous Codex project entry:

- Initial FoodLog implementation and local-first architecture.
- Project review and plan trimming.
- OCR scanner testing.
- PR review comments and parser/export cleanup.
- Day navigation and selected-date behaviour.
- Guarded reinstall/smoke script data safety.
- Meal breakdowns and recipe horizon.
- Daily close/export trust.
- Append-log/live journal export.
- Launch flicker between today/tomorrow.
- Historical fallback review setup.
