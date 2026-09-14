package com.example.aiagent

/**
 * Read-only tools (as used in [getToolSchemas] and the tool registry): they inspect the
 * filesystem but never mutate it. Always allowed to run unattended.
 */
val READ_TOOLS: Set<String> =
    setOf(
        "read_file",
        "glob_files",
        "grep",
    )

/**
 * Internal planning, bookkeeping, and user-interaction tools (as used in [getToolSchemas] and
 * the tool registry): in-memory or interactive, never touching the filesystem or network.
 * Always allowed to run unattended.
 */
val PLANNING_TOOLS: Set<String> =
    setOf(
        "todo_append",
        "todo_list",
        "todo_update",
        "read_scratchpad",
        "write_scratchpad",
    )

/**
 * Tools that mutate files on disk (as used in [getToolSchemas] and the tool registry). Allowed
 * unattended under [PermissionMode.ACCEPT_EDITS] (intended for edits confined to the working
 * directory, per the `--mode` CLI help text) or [PermissionMode.DANGEROUSLY_SKIP_PERMISSIONS].
 */
val WRITE_TOOLS: Set<String> =
    setOf(
        "write_file",
        "edit_file",
    )

/**
 * Whether [toolName] is allowed to run unattended under the given [mode].
 *
 * - [PermissionMode.DEFAULT]: only [READ_TOOLS] and [PLANNING_TOOLS] tools are allowed.
 * - [PermissionMode.ACCEPT_EDITS]: [READ_TOOLS], [PLANNING_TOOLS], and [WRITE_TOOLS] tools are allowed.
 * - [PermissionMode.DANGEROUSLY_SKIP_PERMISSIONS]: every tool is allowed.
 *
 * A tool that is not in any of the three sets above (e.g. `run_bash`, `webfetch`) has effects
 * beyond the working directory and is treated as dangerous: it is only allowed under
 * [PermissionMode.DANGEROUSLY_SKIP_PERMISSIONS] (fails closed).
 */
fun isToolAllowed(
    toolName: String,
    mode: PermissionMode,
): Boolean {
    val readOrPlanning = toolName in READ_TOOLS || toolName in PLANNING_TOOLS
    return when (mode) {
        PermissionMode.DANGEROUSLY_SKIP_PERMISSIONS -> true
        PermissionMode.ACCEPT_EDITS -> readOrPlanning || toolName in WRITE_TOOLS
        PermissionMode.DEFAULT -> readOrPlanning
    }
}
