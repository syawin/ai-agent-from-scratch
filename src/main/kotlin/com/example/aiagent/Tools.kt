package com.example.aiagent

import org.jsoup.Jsoup
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isRegularFile
import kotlin.io.path.useLines

/**
 * Executes a given Bash command and retrieves its output.
 *
 * @param command The Bash command to execute as a string.
 * @return The output of the command, including any error messages from STDERR, appended to the standard output.
 */
fun runBash(command: String): String {
    val process = ProcessBuilder("bash", "-c", command).start()
    // Drain stderr on a separate thread so a command that floods the stderr pipe buffer
    // cannot deadlock against us blocking on a full read of stdout.
    val stderr = StringBuilder()
    val stderrReader =
        Thread {
            process.errorStream.bufferedReader().use { stderr.append(it.readText()) }
        }
    stderrReader.start()
    val stdout = process.inputStream.bufferedReader().use { it.readText() }
    stderrReader.join()
    val output = if (stderr.isNotEmpty()) "$stdout\nSTDERR:\n$stderr" else stdout
    return output.trim().ifEmpty { "(no output)" }
}

/**
 * Reads content from a file and returns a specified range of lines as a single string.
 *
 * @param filePath The path to the file to be read.
 * @param offset The starting line number (1-based) from which to begin reading. Defaults to 1.
 * @param limit The maximum number of lines to read from the file. Defaults to 200.
 * @return A string containing the specified range of lines from the file, or an error message if the file is not found.
 */
fun readFile(
    filePath: String,
    offset: Int = 1,
    limit: Int = 200,
): String {
    val file = File(filePath)
    if (!file.isFile) {
        return "File not found: $filePath"
    }
    return file.useLines { lines ->
        lines.drop(maxOf(0, offset - 1)).take(maxOf(0, limit)).joinToString("\n")
    }
}

/**
 * Walks the file tree rooted at [root] and returns every path it contains.
 */
private fun walkFiles(root: Path): List<Path> = Files.walk(root).use { it.toList() }

/**
 * Finds files matching a given glob pattern within a specified directory and its subdirectories.
 *
 * @param pattern The glob pattern to match file names (e.g., "*.txt" or "file?.csv").
 * @param path The path to the root directory where the search should begin. Defaults to the current directory ("./").
 * @return A string containing the list of matched file paths, each on a new line, or "(no matches)" if no files are found.
 */
fun globFiles(
    pattern: String,
    path: String = ".",
): String {
    val root = Paths.get(path)
    if (!Files.exists(root)) {
        return "Path not found: $path"
    }
    val directMatcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
    val nestedMatcher = FileSystems.getDefault().getPathMatcher("glob:**/$pattern")
    val matches =
        // Filters and sorts file paths matching glob patterns
        walkFiles(root)
            .filter { file ->
                val relative = root.relativize(file)
                file.isRegularFile() && (
                    directMatcher.matches(relative) ||
                        nestedMatcher.matches(
                            relative,
                        )
                )
            }.map { it.toString() }
            .toSortedSet()
    return if (matches.isNotEmpty()) matches.joinToString("\n") else "(no matches)"
}

/**
 * Searches for lines matching a specified pattern in files under the given path and returns the matching lines
 * along with their file paths and line numbers.
 *
 * @param pattern The regular expression pattern to search for within file contents.
 * @param path The root directory to start the file search from. Defaults to the current directory ("./").
 * @param include A glob pattern used to filter files by name while searching. Defaults to "*", which includes all files.
 * @return A string containing the matched lines with their file paths and line numbers, or an appropriate error message
 *         if the input is invalid or no matches are found.
 */
