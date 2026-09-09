package com.example.aiagent

/**
 * A class representing an in-memory scratchpad for storing and retrieving textual content.
 * Provides functionality to write to and read from the scratchpad.
 */
class Scratchpad {
    private var content: String = ""

    fun read(): String =
        if (content == "") {
            "(empty)"
        } else {
            content
        }

    fun write(content: String): String {
        this@Scratchpad.content = content.trim()
        return this@Scratchpad.content
    }
}
