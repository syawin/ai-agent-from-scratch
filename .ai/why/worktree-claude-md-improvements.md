# Why: worktree-claude-md-improvements

<!-- grepathy:v1 generated 2026-07-15 — review before sharing; edit freely, edits are preserved -->

## Intent
Improve CLAUDE.md documentation to guide agents working on the local LM Studio-based AI agent project with build commands, code architecture, and critical configuration gotchas.

## Decisions

### Add Commands section to CLAUDE.md with ./gradlew build/test/run instructions
Status: directed
Touches: `CLAUDE.md`

Added Commands section documenting three ./gradlew tasks: build (compile + run all tests), test (run tests only), and run (start interactive agent loop). Noted JDK 24 toolchain requirement. All commands verified against build.gradle.kts.

### Add Architecture section explaining code structure and design
Status: directed
Touches: `CLAUDE.md`

Added Architecture section documenting the single-module Kotlin/JVM structure, entry point at com.example.aiagent.MainKt, and agentLoop REPL design pattern. Explains that agentLoop accepts an injectable exit lambda to support testable exit paths.

### Add Gotchas section documenting local LM Studio target and integration constraints
Status: directed
Touches: `CLAUDE.md`

Added Gotchas section with four critical items: project targets local LM Studio at http://localhost:1234/v1/ (not cloud OpenAI) with model 'local-model' and key 'lm-studio'; ServiceRunningTest is an integration test requiring LM Studio to be running; unit tests only can run with ./gradlew test --tests 'com.example.aiagent.AgentLoopTest'; REPL exit command is the literal string \exit. All verified against source.

Reviewer attention: The local LM Studio configuration is critical — agents must not assume cloud OpenAI credentials are required for this project.

### Preserve existing Context7 documentation section unchanged
Status: discussed — implicit in 'apply additions' rather than wholesale rewrite
Touches: `CLAUDE.md`

Agent verified the existing Context7 section was accurate and current against library versions pinned in build.gradle.kts, then deliberately left it intact. Maintains institutional knowledge while adding the missing fundamentals.

### Isolate CLAUDE.md edits in git worktree before committing
Status: agent-initiated — best practice applied without explicit request
Touches: `.claude/worktrees/claude-md-improvements`

Agent created a git worktree to isolate edits to the checked-in CLAUDE.md, committed locally on branch worktree-claude-md-improvements, but did not merge to master or push. Allows review before integration into main branch.

Risk: Changes exist in isolated worktree and require manual merge or integration step into master.