fun grep(
    pattern: String,
    path: String = ".",
    include: String = "*",
): String {
    val regex =
        try {
            Regex(pattern)
        } catch (e: Exception) {
            return "Invalid regex pattern: ${e.message}"
        }
    val root = Paths.get(path)
    if (!Files.exists(root)) {
        return "Path not found: $path"
    }
    // Match `include` against each file's name so files at any depth (including directly under
    //  `path`) are considered.
    val matcher = FileSystems.getDefault().getPathMatcher("glob:$include")
    val results = mutableListOf<String>()

    walkFiles(root)
        .filter { it.isRegularFile() && matcher.matches(it.fileName) }
        .forEach { filepath ->
            val absolutePath = filepath.toAbsolutePath()
            try {
                // Scans file content line-by-line for regex matches
                filepath.useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        if (regex.containsMatchIn(line)) {
                            results.add("$absolutePath:${index + 1}: $line")
                        }
                    }
                }
            } catch (_: Exception) {
                // Skip files that cannot be read (e.g. permission denied or binary).
            }
        }
    return if (results.isNotEmpty()) results.joinToString("\n") else "(no matches)"
}

/**
 * Writes the specified content to a file at the given path. If the parent
 * directories do not exist, they will be created automatically.
 *
 * @param path The file path where the content should be written.
 * @param content The content to write to the file.
 * @return A message indicating the number of bytes written and the file path.
 */
fun writeFile(
    path: String,
    content: String,
): String {
    val p = Paths.get(path)
    p.parent?.let(Files::createDirectories)
    Files.writeString(p, content)
    return "Wrote ${content.toByteArray(Charsets.UTF_8).size} bytes to $path"
}

/**
 * Edits a file by replacing the first occurrence of a specified string with a new string.
 *
 * @param path The file path to be edited.
 * @param oldString The string to be replaced in the file.
 * @param newString The string to replace the old string with.
 * @return A message indicating the outcome of the editing operation. Returns an error message if the file does not
 * exist or if the specified string is not found in the file.
 */
fun editFile(
    path: String,
    oldString: String,
    newString: String,
): String {
    // Replace the first occurrence of old_string with new_string in a file.
    val p = Paths.get(path)
    if (!Files.exists(p)) {
        return "Error: file not found: $path"
    }
    val original = Files.readString(p)
    if (!original.contains(oldString)) {
        return "Error: string not found in $path"
    }
    Files.writeString(p, original.replaceFirst(oldString, newString))
    return "Edited $path"
}

/**
 * Fetches the given URL and returns its plain-text content extracted from the HTML.
 *
 * @param url The URL of the web page to fetch. Must use the "http" or "https" protocol.
 * @return The plain-text content of the fetched web page, or an error message if the fetch fails or the protocol is unsupported.
 */
fun webfetch(url: String): String {
    val maxResponseBytes = 2 * 1024 * 1024
    var connection: HttpURLConnection? = null
    try {
        val parsed = URI(url).toURL()
        if (parsed.protocol !in listOf("http", "https")) {
            return "Error fetching $url: unsupported scheme '${parsed.protocol}'."
        }
        connection = parsed.openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "agent/1.0")
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.connect()
        val raw =
            connection.inputStream.use { it.readNBytes(maxResponseBytes) }.toString(Charsets.UTF_8)
        val soup = Jsoup.parse(raw)
        return soup.text().replace("\n{3,}".toRegex(), "\n\n").trim()
    } catch (e: Exception) {
        return "Error fetching $url: ${e.message ?: e.javaClass.simpleName}"
    } finally {
        connection?.disconnect()
    }
}

val scratchpad = Scratchpad()

/**
 * Reads and retrieves the contents of the scratchpad.
 *
 * @return A string representing the current content of the scratchpad. If the scratchpad is empty,
 * it returns the string "(empty)".
 */
fun readScratchpad(): String = scratchpad.read()

/**
 * Writes the specified content into the scratchpad, overwriting any previous content.
 *
 * @param content The text to be written into the scratchpad.
 * @return A confirmation message indicating the content was successfully written.
 */
fun writeScratchpad(content: String): String {
    scratchpad.write(content)
    return "Successfully written content into scratchpad"
}

val todoStore = ToDoList()

