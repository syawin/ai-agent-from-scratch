package com.example.aiagent

import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
