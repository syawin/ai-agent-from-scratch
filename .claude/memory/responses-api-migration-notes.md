# Responses API migration notes

Recorded during the Chat Completions → Responses API migration of
`src/main/kotlin/com/example/aiagent/Main.kt` (2026-09-10). Not a bug — a
scoping decision made under the assumption that the backend stays a local
LM Studio instance serving a plain, non-reasoning model. Revisit if that
assumption stops holding.

## Known gap: non-message/non-function-call output items are dropped

`agentLoop`'s per-turn loop converts each `ResponseOutputItem` from
`response.output()` back into a `ResponseInputItem` so the next turn's
request carries the full conversation. It only handles two item kinds:

- `isFunctionCall()` → `ResponseInputItem.ofFunctionCall(...)`
- `isMessage()` → `ResponseInputItem.ofResponseOutputMessage(...)`

Any other item kind the Responses API can emit — most notably `reasoning`
items, but also anything added to the API surface after this migration — is
silently skipped. No error, no log line; it just isn't carried into the next
turn's `input`.

**Why this was accepted rather than fixed:** the app's only target backend is
a local LM Studio server serving `"local-model"` (see `main()` and
`ServiceRunningTest.kt`), which doesn't emit reasoning items or other exotic
output kinds today. Handling every current and future `ResponseOutputItem`
variant generically would have meant guessing at API surface the model
never actually exercises — out of scope for a mechanical, behavior-preserving
migration in a learning project.

**Revisit when:** the backend or model choice changes — e.g. swapping
`"local-model"` for a reasoning-capable local model, or pointing the client
at real OpenAI models that can emit reasoning/other item kinds. At that
point this becomes a real correctness bug (silent context loss across
turns), not a documented limitation. Fix by extending the output→input
conversion loop in `agentLoop` to cover the additional `ResponseOutputItem`
variants (e.g. `ResponseInputItem.ofReasoning(...)` for reasoning items)
before switching backends.
