package com.example.aiagent

import kotlin.test.Test
import kotlin.test.assertEquals

class ScratchpadTest {
    @Test
    fun `read returns a placeholder when the scratchpad is empty`() {
        assertEquals("(empty)", Scratchpad().read())
    }

    @Test
    fun `read returns the stored content`() {
        val pad = Scratchpad()
        pad.write("hello")
        assertEquals("hello", pad.read())
    }

    @Test
    fun `write trims surrounding whitespace and returns the trimmed value`() {
        val pad = Scratchpad()
        assertEquals("hello", pad.write("  hello  \n\t"))
        assertEquals("hello", pad.read())
    }

    @Test
    fun `write replaces any previously stored content`() {
        val pad = Scratchpad()
        pad.write("first")
        pad.write("second")
        assertEquals("second", pad.read())
    }
}
