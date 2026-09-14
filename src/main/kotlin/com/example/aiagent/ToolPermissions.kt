package com.example.aiagent

import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

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
        "ask_question",
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
 *
 * This is a coarse allow/deny check independent of any specific call's arguments. Actual per-call
 * enforcement (used by `handleToolCalls`) is [checkPermission], which builds on this function but
 * overrides its answer for [WRITE_TOOLS] under [PermissionMode.ACCEPT_EDITS] to additionally apply
 * path confinement, and falls back to an interactive prompt rather than failing closed outright.
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

/**
 * Prompts the user for authorization to perform an action for a specific tool.
 *
 * This function outputs the tool name and its arguments (truncated, as `handleToolCalls` already
 * does for tool results, so a large `write_file`/`edit_file` payload doesn't flood the prompt) to
 * the console, then waits for the user's input to grant or deny permission. Authorization is
 * granted by entering "y" or "yes" and denied by entering "n" or "no". Denies immediately on EOF,
 * matching the convention established by `askQuestion` in `Tools.kt`.
 *
 * @param toolName The name of the tool requesting authorization.
 * @param args The arguments the tool would be invoked with.
 * @return `true` if authorization was granted, `false` if denied or EOF was reached.
 */
fun promptForAuthorization(
    toolName: String,
    args: Map<String, Any?>,
): Boolean {
    val argsText = args.toString()
    println("\n  [permission required] $toolName")
    println("  Arguments: ${argsText.take(200)}${if (argsText.length > 200) "..." else ""}")
    while (true) {
        print("  Allow this action? [y/n]: ")
        val answer = readlnOrNull()?.trim()?.lowercase()
        if (answer == null) {
            println("  (EOF - denying permission)")
            return false
        }
        if (answer in listOf("y", "yes")) {
            return true
        }
        if (answer in listOf("n", "no")) {
            return false
        }
        println("  Please enter 'y' or 'n'.")
    }
}

/**
 * Resolves the file path a tool call would act on, if applicable.
 *
 * @param toolName The name of the tool.
 * @param args The tool's arguments, where the path is expected under the key "path".
 * @return The path as a string if [toolName] is a [WRITE_TOOLS] tool and its `path` argument is
 *   a string; `null` otherwise.
 */
fun resolveToolPath(
    toolName: String,
    args: Map<String, Any?>,
): String? = if (toolName in WRITE_TOOLS) args["path"] as? String else null

/**
 * Whether [path] resolves to a location inside [workingDir].
 *
 * This is a lexical, `normalize()`-based containment check — it does not call `toRealPath()` (a
 * target that doesn't exist yet, e.g. a new file being created by `write_file`, would otherwise
 * throw) and it does not resolve symlinks, so a symlink inside [workingDir] pointing outside it
 * is treated as "inside". This matches this project's documented stance of favoring simplicity
 * over hardening in tool implementations (see CLAUDE.md).
 *
 * @param path The path to check, relative or absolute.
 * @param workingDir The directory [path] must resolve inside of.
 * @return `true` if [path] resolves to a location strictly inside [workingDir]; `false` if it
 *   resolves outside, resolves to [workingDir] itself (e.g. an empty string or `"."` — never a
 *   legitimate write/edit target), or if [path] is not a valid path string.
 */
fun isPathInsideWorkingDir(
    path: String,
    workingDir: Path,
): Boolean =
    try {
        var target = Paths.get(path)
        if (!target.isAbsolute) {
            target = workingDir.resolve(target)
        }
        val normalizedTarget = target.normalize()
        val normalizedWorkingDir = workingDir.normalize()
        normalizedTarget.startsWith(normalizedWorkingDir) && normalizedTarget != normalizedWorkingDir
    } catch (e: InvalidPathException) {
        false
    }

/**
 * Determines whether a tool call is permitted to run right now, given its arguments and the
 * current permission mode. Builds on [isToolAllowed] for every case it can decide correctly
 * (read/planning tools, and [PermissionMode.DANGEROUSLY_SKIP_PERMISSIONS]), but overrides it for
 * [WRITE_TOOLS] under [PermissionMode.ACCEPT_EDITS]: instead of the coarse "always allowed"
 * [isToolAllowed] reports for that combination, it additionally applies path confinement, and
 * falls back to an interactive prompt (rather than failing closed outright) whenever a call isn't
 * allowed unattended.
 *
 * @param toolName The name of the tool attempting to perform the operation.
 * @param args The tool's arguments.
 * @param mode The current permission mode.
 * @param workingDir The working directory used to confine [WRITE_TOOLS] paths under
 *   [PermissionMode.ACCEPT_EDITS].
 * @return `true` if the operation is permitted, `false` if the user denied it interactively.
 */
fun checkPermission(
    toolName: String,
    args: Map<String, Any?>,
    mode: PermissionMode,
    workingDir: Path,
): Boolean {
    // ACCEPT_EDITS + a write tool is the one combination isToolAllowed can't decide alone: it
    // reports "always allowed" with no awareness of where the write actually lands, so it's
    // handled separately below instead of being trusted here.
    val needsPathConfinement = mode == PermissionMode.ACCEPT_EDITS && toolName in WRITE_TOOLS
    if (!needsPathConfinement && isToolAllowed(toolName, mode)) {
        return true
    }

    if (needsPathConfinement) {
        val path = resolveToolPath(toolName, args)
        if (path != null && isPathInsideWorkingDir(path, workingDir)) {
            return true
        }
    }

    return promptForAuthorization(toolName, args)
}
