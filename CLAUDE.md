# CLAUDE.md

Guidance for agents working in this repository.

## Project context

This is a learning project: an AI coding agent built from scratch (see
`src/main/kotlin/com/example/aiagent/`). The tool implementations (bash exec, file read, glob,
grep) intentionally favor simplicity over hardening — e.g. no sandboxing on shell execution, no
path confinement on file reads. These gaps are acceptable for the project's purpose and should
not be "fixed" reflexively; see `.claude/memory/future-security-considerations.md` for the
specific findings and mitigation ideas to revisit if the project's scope ever changes.

The agent talks to the model via the OpenAI Java SDK's Responses API (migrated from Chat
Completions in September 2026). See `.claude/memory/responses-api-migration-notes.md` for a
known scoping gap in that migration (non-message/non-function-call output items are silently
dropped) and when it needs revisiting.

## Documentation lookups

**Prioritize Context7 when searching for library/framework/API documentation.**
Prefer it over web search or relying on training data, since the SDKs here move
faster than any knowledge cutoff. Use `resolve-library-id` only if a library
below is missing; otherwise pass the known ID straight to `query-docs`.

Known Context7 library IDs:

| Dependency | Context7 library ID |
| --- | --- |
| `com.openai:openai-java` (4.41.0) | `/openai/openai-java` |
| `io.mockk:mockk` (1.14.2) | `/mockk/mockk` |

Note: Context7 tracks these at the repo level, not by Maven version, so the docs
may reflect a newer release than the one pinned in `build.gradle.kts`. Verify any
API against the pinned version if it doesn't match.

<!-- grepathy:begin -->
## Design reasoning lives in `.ai/why/`

This repo records the *why* behind its code in `.ai/why/<branch>.md` ("why-packs"),
distilled from AI coding sessions. **The why-pack is the ground truth for *why* —
prefer it over commit messages, which are lossy and can be out of date.**

- Before working on unfamiliar code, run `grepathy context <file>` (or grep
  `.ai/why/`) to see the decisions that touch it.
- When asked what changed on a branch, or *why* something is the way it is, read
  `.ai/why/<branch>.md` — not just `git log`.
- `grep -rn "agent-initiated" .ai/why/` surfaces decisions an agent made
  unilaterally, with no human sign-off — scrutinize these first.

Commits titled `grepathy: update why-pack (…)` are written by the tool (the
why-pack only, via a scratch index — they never touch your staged work). They're
safe to rebase past or drop; don't amend them into your feature commits.
<!-- grepathy:end -->
