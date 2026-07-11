package com.example.aiagent

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
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
            listOf("run_bash", "read_file", "glob_files", "grep", "write_file", "edit_file", "webfetch"),
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