/**
 * Appends a new to-do item to the to-do list.
 *
 * @param id The unique identifier for the to-do item.
 * @param content The description or content of the to-do item.
 * @param status The status of the to-do item.
 * @return A message indicating the success or failure of the operation.
 */
fun todoAppend(
    id: Int,
    content: String,
    status: String,
): String {
    val idStr = id.toString()
    return try {
        todoStore.append(idStr, content, TaskStatus.from(status))
        "Successfully appended to do item $idStr in to do list!"
    } catch (e: Exception) {
        "Failed to append to do item: ${e.message}"
    }
}

/**
 * Generates a formatted string representation of the to-do list.
 *
 * @param includeCompleted A boolean flag indicating whether to include completed items (e.g., tasks with a status of "done" or "cancelled").
 * If set to `true`, completed tasks will be included in the list. Defaults to `false`.
 * @return A formatted string showing the to-do list with item counts per status and item details,
 * including retries if applicable.
 */
fun todoList(includeCompleted: Boolean = false): String {
    val items = todoStore.read(includeCompleted)

    var result = "To Do List (${items.size} items)\n"
    TaskStatus.entries.forEach { status ->
        val count = items.count { it["status"] == status.wire }
        result += "$count ${status.name} items\n"
    }

    result += "-----\n"
    for (item in items) {
        val retries = item["retries"] as Int
        val retryNote = if (retries > 0) ", $retries retries" else ""
        result += "- [${item["id"]}] ${
            item["content"]
        } (${item["status"]}$retryNote)\n"
    }

    return result
}

/**
 * Updates a to-do item with the provided content and/or status.
 *
 * @param id The unique identifier of the to-do item to update.
 * @param content The new content or description for the to-do item. If null, the content will not be updated.
 * @param status The new status for the to-do item. If null, the status will not be updated.
 * @return A string message indicating the success or failure of the update operation. The message may include additional
 * information, such as retry attempt details, if applicable.
 */
fun todoUpdate(
    id: String,
    content: String? = null,
    status: TaskStatus? = null,
): String {
    if (content == null && status == null) {
        return "No content or status was given to update. Nothing to do."
    }
    return try {
        val item = todoStore.update(id, content, status)
        val retries = item["retries"] as Int
        // Evaluates retry status and returns appropriate progress message
        if (item["status"] == "in_progress" && retries > 0) {
            return if (retries >= RETRY_LIMIT) {
                "Updated to do item $id to in_progress — but this is retry $retries of $RETRY_LIMIT (retry limit reached). Do not retry again. Escalate to the user instead."
            } else {
                "Successfully updated to do item $id! Retry attempt $retries of $RETRY_LIMIT."
            }
        }
        "Successfully updated to do item $id!"
    } catch (e: Exception) {
        "Failed to update to do item $id: ${e.message}"
    }
}

/**
 * Asks the user a clarifying question via stdin and returns their answer.
 *
 * @param question The question to show the user.
 * @return The user's trimmed answer, `"(no answer provided)"` if they entered nothing, or
 *   `"(no answer - EOF)"` if input is exhausted.
 */
fun askQuestion(question: String): String {
    println("\n  [agent] $question")
    print("  Your answer: ")
    val answer = readlnOrNull()?.trim()
    return when {
        answer == null -> "(no answer - EOF)"
        answer.isEmpty() -> "(no answer provided)"
        else -> answer
    }
}

