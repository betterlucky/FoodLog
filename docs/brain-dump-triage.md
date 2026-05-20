# Brain-Dump Triage Workflow

This workflow is for turning rough user notes, tangled ideas, issues, tangents, and context into usable kanban tasks without forcing the user to pre-structure the input.

The output is planning material and draftable tasks. It must not override `PLAN.md`, silently change product direction, or create work that exposes personal FoodLog data.

## Goals

- Preserve the user's rough wording before interpretation.
- Separate concrete tasks from decisions, unknowns, parked ideas, and context.
- Produce kanban-ready tasks with title, goal, scope, acceptance criteria, dependencies, level, priority, tags, and verification notes.
- Let different reviewers catch different failure modes without turning triage into a long ceremony.
- Keep user review focused on product direction and ambiguous tradeoffs, not mechanical task formatting.

## Explicit Intake

Ordinary kanban-linked chat is context, not automatic intake. Do not harvest rough ideas, testing notes, or tangents from normal conversation unless the user explicitly says the message is a brain dump, submits it through the board's Brain dump new-card mode, or attaches it to an existing `triage-source` card.

If normal conversation starts to look like a separate dump but is not explicit, ask a short confirmation such as "Should I treat that as a brain dump?" when triage would create or park new work. Useful signals include multi-topic notes that are not a direct answer to the current task, testing feedback mixed with unrelated future ideas, phrases like "dump", "notes", "while I remember", or a message whose likely output would be several candidate cards. If the notes are clearly just context for the current active task, use them as task context and do not create a triage-source card.

Explicit intake sources:

- Web board Brain dump mode in the new-card dialog.
- A card already tagged `triage-source`.
- A direct user request such as "treat this as a brain dump" or "triage this dump".

When intake is explicit, preserve the raw text before interpretation. The source card is not implementation-ready; it is a triage container that can later become `needs-context`, `needs-grill-me`, `ready-to-read`, `ready-to-convert`, `converted`, or `abandoned`.

Do not rewrite the `Raw dump` field when inferring structure. Put interpretation, clusters, decisions, and proposed tasks in the triage report so the raw source remains auditable.

## Source Template

A dump can arrive as a chat message, a kanban note, or a dedicated triage card. Keep the raw dump attached to the triage record or referenced in the card notes.

Use this source-card shape when creating a triage-source card:

```md
Brain dump source:
<short label or generated title>

Raw dump:
<unstructured text>

Must keep:
<constraints or non-negotiables>

Nice to have:
<preferences>

Known context:
<links, files, prior task ids, PRs>

Intake notes:
- Source: <web board | chat | existing card/note>
- Created by: <human name/handle or agent name>
- Initial tags: `triage-source`, `brain-dump`, `triage`
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
6. Assign level, priority, attention tags, dependencies, and a suggested next status.
7. Review for duplicates against the existing board and recently completed cards.
8. Add approved tasks or return a triage report.

## Attention States

Use tags and notes for triage attention state before adding new kanban columns. A triage-source card can move through these states while the actual implementation candidates remain draft material until they are approved or created as separate cards.

Core tags:

- `triage-source`: raw dump has been captured and should not be treated as implementation-ready.
- `needs-context`: triage is blocked on missing facts, links, device/account state, or a user preference.
- `needs-grill-me`: the idea is too half-formed or risky to convert; run adversarial questions before task creation.
- `ready-to-read`: research, analysis, or a triage report is ready for the user to review.
- `ready-to-convert`: candidate cards are drafted and can be created after user approval or an explicit direct-creation rule.
- `converted`: approved follow-up cards were created; keep source references on the generated cards.
- `abandoned`: the dump or candidate was intentionally parked with no expected follow-up.

`ATTENTION:` notes are the user-facing signal. Every `needs-context`, `needs-grill-me`, `ready-to-read`, `ready-to-convert`, `converted`, and `abandoned` state must have a current `ATTENTION:` note. The tag is the machine/filter signal; the note explains the state for the user and next agent. Update the tag and latest note in the same turn. If they disagree, pause implementation and fix the card state first.

```md
ATTENTION: <tag>
Blocks: <yes|no>
Ask: <specific question or review request>
Ready to read: <summary/report/card ids, if applicable>
Next agent action: <what to do if the user does not respond yet>

