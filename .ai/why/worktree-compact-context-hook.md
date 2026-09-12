# Why: worktree-compact-context-hook

<!-- grepathy:v1 generated 2026-09-12 — review before sharing; edit freely, edits are preserved -->

## Intent
Restore file-activity context after session compaction so the system can continue work with a reminder of touched files without re-reading full session transcripts.

## Decisions

### Register hook on SessionStart event with matcher='compact'
Status: agent-initiated — specific matcher name and event chosen without explicit specification
Touches: `.claude/settings.json`

The hook fires when a session begins after compaction (source='compact' in hook stdin). SessionStart is the only hook point where the session exists but context is still short, making it the natural recovery window. The 'compact' matcher filters to post-compaction recovery only, preventing noise in regular sessions.

Risk: If the compaction event source key changes in the harness, the matcher will not fire and context will be silently lost.

### Implement hook in Python, parsing JSONL transcript and git output
Status: agent-initiated — language choice unconstrained
Touches: `.claude/hooks/compact-context-summary.py`

The script reads the session transcript (JSONL at path provided in hook stdin), parses tool calls to identify file references, cross-references against git status and git diff --numstat, and emits structured JSON. Python's native json module and cleaner string handling make this more maintainable than shell; the self-contained script has minimal dependencies.

Considered/rejected: Bash would require external jq and sed/awk for transcript parsing and git output formatting, increasing fragility.
Risk: Requires Python 3.x at runtime. The script does not validate Python availability before execution, so missing or incompatible Python fails silently.

### Track only direct file-reference tool calls from the transcript
Status: discussed — scope follows naturally from the goal 'restore what files were touched'
Touches: `.claude/hooks/compact-context-summary.py`

The script identifies Read, Write, Edit, MultiEdit, NotebookEdit, and Grep tool calls from the JSONL transcript, since these tools directly reference file paths and are visible in the session log. Bash-driven filesystem operations and sub-agent work are either invisible to this script or belong to separate transcripts.

Risk: Agent-run file modifications via Bash or sub-agents will not appear in the summary, leaving context incomplete for sessions involving builds, scripts, or delegated work.

### Filter out sensitive paths and build artifacts from summary
Status: agent-initiated — security best practice applied proactively, not requested
Touches: `.claude/hooks/compact-context-summary.py`

The script hard-codes a filter list: .env, *.pem, *.key, credentials*, plus build directories (build/, .gradle/, node_modules/, etc.). Since the script reads the local transcript and full git metadata, without filtering it could expose secret filenames or clutter output with machine-generated files.

Risk: Custom secret naming schemes or non-standard build directories will not be filtered and their paths will appear in the summary.

### Emit summarized context (path + status + line counts), not file contents or diffs
Status: discussed — efficiency constraint implicit in the compaction goal
Touches: `.claude/hooks/compact-context-summary.py`

The script reports file paths, git status (modified/untracked/new), and line-change counts from git diff --numstat, but never dumps file contents or full diff bodies. This is sufficient to jog memory about what was touched without re-inflating the compressed session.

Considered/rejected: Including full diffs or file contents would defeat the purpose of compaction by reintroducing the bulk being trimmed.
Risk: If the system or next session needs full diff context for recovery, omitting it forces extra file reads later.

### Store hook configuration at project level (.claude/settings.json, version-controlled)
Status: discussed — project-level scope confirmed via .gitignore; JSON structure empirically validated against user's working settings.json
Touches: `.claude/settings.json`

The hook configuration is stored in .claude/settings.json (tracked in git), not settings.local.json (excluded), making it a shared team asset. Verified the JSON structure against the user's real ~/.claude/settings.json: top-level 'hooks' key wraps event-keyed maps, differing from skill documentation examples that showed only the inner event map.

Considered/rejected: Using settings.local.json would make the hook per-user only, requiring recreation on every machine. A separate .claude/hooks.json would break established conventions.
Risk: If a user has a local hook with the same name in settings.local.json, both will register and execute without conflict detection.

### Validate hook configuration and script with bundled skill validators before commit
Status: agent-initiated — comprehensive testing beyond minimum viable deployment
Touches: `.claude/settings.json`, `.claude/hooks/compact-context-summary.py`

Ran validate-hook-schema.sh (from hook-development skill) against extracted hook definitions. Also tested the script directly with synthetic JSONL transcripts and git repos, validating correct output for mixed file states, untracked files, modified tracked files, and correct no-op behavior for non-'compact' sources.

Risk: Linter flagged bash-specific best practices inapplicable to Python, creating false warnings that could mislead future reviewers.

### Isolate implementation in a git worktree branch
Status: agent-initiated — isolation strategy chosen for safety, not requested
Touches: `.claude/hooks/compact-context-summary.py`, `.claude/settings.json`

Created a git worktree (worktree-compact-context-hook, commit 662f3d5) to keep the main branch clean during development. This allows the user to review, test, and decide on integration without risk of accidental commits to main.

Reviewer attention: Decide how to integrate this branch (merge, cherry-pick, or discard) after review and testing.
