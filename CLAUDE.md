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
| `com.openai:openai-java` (4.42.0) | `/openai/openai-java` |
| `io.mockk:mockk` (1.14.2) | `/mockk/mockk` |

Note: Context7 tracks these at the repo level, not by Maven version, so the docs
may reflect a newer release than the one pinned in `build.gradle.kts`. Verify any
API against the pinned version if it doesn't match.

If Context7's coverage is thin for what you need, the real classes live in
`openai-java-core`, not the `openai-java` aggregate artifact (which ships no
classes of its own). Extract `openai-java-core-<version>-sources.jar` from
`~/.gradle/caches/modules-2/files-2.1/com.openai/openai-java-core/` and grep
it directly for ground-truth signatures.

## Testing

- `./gradlew check` (not just `test`) is the real gate — it runs
  `jacocoTestCoverageVerification` at 80% line / **100% method** coverage.
  Prefer inline logic over new private helper functions unless you're also
  adding a test that exercises them.
- When mocking OpenAI SDK response objects with MockK for assertions that
  check `.toString()` (see `AgentLoopTest.kt`), build nested objects (e.g.
  `ResponseOutputMessage`, `ResponseFunctionToolCall`) via their real
  `.builder()` chains, not `mockk<T>()` — a mocked object renders as an
  opaque identifier in `toString()` and silently breaks substring assertions.
- Before or after a change that could break existing behavior (a tool added/
  removed/renamed, a change under `src/main/kotlin`, a claim that tests or
  coverage pass), use the `verifying-change-impact` skill
  (`.claude/skills/verifying-change-impact/`) to scope and report verification.

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
