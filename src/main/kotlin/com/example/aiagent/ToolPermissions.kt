package com.example.aiagent

/**
 * Groups every tool name (as used in [getToolSchemas] and the tool registry) into one of three
 * risk tiers, mirroring the three-tier description already given to users via the `--mode` flag
 * in [PermissionMode]:
 *
 * - [READ_ONLY]: read-only or in-memory planning tools. Always safe to run unattended.
 * - [WRITE]: tools that mutate files on disk within the working directory.
 * - [DANGEROUS]: tools with effects beyond the working directory (arbitrary shell execution,
 *   outbound network requests).
 */
enum class ToolCategory {
    READ_ONLY,
    WRITE,
    DANGEROUS,
}

/**
 * Maps every known tool name to its [ToolCategory].
 */
val TOOL_CATEGORIES: Map<String, ToolCategory> =
    mapOf(
        "read_file" to ToolCategory.READ_ONLY,
        "glob_files" to ToolCategory.READ_ONLY,
        "grep" to ToolCategory.READ_ONLY,
        "read_scratchpad" to ToolCategory.READ_ONLY,
        "write_scratchpad" to ToolCategory.READ_ONLY,
        "todo_append" to ToolCategory.READ_ONLY,
        "todo_list" to ToolCategory.READ_ONLY,
        "todo_update" to ToolCategory.READ_ONLY,
        "write_file" to ToolCategory.WRITE,
        "edit_file" to ToolCategory.WRITE,
        "run_bash" to ToolCategory.DANGEROUS,
        "webfetch" to ToolCategory.DANGEROUS,
    )

/**
 * Whether [toolName] is allowed to run unattended under the given [mode], per the tiers above.
 *
 * - [PermissionMode.DEFAULT]: only [ToolCategory.READ_ONLY] tools are allowed.
 * - [PermissionMode.ACCEPT_EDITS]: [ToolCategory.READ_ONLY] and [ToolCategory.WRITE] tools are allowed.
 * - [PermissionMode.DANGEROUSLY_SKIP_PERMISSIONS]: every tool is allowed.
 *
 * An unrecognized [toolName] is treated as [ToolCategory.DANGEROUS] (fails closed).
 */
fun isToolAllowed(
    toolName: String,
    mode: PermissionMode,
): Boolean {
    val category = TOOL_CATEGORIES[toolName] ?: ToolCategory.DANGEROUS
    return when (mode) {
        PermissionMode.DANGEROUSLY_SKIP_PERMISSIONS -> true
        PermissionMode.ACCEPT_EDITS -> category != ToolCategory.DANGEROUS
        PermissionMode.DEFAULT -> category == ToolCategory.READ_ONLY
    }
}
