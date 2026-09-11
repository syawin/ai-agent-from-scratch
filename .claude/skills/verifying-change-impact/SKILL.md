---
name: verifying-change-impact
description: This skill should be used when the user or an agent is about to change behavior in `src/main/kotlin`, add/remove/rename an agent tool, run or report the results of `./gradlew check`, claim tests or coverage pass, or open a pull request in this repository. It maps affected behavior, public contracts, and regression risk; chooses proportionate checks grounded in this repo's own guidance, tests, and design rationale; runs the required gate plus focused checks; and reports passed/failed/skipped/could-not-run honestly instead of assuming success.
---

## Overview

Match the breadth of verification to the change's actual blast radius. Neither default to maximal — running every check for a one-line comment fix wastes effort and buries the signal that matters — nor default to minimal — skipping the required gate for a real behavior change is how regressions ship. Map impact first, then size the checks to what could actually break.

## Core rule

Never claim a completion, pass, or "tests are green" result without having actually run the corresponding command in this session and read its real output. This rule holds standalone, regardless of whether some other, more general verification-discipline skill is also loaded in the environment.

## Phase 1 — Map impact, contracts, and regression risk

1. Identify exactly what changed — which files, which functions or symbols.
2. Determine whether the change touches a public contract or integration seam. See `references/contract-map.md`.
3. Determine whether the change touches a documented known-gap area. See `references/contract-map.md` — if it does, read the linked `.claude/memory/` file before assuming the gap is a bug.
4. Identify which existing tests already exercise the changed code path.

## Phase 2 — Inspect guidance before choosing checks

1. Read the relevant section(s) of root `CLAUDE.md`.
2. Search this repo's design-rationale history (`.ai/why/`) for decisions touching the changed file(s), following CLAUDE.md's own documented lookup procedure — note anything flagged as decided unilaterally by an agent without human sign-off.
3. Read the actual current build configuration fresh rather than relying on any cached memory of it — it can change.
4. Identify the covering test file(s) by name and convention, not by a remembered test count — test counts in this repo have been observed to go stale between snapshots.

## Phase 3 — Select proportionate checks

Breadth follows blast radius, not a fixed default: a small, well-tested change warrants a narrow set of focused checks, while a change touching a contract seam, a known-gap area, or multiple call sites warrants broader coverage. See `references/contract-map.md`'s change-class table for this repo's concrete mapping from kinds of change to appropriate check breadth.

**A tool added, removed, or renamed is always full-triangle-audit scope, never partial** — see `references/contract-map.md`.

## Phase 4 — Execute: required gate and focused checks

1. Run the project's required validation gate. See `references/repo-facts.md` for the exact current command — do not hardcode it here, since it can change.
2. Run focused/targeted checks appropriate to the change.
3. If any test run this session happened concurrently with another test run, treat coverage evidence as untrustworthy until a fresh, isolated gate run is done. See `references/repo-facts.md`'s note on this.
4. A required check that cannot run in this environment (a missing service dependency, a missing tool) is a legitimate outcome — report it as "could not run," never as skipped or passed.
5. Treat these three responses to a failing or inconvenient gate as forbidden shortcuts, never as valid fixes: (a) inlining or restructuring code solely to change what the gate measures, (b) writing an assertion-free test whose only purpose is invoking a method for coverage credit, (c) weakening or disabling the gate's rule. Any of these is an escalation trigger (see Phase 6) — never do one of these unilaterally.

## Phase 5 — Report honestly

Give every check considered a row in the report below, including ones deliberately not run — no category may be silently omitted. Use this table template:

```markdown
| Check | How (command/method) | Status | Evidence / Reason |
|---|---|---|---|
| Required gate | | | |
| Focused test(s) for this change | | | |
| Tool-registry triangle audit (if applicable) | | | |
| Known-gap review (if applicable) | | | |
| Integration test (if applicable) | | | |
```

Define the four status values precisely:

- **Passed** — ran it this session, output confirms.
- **Failed** — ran it this session, output contradicts.
- **Skipped** — deliberately not run because out of scope for this change; must state why.
- **Could not run** — attempted or would-be-required but blocked by environment or tooling; must state what blocked it.

Never collapse "could not run" into "skipped," and never omit a row that would normally apply to this change class just because the result is unfavorable.

## Phase 6 — Stop and escalate

```markdown
| Situation | Rule |
|---|---|
| Required gate run and it fails | Stop. Do not commit, push, or open a pull request. Report the failure with its full output. Hand off root-causing the failure to a systematic-debugging approach rather than guessing at a fix inline. |
| Required gate cannot run (e.g. a needed local service isn't running) | Report status "could not run" with the specific reason — never report it as passed. Judge relevance: if the change touches the surface that check exists to protect, flag elevated risk and ask the user whether to start the dependency or accept the risk; otherwise disclose the unrun check and proceed. |
| Coverage evidence exists but a concurrent test run happened this session | Treat it as untrustworthy, not as pass or fail. Re-run the gate in isolation before reporting any coverage status. |
| Change touches a documented known-gap area | Stop before treating it as a bug. Read the linked memory file first. If it still seems to need fixing afterward, surface that as a decision for the user rather than fixing it unilaterally, unless the user already asked for exactly that. |
| Ambiguous scope or design question surfaces mid-verification | Escalate to the user rather than guessing. Treat asking as the correct outcome, not a process failure. |
| Considering a gate-gaming shortcut (see Phase 4) | Treat as an escalation trigger. Ask the user before doing any of these. |
```

## Maintenance

This skill's `references/` files record facts about the repository's code, tooling, and tests that change over time — keep them accurate rather than letting them silently go stale. Update triggers:

- A change to the build configuration that affects the required gate (thresholds, task wiring, a new quality-tooling plugin).
- A change to the shape of the tool-registry triangle (e.g., the current ad hoc dispatch-map/schema/prompt trio being replaced by a single enforced interface).
- A `.claude/memory/` file being added, removed, or its own stated "revisit when" condition being met.
- A change to the test framework, CI, or linting setup.
- This skill's own drift check (below) finding a mismatch.

Every update to `references/` must be backed by the literal output of a command run in that session, or the literal current content of a file read fresh — never by assumption or by memory of an earlier session.

Update procedure:

1. Edit only the specific row or section that is now wrong.
2. Append a short "Verified `<date>`: `<command>` confirms `<what>`" note to that row.
3. Only edit this SKILL.md file itself if the *procedure* it describes has changed, not merely a fact.

### Drift check

Periodically — and especially before relying on `references/repo-facts.md` for a high-stakes verification — re-run every command in that file's "Re-verify with" column and compare the output to what the file currently states; treat any mismatch as an update trigger. Cheap heuristic for when this is worth doing: compare the last-modified history of the `references/` directory against `build.gradle.kts`, `Main.kt`, and `Tools.kt` — if those source files have changed more recently than the references, run the drift check before trusting them for this invocation.

## Related skills

A separate, more general verification-discipline skill (if present in the environment) enforces that an agent must actually run its checks before claiming success; this skill instead decides *what* to check and *how broadly*, using this specific repository's own gate, tests, and design-rationale history — the two are complementary, not redundant. A systematic-debugging skill (if present) is the right next step once this skill's Phase 4 has surfaced an actual failure to investigate; this skill does not itself diagnose root causes.