Ambiguity level:
<low | medium | high, with one sentence explaining why>
```

Use `Blocks: yes` when implementation or task creation cannot proceed without the requested user/reviewer action. Use `Blocks: no` when the note is informational, such as a ready-to-read report that does not block unrelated work or a converted/abandoned final state.

Keep the ask narrow. `needs-context` should name the smallest missing input needed to continue and avoid bundled questions; split broad uncertainty into a `needs-grill-me` review instead. `ready-to-read` should point to the report or result and say whether it blocks implementation. `ready-to-convert` should list the candidate task titles or draft ids and wait for approval unless direct board creation is allowed by the Review Policy below: explicit user approval to add tasks, delegated low-risk workflow/tooling triage within the allowed tags, or purely administrative work. Low-risk direct creation means small docs, cleanup, or workflow/tooling follow-ups that do not change product behavior, data contracts, privacy rules, review policy, or the triage gates themselves. To avoid stale ghost tasks, the next agent should either create the approved cards, move the source back to `needs-context` with a narrow ask, or record why the candidates are being abandoned. `converted` should list generated task ids and confirm those cards link back to the source dump. `abandoned` should record the reason, no-follow-up decision, and whether deletion was explicitly requested.

Moving a triage-source card to `plan_review` is appropriate when the next step is user or reviewer judgment rather than implementation, especially for `needs-grill-me`, `ready-to-read`, `ready-to-convert`, and blocked `needs-context` states. If the card is still in `todo` or `plan` when a blocking attention state is discovered, update the tag, add the `ATTENTION:` note, and move it to `plan_review` before asking for review or context so the board column and attention tag tell the same story. Do not move a triage-source card to `impl` merely because it contains plausible tasks; create or approve implementation cards first.

An agent assigned a `triage-source` card is assigned triage, not implementation. If the dump contains a ready-looking checklist, the next action is still `ready-to-convert`, `converted`, `needs-context`, `needs-grill-me`, or `abandoned`, not direct product/code work on the source card.

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
- Attention state and latest `ATTENTION:` note.
- Summary of interpreted intent.
- Candidate tasks.
- Decisions needing user review.
- Research/exploration items.
- Parking-lot items.
- Duplicates or already-done items.
- Recommended next action.

If tasks are added directly, the report should list the new task ids and any items held back for review.

When a triage-source card is marked `converted`, verify both directions of traceability: the source card lists every generated task id, and each generated task references the source dump or source card id. Record that manual verification in the source card note. Direct-created tasks follow the same traceability rule.

## Triage Report Template

```md
Triage report for:
<source card id/title>

Attention state:
<needs-context | needs-grill-me | ready-to-read | ready-to-convert | converted | abandoned>

Card tags/status:
<machine-readable tags to set and board status to use>

ATTENTION:
Blocks: <yes|no>
Ask: <specific question or review request>
Ready to read: <summary/report/card ids, if applicable>
Next agent action: <what to do if the user does not respond yet>

Interpreted intent:
<short summary of what the dump seems to be about>

Proposed candidate tasks (drafts, not created cards):
- <draft title> — <goal, scope, level/priority, acceptance sketch, source reference>

Accepted/generated tasks:
- <task id/title after formal conversion, or "none yet">

Decisions needing user review:
- <decision or tradeoff, preferably phrased as yes/no or a short set of choices>

Research or grill-me items:
- <question, objection, assumption, invalidation path, or exploration need>

Parking lot:
- <idea worth keeping but not acting on now>

Context only:
- <background that should not become work>

Duplicates or already done:
- <existing card/doc/plan reference and decision>

Recommended next action:
<convert, ask for context, grill, read result, abandon, or park>
```

Candidate tasks in a triage report are user-facing proposals, not implementation work. They must not trigger downstream automation, implementation, or task assignment until direct board creation is allowed by the Review Policy or the user/reviewer approves conversion and separate task cards are created. If the report recommends `abandoned`, verify that the source card records the reason, no-follow-up decision, and whether any partial drafts or references should be deleted, retained as context, or left parked.

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
