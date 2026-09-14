package com.example.aiagent

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolsTest {
    @Test
    fun `runBash returns stdout and stderr`() {
        assertEquals("hello\nSTDERR:\nwarning", runBash("printf hello; printf warning >&2"))
    }

    @Test
    fun `runBash describes a command with no output`() {
        assertEquals("(no output)", runBash("true"))
    }

    @Test
    fun `readFile returns the requested lines`() {
        val file = Files.createTempFile("read-file", ".txt")
        try {
            Files.writeString(file, "one\ntwo\nthree\nfour")
            assertEquals("two\nthree", readFile(file.toString(), offset = 2, limit = 2))
            assertEquals("one", readFile(file.toString(), offset = 0, limit = 1))
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `readFile reports a missing file`() {
        assertEquals("File not found: missing-file", readFile("missing-file"))
    }

    @Test
    fun `readFile treats a negative limit as an empty range`() {
        val file = Files.createTempFile("read-file", ".txt")
        try {
            Files.writeString(file, "content")
            assertEquals("", readFile(file.toString(), limit = -1))
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `globFiles finds direct and nested matches in sorted order`() {
        val root = Files.createTempDirectory("glob-files")
        try {
            val nested = root.resolve("nested").createDirectories()
            Files.writeString(root.resolve("b.txt"), "b")
            Files.writeString(nested.resolve("a.txt"), "a")
            Files.writeString(nested.resolve("ignored.kt"), "ignored")
            nested.resolve("directory.txt").createDirectories()

            assertEquals(
                listOf(root.resolve("b.txt"), nested.resolve("a.txt")).map { it.toString() }.sorted().joinToString("\n"),
                globFiles("*.txt", root.toString()),
            )
            assertEquals("(no matches)", globFiles("*.md", root.toString()))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `globFiles reports a missing path`() {
        assertEquals("Path not found: missing-directory", globFiles("*", "missing-directory"))
    }

    @Test
    fun `globFiles uses the current directory by default`() {
        assertEquals("(no matches)", globFiles("definitely-not-a-real-file-${UUID.randomUUID()}"))
    }

    @Test
    fun `grep finds matching lines in included files`() {
        val root = Files.createTempDirectory("grep-files")
        try {
            val nested = root.resolve("nested").createDirectories()
            val direct = root.resolve("direct.txt")
            val nestedFile = nested.resolve("nested.txt")
            Files.writeString(direct, "alpha\nbeta")
            Files.writeString(nestedFile, "gamma alpha")
            Files.writeString(nested.resolve("ignored.kt"), "alpha")

            assertEquals(
                listOf("${direct.toAbsolutePath()}:1: alpha", "${nestedFile.toAbsolutePath()}:1: gamma alpha").joinToString("\n"),
                grep("alpha", root.toString(), "*.txt"),
            )
            assertEquals("(no matches)", grep("absent", root.toString()))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `grep reports invalid patterns and missing paths`() {
        assertTrue(grep("[", ".").startsWith("Invalid regex pattern:"))
        assertEquals("Path not found: missing-directory", grep("text", "missing-directory"))
    }

    @Test
    fun `writeFile writes a bare filename in the current directory`() {
        val filename = "write-file-${UUID.randomUUID()}.txt"
        val file = Paths.get(filename)
        val content = "hello"

        // Executes file write test; ensures cleanup of artifacts
        try {
            val result = writeFile(filename, content)

            assertEquals("Wrote 5 bytes to $filename", result)
            assertEquals(content, Files.readString(file))
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `writeFile reports UTF-8 byte count for non-ASCII content`() {
        val file = Files.createTempFile("write-file", ".txt")
        val content = "café ☕"

        try {
            val result = writeFile(file.toString(), content)

            assertEquals("Wrote ${content.toByteArray(Charsets.UTF_8).size} bytes to $file", result)
            assertEquals(content, Files.readString(file))
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `writeFile creates missing parent directories`() {
        val root = Files.createTempDirectory("write-file")
        val file = root.resolve("one/two/file.txt")
        try {
            assertEquals("Wrote 7 bytes to $file", writeFile(file.toString(), "content"))
            assertEquals("content", Files.readString(file))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `editFile replaces only the first occurrence`() {
        val file = Files.createTempFile("edit-file", ".txt")
        try {
            Files.writeString(file, "old and old")
            assertEquals("Edited $file", editFile(file.toString(), "old", "new"))
            assertEquals("new and old", Files.readString(file))
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `editFile reports missing file and missing string`() {
        assertEquals("Error: file not found: missing-file", editFile("missing-file", "old", "new"))
        val file = Files.createTempFile("edit-file", ".txt")
        try {
            Files.writeString(file, "content")
            assertEquals("Error: string not found in $file", editFile(file.toString(), "old", "new"))
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `getToolSchemas defines each registered tool once`() {
        val names =
            getToolSchemas().map { schema ->
                @Suppress("UNCHECKED_CAST")
                val function = schema.getValue("function") as Map<String, Any>
                function.getValue("name") as String
            }

        assertEquals(
            listOf(
                "run_bash",
                "read_file",
                "glob_files",
                "grep",
                "write_file",
                "edit_file",
                "webfetch",
                "read_scratchpad",
                "write_scratchpad",
                "todo_append",
                "todo_list",
                "todo_update",
                "ask_question",
            ),
            names,
        )
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `webfetch extracts plain text from HTML`() =
        withHttpResponse(
            status = 200,
            body = "<html><head><title>Example</title></head><body><h1>Hello</h1><p>from the web</p></body></html>",
        ) { url ->
            assertEquals("Example Hello from the web", webfetch(url))
        }

    @Test
    fun `webfetch reads at most two MiB`() {
        val markerBeyondLimit = "CONTENT_BEYOND_LIMIT"
        val body = "a".repeat(2 * 1024 * 1024) + markerBeyondLimit

        withHttpResponse(status = 200, body = body) { url ->
            val result = webfetch(url)

            assertEquals(2 * 1024 * 1024, result.length)
            assertFalse(result.contains(markerBeyondLimit))
        }
    }

    @Test
    fun `webfetch returns an error for an HTTP failure`() =
        withHttpResponse(status = 404, body = "not found") { url ->
            assertTrue(webfetch(url).startsWith("Error fetching $url:"))
        }

    @Test
    fun `webfetch returns an error for a malformed URL`() {
        val url = "://not-a-url"

        assertTrue(webfetch(url).startsWith("Error fetching $url:"))
    }

    @Test
    fun `webfetch rejects unsupported schemes`() {
        val url = "file:///tmp/example.html"

        assertEquals("Error fetching $url: unsupported scheme 'file'.", webfetch(url))
    }

    // --- Scratchpad tool tests ---

    @Test
    fun `writeScratchpad stores trimmed content and returns a fixed confirmation message`() {
        val marker = "scratchpad-${UUID.randomUUID()}"
        val result = writeScratchpad("  $marker  \n")
        assertEquals("Successfully written content into scratchpad", result)
        assertEquals(marker, readScratchpad())
        // Cross-file access forces the call through ToolsKt.getScratchpad()'s public accessor —
        // same-file access inside Tools.kt compiles to a direct getstatic and doesn't cover it.
        assertEquals(marker, scratchpad.read())
    }

    @Test
    fun `readScratchpad reflects the most recent write`() {
        val marker = "scratchpad-${UUID.randomUUID()}"
        writeScratchpad(marker)
        assertEquals(marker, readScratchpad())
    }

    // --- todoAppend tests ---

    @Test
    fun `todoAppend succeeds and reports the new item id`() {
        val id = 90001
        val result = todoAppend(id, "write coverage test", TaskStatus.PENDING.wire)
        assertEquals("Successfully appended to do item $id in to do list!", result)
        // Cross-file access forces ToolsKt.getTodoStore()'s public accessor, same reasoning as above.
        assertTrue(todoStore.contains("$id"))
    }

    @Test
    fun `todoAppend reports a failure for a duplicate id`() {
        val id = 90002
        todoAppend(id, "first", TaskStatus.PENDING.wire)
        val result = todoAppend(id, "second", TaskStatus.PENDING.wire)
        assertEquals("Failed to append to do item: To do item with id $id already exists.", result)
    }

    // --- todoList tests ---

    @Test
    fun `todoList reports a freshly appended pending item`() {
        val id = 90010
        todoAppend(id, "check todoList coverage", TaskStatus.PENDING.wire)
        assertContains(todoList(false), "[$id] check todoList coverage (pending)")
    }

    @Test
    fun `todoList hides completed items by default but includes them when requested`() {
        val id = 90012
        todoAppend(id, "finish todoList coverage", TaskStatus.PENDING.wire)
        todoUpdate("$id", status = TaskStatus.DONE)
        assertFalse(todoList(false).contains("[$id]"))
        assertContains(todoList(true), "[$id] finish todoList coverage (done)")
    }

    @Test
    fun `todoList with omitted argument defaults to excluding completed items`() {
        val id = 90013
        todoAppend(id, "default-bridge fixture", TaskStatus.PENDING.wire)
        todoUpdate("$id", status = TaskStatus.DONE)
        // todoList() with the argument omitted routes through the synthetic todoList$default bridge.
        assertEquals(todoList(false), todoList())
        assertFalse(todoList().contains("[$id]"))
    }

    // --- todoUpdate tests ---

    @Test
    fun `todoUpdate returns a no-op message when neither content nor status is given`() {
        assertEquals(
            "No content or status was given to update. Nothing to do.",
            todoUpdate("id-that-is-never-created-${UUID.randomUUID()}"),
        )
    }

    @Test
    fun `todoUpdate reports a failure for an unknown id`() {
        val id = "missing-${UUID.randomUUID()}"
        assertEquals(
            "Failed to update to do item $id: To do item with id $id not found",
            todoUpdate(id, content = "anything"),
        )
    }

    @Test
    fun `todoUpdate updates content without changing status`() {
        val id = "90103"
        todoAppend(id.toInt(), "content v1", TaskStatus.PENDING.wire)
        assertEquals("Successfully updated to do item $id!", todoUpdate(id, content = "content v2"))
    }

    @Test
    fun `todoUpdate reports the retry count when a failed item resumes in_progress`() {
        val id = "90101"
        todoAppend(id.toInt(), "flaky task", TaskStatus.PENDING.wire)
        todoUpdate(id, status = TaskStatus.FAILED)
        val message = todoUpdate(id, status = TaskStatus.IN_PROGRESS)
        assertEquals("Successfully updated to do item $id! Retry attempt 1 of $RETRY_LIMIT.", message)
    }

    @Test
    fun `todoUpdate reports when the retry limit has been reached`() {
        val id = "90102"
        todoAppend(id.toInt(), "very flaky task", TaskStatus.PENDING.wire)
        repeat(RETRY_LIMIT - 1) {
            todoUpdate(id, status = TaskStatus.FAILED)
            todoUpdate(id, status = TaskStatus.IN_PROGRESS)
        }
        todoUpdate(id, status = TaskStatus.FAILED)
        val message = todoUpdate(id, status = TaskStatus.IN_PROGRESS)
        assertEquals(
            "Updated to do item $id to in_progress — but this is retry $RETRY_LIMIT of $RETRY_LIMIT " +
                "(retry limit reached). Do not retry again. Escalate to the user instead.",
            message,
        )
    }

    @Test
    fun `askQuestion prints the question and returns the user's trimmed answer`() {
        val originalIn = System.`in`
        try {
            System.setIn(ByteArrayInputStream("  an answer  \n".toByteArray()))
            assertEquals("an answer", askQuestion("Which approach do you prefer?"))
        } finally {
            System.setIn(originalIn)
        }
    }

    @Test
    fun `askQuestion returns an empty string when input is exhausted`() {
        val originalIn = System.`in`
        try {
            System.setIn(ByteArrayInputStream(ByteArray(0)))
            assertEquals("", askQuestion("Which approach do you prefer?"))
        } finally {
            System.setIn(originalIn)
        }
    }

    private fun withHttpResponse(
        status: Int,
        body: String,
        assertion: (String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            assertion("http://127.0.0.1:${server.address.port}/")
        } finally {
            server.stop(0)
        }
    }
}
