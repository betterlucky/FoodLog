# Brain-Dump Triage Workflow

This workflow is for turning rough user notes, tangled ideas, issues, tangents, and context into usable kanban tasks without forcing the user to pre-structure the input.

The output is planning material and draftable tasks. It must not override `PLAN.md`, silently change product direction, or create work that exposes personal FoodLog data.

## Goals

- Preserve the user's rough wording before interpretation.
- Separate concrete tasks from decisions, unknowns, parked ideas, and context.
- Produce kanban-ready tasks with title, goal, scope, acceptance criteria, dependencies, level, priority, tags, and verification notes.
- Let different reviewers catch different failure modes without turning triage into a long ceremony.
- Keep user review focused on product direction and ambiguous tradeoffs, not mechanical task formatting.

## Intake Shape

A dump can arrive as a chat message, a kanban note, or a dedicated triage card. Keep the raw dump attached to the triage record or referenced in the card notes.

Useful optional headings:

```md
Raw dump:
<unstructured text>

Must keep:
<constraints or non-negotiables>

Nice to have:
<preferences>

Known context:
<links, files, prior task ids, PRs>
```

Do not require those headings before accepting a dump. If the input is messy, preserve it and infer structure later.

## Triage Stages

1. Capture the raw dump without rewriting it.
2. Extract claims, requirements, questions, constraints, and tangents.
3. Cluster related items into candidate work packages.
4. Split candidates until each task has one clear outcome and a bounded verification path.
5. Classify each candidate as one of:
   - `task`: ready to become a kanban card.
   - `decision`: needs user choice or plan review before implementation.
   - `research`: needs exploration before implementation.
   - `parking_lot`: worth keeping, not current work.
   - `context`: useful background, not a task.
6. Assign level, priority, tags, dependencies, and a suggested next status.
7. Review for duplicates against the existing board and recently completed cards.
8. Add approved tasks or return a triage report.

## Model Roles

Use models as independent lenses, not as a voting system:

- Codex: repo-aware orchestration, duplicate checks, board updates, and final task wording.
- Claude: narrative structure, requirements clarity, and user-intent preservation when available.
- Gemini: adversarial review for contradictions, missing acceptance criteria, unsafe assumptions, and over-broad tasks.
- Local oMLX models: cheap first-pass clustering, title drafts, missing-test ideas, and privacy-preserving review of bounded excerpts that fit the local prompt without including secrets, raw health logs, exports, tokens, or personal identifiers.

For sensitive or personal content, prefer local processing unless the user explicitly approves sending the content to an external model. If any part of a dump contains personal, health, credential, export, or other sensitive content, treat the whole dump as local-only until a human or local-only pass separates safe excerpts from sensitive material. No raw sensitive content may be sent to an external model without explicit user approval for that specific content. Scrubbing must happen locally or manually before any external model call. External model use is allowed only after the dump is scrubbed of personal data or the user has explicitly approved sharing the specific raw content. Record any external-model use in the triage report. For local-only processing, record the local endpoint or wrapper used; if the local model is unavailable, halt triage and ask for user direction rather than falling back to a cloud model. For non-sensitive dumps, external models may be used normally, with model use recorded.

## Review Policy

Default behavior: return a triage report for user review before adding new tasks.

Direct board creation is acceptable when one of these is true:

- The user explicitly says to add the resulting tasks to the board.
- The user request or source card explicitly says the dump is workflow/tooling work already delegated to agents, the generated cards stay within `kanban`, `docs`, `cleanup`, `review`, `triage`, or `automation` tags, and the tasks do not change product behavior, data contracts, privacy policy, review policy, or the triage workflow's own review gates.
- A task is purely administrative and does not change product direction, data contracts, app behavior, or review policy.

The privacy gate overrides every direct-creation rule. If a dump contains personal, health, credential, export, or other sensitive content, either keep triage local-only or ask before using external models, even for workflow/tooling tasks.

User review is required before adding or starting tasks that:

- Change FoodLog product direction or scope.
- Introduce AI/backend/cloud behavior.
- Touch data contracts, exports, persistence, privacy, or local-first boundaries.
- Require a user preference or tradeoff decision.
- Depend on unclear external tools, accounts, or device state.
- Update docs in a way that changes product direction, workflow rules, privacy rules, or review policy.
- Reference sensitive context or touch privacy, regardless of tags.

When direct creation is used, add a note to each generated task with the source triage card or dump summary.

## Task Template

Each generated task should aim for this shape:

```md
Goal:
<one outcome>

Scope:
<files, workflow, or behavior likely involved>

Acceptance criteria:
- <observable result>
- <edge case or non-goal>
- <docs/verification expectation>

Dependencies:
<task ids or none>

Exclusions:
<related ideas intentionally left out>
```

Use L1 for small docs/admin work, L2 for straightforward implementation with known shape, and L3 for uncertain product behavior, multi-agent planning, or changes that need plan review.

## Duplicate Checks

Before adding generated cards, check:

- exact or near-exact title overlap on active board cards;
- cards completed in the last 30 days whose implementation notes already cover the request;
- `PLAN.md` for product-direction items already parked or sequenced;
- `docs/kanban-workflow.md` and this file for workflow items already documented.

Mark a candidate as duplicate when it has the same intended outcome as an existing card or completed card, even if the wording differs. Use Codex or Gemini to check semantic overlap when exact title matching is not enough, and hold low-confidence duplicate decisions for user review. For sensitive dumps, duplicate checks must use local models, exact matching, or already-available local context only; do not send sensitive excerpts to external models for semantic matching. If a candidate only overlaps partly, reference the existing task and narrow the new candidate's scope.

## Output Format

A triage report should include:

- Raw dump reference.
- Summary of interpreted intent.
- Candidate tasks.
- Decisions needing user review.
- Research/exploration items.
- Parking-lot items.
- Duplicates or already-done items.
- Recommended next action.

If tasks are added directly, the report should list the new task ids and any items held back for review.

## Candidate Follow-Up Tasks

The planning work suggests these implementation cards:

### Add Brain-Dump Intake And Triage Report Templates

Goal: add reusable templates for raw brain-dump intake and triage reports.

Acceptance criteria:

- Templates preserve raw input before interpretation.
- Templates separate tasks, decisions, research, parking-lot items, and context.
- Templates state when user review is required.

### Prototype Brain-Dump Triage Helper Command

Goal: explore a repo or skill helper that reads a raw dump and emits draft task JSON plus a triage report.

Acceptance criteria:

- Generated JSON can be inspected before posting to the board.
- Product-direction tasks are not added automatically without explicit approval.
- The helper records source dump references on generated tasks.
- The helper preflights local model availability before local-only processing and halts if required local processing is unavailable.

### Add Duplicate Detection Guidance For Generated Tasks

Goal: make duplicate and already-done checks consistent.

Acceptance criteria:

- Triage reports list duplicate or overlapping active cards.
- Recently done cards and `PLAN.md` are checked before new cards are added.
- Partial overlaps are narrowed instead of duplicated.

### Add Privacy Review Gate For External-Model Triage

Goal: prevent accidental sharing of personal, health, or sensitive project context during triage.

Acceptance criteria:

- Sensitive dumps default to local processing.
- External-model use requires explicit approval or scrubbed input.
- Triage reports record which model classes were used.