fun getToolSchemas(): List<Map<String, Any>> =
    listOf(
        mapOf(
            "type" to "function",
            "function" to
                mapOf(
                    "name" to "run_bash",
                    "description" to "Run a bash command on the user's machine and return the output.",
                    "parameters" to
                        mapOf(
                            "type" to "object",
                            "properties" to
                                mapOf(
                                    "command" to
                                        mapOf(
                                            "type" to "string",
                                            "description" to "The bash command to execute.",
                                        ),
                                ),
                            "required" to listOf("command"),
                        ),
                ),
        ),
        mapOf(
            "type" to "function",
            "function" to
                mapOf(
                    "name" to "read_file",
                    "description" to "Read lines from a file.",
                    "parameters" to
                        mapOf(
                            "type" to "object",
                            "properties" to
                                mapOf(
                                    "path" to
                                        mapOf(
                                            "type" to "string",
                                            "description" to "Absolute or relative path to the file.",
                                        ),
                                    "offset" to
                                        mapOf(
                                            "type" to "integer",
                                            "description" to "First line to read (1-indexed). Defaults to 1.",
                                        ),
                                    "limit" to
                                        mapOf(
                                            "type" to "integer",
                                            "description" to "Maximum number of lines to return. Defaults to 200.",
                                        ),
                                ),
                            "required" to listOf("path"),
                        ),
                ),
        ),
        mapOf(
            "type" to "function",
            "function" to
                mapOf(
                    "name" to "glob_files",
                    "description" to "Find files matching a glob pattern (e.g. '**/*.py') inside a directory.",
                    "parameters" to
                        mapOf(
                            "type" to "object",
                            "properties" to
                                mapOf(
                                    "pattern" to
                                        mapOf(
                                            "type" to "string",
                                            "description" to "Glob pattern to match against file names.",
                                        ),
                                    "path" to
                                        mapOf(
                                            "type" to "string",
                                            "description" to "Root directory to search in. Defaults to '.'.",
                                        ),
                                ),
                            "required" to listOf("pattern"),
                        ),
                ),
        ),
        mapOf(
            "type" to "function",
            "function" to
                mapOf(
                    "name" to "grep",
                    "description" to "Search file contents for a regex pattern and return matching lines with file paths and line numbers.",
                    "parameters" to
                        mapOf(
                            "type" to "object",
                            "properties" to
                                mapOf(
                                    "pattern" to
                                        mapOf(
                                            "type" to "string",
                                            "description" to "Regular expression to search for.",
                                        ),
                                    "path" to
                                        mapOf(
                                            "type" to "string",
                                            "description" to "Directory to search in. Defaults to '.'.",
                                        ),
                                    "include" to
                                        mapOf(
                                            "type" to "string",
                                            "description" to
                                                "Filename glob to restrict which files are searched (e.g. '*.py'). Defaults to '*'.",
                                        ),
                                ),
                            "required" to listOf("pattern"),
                        ),
                ),
        ),
        mapOf(
            "type" to "function",
            "function" to
                mapOf(
                    "name" to "write_file",
                    "description" to "Write content to a file, creating it (and any missing parent directories) if it does not exist.",
                    "parameters" to
                        mapOf(
                            "type" to "object",
                            "properties" to
                                mapOf(
                                    "path" to
                                        mapOf(
                                            "type" to "string",
                                            "description" to "Path of the file to write.",
                                        ),
                                    "content" to
                                        mapOf(
                                            "type" to "string",
                                            "description" to "Full content to write to the file.",
                                        ),
                                ),
                            "required" to listOf("path", "content"),
                        ),
                ),
        ),
        mapOf(
            "type" to "function",
            "function" to
                mapOf(
                    "name" to "edit_file",
                    "description" to "Replace the first occurrence of a string in a file with a new string.",
                    "parameters" to
                        mapOf(
                            "type" to "object",
                            "properties" to
                                mapOf(
                                    "path" to
                                        mapOf(
                                            "type" to "string",
                                            "description" to "Path of the file to edit.",
                                        ),
                                    "old_string" to
                                        mapOf(
                                            "type" to "string",
                                            "description" to "Exact string to find and replace.",
                                        ),
                                    "new_string" to
                                        mapOf(
                                            "type" to "string",
                                            "description" to "String to replace it with.",
                                        ),
                                ),
                            "required" to listOf("path", "old_string", "new_string"),
                        ),
                ),
        ),
        mapOf(
            "type" to "function",
            "function" to
                mapOf(
                    "name" to "webfetch",
                    "description" to "Fetch a public URL (http/https only) and return its full plain-text content (up to 2 MB).",
                    "parameters" to
                        mapOf(
                            "type" to "object",
                            "properties" to
                                mapOf(
                                    "url" to
                                        mapOf(
                                            "type" to "string",
                                            "description" to "The URL to fetch (http/https).",
                                        ),
                                ),
                            "required" to listOf("url"),
                        ),
                ),
        ),
        mapOf(
            "type" to "function",
            "function" to
                mapOf(
                    "name" to "read_scratchpad",
                    "description" to "Read the current contents of the in-memory scratchpad.",
                    "parameters" to
                        mapOf(
                            "type" to "object",
                            "properties" to emptyMap<String, Any>(),
                        ),
                ),
        ),
        mapOf(
            "type" to "function",
            "function" to
                mapOf(
                    "name" to "write_scratchpad",
                    "description" to "Replace the current contents of the in-memory scratchpad.",
                    "parameters" to
                        mapOf(
                            "type" to "object",
                            "properties" to
                                mapOf(
                                    "content" to
                                        mapOf(
                                            "type" to "string",
                                            "description" to "The content to store in the scratchpad.",
                                        ),
                                ),
                            "required" to listOf("content"),
                        ),
                ),
        ),
        mapOf(
            "type" to "function",
            "function" to
                mapOf(
                    "name" to "todo_append",
                    "description" to "Add a new item to the in-memory to-do list.",
                    "parameters" to
                        mapOf(
                            "type" to "object",
                            "properties" to
                                mapOf(
                                    "id" to
                                        mapOf(
                                            "type" to "integer",
                                            "description" to "The unique numeric identifier for the item.",
                                        ),
                                    "content" to
                                        mapOf(
                                            "type" to "string",
                                            "description" to "The task description.",
                                        ),
                                    "status" to
                                        mapOf(
                                            "type" to "string",
                                            "enum" to TaskStatus.entries.map { it.wire },
                                            "description" to "The initial status of the item.",
                                        ),
                                ),
                            "required" to listOf("id", "content", "status"),
                        ),
                ),
        ),
        mapOf(
            "type" to "function",
            "function" to
                mapOf(
                    "name" to "todo_list",
                    "description" to "List to-do items and summarize their statuses.",
                    "parameters" to
                        mapOf(
                            "type" to "object",
                            "properties" to
                                mapOf(
                                    "include_completed" to
                                        mapOf(
                                            "type" to "boolean",
                                            "description" to "Whether to include done and cancelled items. Defaults to false.",
                                        ),
                                ),
                        ),
                ),
        ),
        mapOf(
            "type" to "function",
            "function" to
                mapOf(
                    "name" to "todo_update",
                    "description" to "Update the content or status of an existing to-do item.",
                    "parameters" to
                        mapOf(
                            "type" to "object",
                            "properties" to
                                mapOf(
                                    "id" to
                                        mapOf(
                                            "type" to "string",
                                            "description" to "The unique identifier of the item to update.",
                                        ),
                                    "content" to
                                        mapOf(
                                            "type" to "string",
                                            "description" to "The replacement task description.",
                                        ),
                                    "status" to
                                        mapOf(
                                            "type" to "string",
                                            "enum" to TaskStatus.entries.map { it.wire },
                                            "description" to "The replacement status.",
                                        ),
                                ),
                            "required" to listOf("id"),
                        ),
                ),
        ),
        mapOf(
            "type" to "function",
            "function" to
                mapOf(
                    "name" to "ask_question",
                    "description" to "Ask the user a clarifying question via stdin and return their answer.",
                    "parameters" to
                        mapOf(
                            "type" to "object",
                            "properties" to
                                mapOf(
                                    "question" to
                                        mapOf(
                                            "type" to "string",
                                            "description" to "The question to show the user.",
                                        ),
                                ),
                            "required" to listOf("question"),
                        ),
                ),
        ),
    )
