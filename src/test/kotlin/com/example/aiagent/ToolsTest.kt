package com.example.aiagent

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolsTest {
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
