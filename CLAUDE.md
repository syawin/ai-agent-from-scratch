# CLAUDE.md

Guidance for agents working in this repository.

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