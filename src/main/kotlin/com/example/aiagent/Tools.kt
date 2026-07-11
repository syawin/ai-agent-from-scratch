@file:Suppress("unused")

package com.example.aiagent

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors
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
        lines.drop(maxOf(0, offset - 1)).take(limit).joinToString("\n")
    }
}

/**
 * Walks the file tree rooted at [root] and returns every path it contains.
 */
private fun walkFiles(root: Path): List<Path> = Files.walk(root).use { it.collect(Collectors.toList()) }

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
                directMatcher.matches(relative) || nestedMatcher.matches(relative)
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
