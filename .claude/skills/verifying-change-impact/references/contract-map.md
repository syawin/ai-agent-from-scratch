# Contract map: verifying-change-impact

This file narrates the repository's key contract seams, error conventions, and known-gap areas that the verifying-change-impact skill uses to scope its checks — for exact current facts and commands, see `./repo-facts.md`.

## The tool-registry triangle

This repo dispatches agent tool calls through three independent pieces that must all stay in sync, with no compiler enforcement tying them together:

- a tool-name-to-implementation dispatch map that routes an incoming tool call to the function that executes it;
- a list of typed JSON-schema tool definitions, built from a schema-generating function, that gets sent to the model so it knows what tools exist and what arguments they take;
- a plain-text system-prompt string that separately documents, in prose, each tool's name and behavior for the model.

Nothing forces these three to agree. Add, remove, or rename a tool in only one or two of the three places and you get a silent mismatch: the model can be told about a tool that isn't actually in the dispatch map, the dispatch map can carry an entry that no schema ever advertises, or a schema/dispatch pair can exist that the system prompt never explains to the model. None of these mismatches fails to compile, and none of them necessarily fails any existing test — the triangle is a convention, not a type.

One test does exercise the dispatch-map side end-to-end: a test whose name describes registry dispatch of every supported tool. That test proves the dispatch map is internally self-consistent. It proves nothing about whether the schema list or the system-prompt text still match it — either of those two sides can drift silently while that test stays green.

See `./repo-facts.md`'s "Tool-registry triangle" and "Test inventory" rows for the exact current file/line locations and the exact command to re-verify them.

## Errors become strings, not exceptions

Every individual tool function in this repo — bash execution, file read, glob, grep, write, edit, web fetch, plus the scratchpad and to-do-list wrapper functions — follows the same convention: it catches its own errors internally and returns a plain string result describing what happened, success or failure, rather than throwing. There is also a second safety net one layer up in the dispatch path: if a tool implementation somehow fails to catch its own exception, the dispatcher catches it there and turns that failure into an error string too.

The consequence for verification: a regression in one of these tools essentially never announces itself as a crash or a stack trace. It shows up as the wrong string — a test assertion on tool output that now reads differently, or a live tool call that returns a plausible-looking but subtly incorrect message. Confirming that a change here "didn't throw" verifies almost nothing, because the convention is specifically designed so that failures don't throw. Verifying such a change means reading the actual returned string carefully — in the test assertion, or in what a live tool call actually produces — not merely confirming that nothing crashed.

## Known-gap areas — read before calling it a bug

Two areas of this repo look, at first glance, like bugs but are documented, deliberate scope decisions. Both are written up in `.claude/memory/`, and each states its own condition for when it would stop being acceptable.

**Gap A — no sandboxing on tool execution.** Several tool implementations (shell execution, file read, glob, grep, write, edit) intentionally have no sandboxing and no path confinement: they can touch anything the running process's own user account can touch. This is a deliberate choice appropriate to a learning project's scope, not an oversight that slipped through review.

**Gap B — narrow output-item handling in the agent loop.** The integration between this repo's agent loop and the OpenAI Responses API only forwards two kinds of model output back into the ongoing conversation history — assistant messages, and function/tool calls — and silently drops any other kind of output item the API might return. This, too, is a deliberate, documented scoping decision, accepted under a stated assumption about what kind of model backend is in use.

The rule for both: if a change touches either area, read the corresponding memory file in full before concluding it's a bug that needs fixing. Each file states its own precise "revisit when" condition in its own words — that stated condition, not general instinct, determines whether the gap has become an actual bug worth fixing. See `./repo-facts.md`'s "Known-gap areas" row for the exact current filenames and their exact revisit-condition wording.

## Consulting design rationale before choosing checks

This repo keeps a per-branch history of the reasoning behind past decisions, plus a root guidance file that is the authoritative entry point for repo conventions in general. Before choosing which verification checks a nontrivial change deserves, search that design-rationale history for entries touching the file(s) being changed. Pay particular attention to any entry flagged as decided unilaterally by an agent with no human sign-off — a decision like that was never reviewed by a person, so it deserves extra scrutiny before you treat it as settled precedent.

The root guidance file documents the exact lookup procedure (what to run, and how the history is organized) and is the place to confirm it, since that procedure can change over time. Don't duplicate it here — a copy of a command or path convention in this file would itself become a second copy of a fact that can drift out of sync with the real one.

## Change-class to check-breadth mapping

| Change class | Proportionate check breadth |
|---|---|
| Docs-only (markdown, comments) | No gate run required; confirm no source or build file was actually touched. |
| Test-only change — purely additive (new test files or methods, no removal or weakening of existing assertions) | Run the affected test class/file; run the full gate only if the change also touches shared test infrastructure. |
| Test-only change — removes, weakens, or restructures existing test code (deleted assertions, deleted test methods, disabled or skipped tests, narrowed tag filters) | Run the required gate. Running only the affected test class/file cannot catch a resulting drop below the enforced coverage thresholds — that check only runs as part of the gate, not as part of a scoped test run. |
| `src/main` behavior change, no tool surface touched | Run the required gate; run the specific test file(s) covering the changed function(s). |
| Tool added, removed, or renamed | Full tool-registry-triangle audit (dispatch map, schema list, system prompt, and the registry-dispatch contract test) plus the required gate — never partial. |
| Build/tooling configuration change | Run the required gate; treat any change to coverage thresholds, task wiring, or dependency versions as itself a fact requiring a `repo-facts.md` update. |
