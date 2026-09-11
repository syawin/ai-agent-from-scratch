# Repo facts: verifying-change-impact

This file is the single source of truth for every volatile, drift-checkable fact the
`verifying-change-impact` skill's other files rely on; every row below carries the exact
command used to re-verify it, and no other file in this skill should restate these values —
they should point back here instead.

## Gate & coverage

| Fact | Source | Re-verify with |
| --- | --- | --- |
| `tasks.check` depends on `jacocoTestCoverageVerification`, which enforces two violation-rule limits: `LINE` minimum `0.80` (80%) and `METHOD` minimum `1.00` (100%). | `build.gradle.kts:52-70` | `grep -n -A20 "tasks.jacocoTestCoverageVerification" build.gradle.kts` |
| `tasks.test` excludes tests tagged `"integration"` (`useJUnitPlatform { excludeTags("integration") }`) and is `finalizedBy(tasks.jacocoTestReport)`. A separate `integrationTest` task includes only `"integration"`-tagged tests, its `description` states it "Runs tests that require a locally running LM Studio service", and it only `shouldRunAfter(tasks.test)` — it is never wired into `tasks.check`, so `./gradlew check` does not run it. | `build.gradle.kts:30-42` | `grep -n -B2 -A8 "integrationTest\|excludeTags\|includeTags" build.gradle.kts` |
| Documented gotcha: concurrent `./gradlew test` invocations can corrupt JaCoCo's shared `build/jacoco/test.exec` state and produce false-positive coverage misses (one dev agent observed `getScratchpad`, `getTodoStore`, `todoList$default`, `todoUpdate$default` incorrectly shown as 0% covered in an isolated run); the documented fix is a final `./gradlew clean check` run in isolation, which confirmed all 68/68 methods truly covered. | `.ai/why/master.md:526` (also corroborated at lines 549, 552, 568) | `grep -n -i "false-positive\|test.exec\|jacoco" .ai/why/master.md` |

## Tool-registry triangle

| Fact | Source | Re-verify with |
| --- | --- | --- |
| The tool-dispatch map, typed-schema list, and system-prompt string all live in one file: `private val TOOL_REGISTRY` at line 44, `private val TOOL_SCHEMAS` at line 108, `private val SYSTEM_INSTRUCTIONS` at line 194. | `src/main/kotlin/com/example/aiagent/Main.kt:44,108,194` | `grep -n "private val TOOL_REGISTRY\|private val TOOL_SCHEMAS\|private val SYSTEM_INSTRUCTIONS" src/main/kotlin/com/example/aiagent/Main.kt` |
| The schema-generating function `fun getToolSchemas()` lives in a separate file from the registry/schema-list/prompt triangle above. | `src/main/kotlin/com/example/aiagent/Tools.kt:329` | `grep -n "fun getToolSchemas" src/main/kotlin/com/example/aiagent/Tools.kt` |
| A test named `tool registry dispatches every supported tool` exists and exercises the registry-to-implementation dispatch as a whole. | `src/test/kotlin/com/example/aiagent/AgentLoopTest.kt:193` | `grep -n "tool registry dispatches" src/test/kotlin/com/example/aiagent/AgentLoopTest.kt` |

## Known-gap areas

| Fact | Source | Re-verify with |
| --- | --- | --- |
| Exactly two files exist under `.claude/memory/`: `future-security-considerations.md` and `responses-api-migration-notes.md`. | `.claude/memory/` | `ls .claude/memory/` |
| `future-security-considerations.md`'s stated revisit condition: "Revisit these if the project moves beyond a learning exercise (e.g. gets exposed to untrusted input, runs unattended, or is used as a base for something real)." | `.claude/memory/future-security-considerations.md:6-8` | `grep -n -i "revisit" .claude/memory/future-security-considerations.md` |
| `responses-api-migration-notes.md`'s stated revisit condition: "**Revisit when:** the backend or model choice changes — e.g. swapping `"local-model"` for a reasoning-capable local model, or pointing the client at real OpenAI models that can emit reasoning/other item kinds. At that point this becomes a real correctness bug (silent context loss across turns), not a documented limitation." | `.claude/memory/responses-api-migration-notes.md:31-34` | `grep -n -i "revisit" .claude/memory/responses-api-migration-notes.md` |

## Test inventory

| Fact | Source | Re-verify with |
| --- | --- | --- |
| Current test files (names only — method counts are known to go stale between snapshots of this repo's history): `ScratchpadTest.kt`, `ServiceRunningTest.kt`, `AgentLoopTest.kt`, `ToolsTest.kt`, `ToDoListTest.kt`. | `src/test/kotlin/com/example/aiagent/` | `find src/test/kotlin -name "*.kt"` |

## Tooling versions & absent tooling

| Fact | Source | Re-verify with |
| --- | --- | --- |
| Kotlin JVM plugin pinned at `2.3.20`; MockK pinned at `1.14.2`. | `build.gradle.kts:2,19` | `grep -n "kotlin(\"jvm\")\|mockk:" build.gradle.kts` |
| Gradle wrapper pinned at `9.5.1` (`gradle-9.5.1-bin.zip`). | `gradle/wrapper/gradle-wrapper.properties` | `grep distributionUrl gradle/wrapper/gradle-wrapper.properties` |
| No CI config exists: no `.github` directory is present in the repo root, so `find .github -type f` finds nothing. | repo root listing | `find .github -type f 2>/dev/null` |
| No detekt/ktlint/spotless tooling is configured: no matching plugin or dependency string appears in `build.gradle.kts`. | `build.gradle.kts` | `grep -n -i "detekt\|ktlint\|spotless" build.gradle.kts` |
