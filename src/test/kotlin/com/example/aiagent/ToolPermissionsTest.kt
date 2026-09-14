package com.example.aiagent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolPermissionsTest {
    /**
     * Validates every tool exposed to the model is either categorized into one of the three
     * permission sets, or is a known dangerous tool allowed to fall through by omission.
     */
    @Test
    fun `every tool exposed to the model is categorized or a known dangerous tool`() {
        val toolNames = getToolSchemas().map { (it["function"] as Map<*, *>)["name"] as String }.toSet()
        val categorized = READ_TOOLS + PLANNING_TOOLS + WRITE_TOOLS
        val knownDangerous = setOf("run_bash", "webfetch")
        assertEquals(toolNames, categorized + knownDangerous)
    }

    @Test
    fun `read tools are read-only`() {
        listOf("read_file", "glob_files", "grep").forEach { name ->
            assertTrue(name in READ_TOOLS, "$name should be in READ_TOOLS")
        }
    }

    @Test
    fun `planning tools cover bookkeeping and user interaction`() {
        listOf(
            "todo_append",
            "todo_list",
            "todo_update",
            "read_scratchpad",
            "write_scratchpad",
        ).forEach { name ->
            assertTrue(name in PLANNING_TOOLS, "$name should be in PLANNING_TOOLS")
        }
    }

    @Test
    fun `file mutation tools are write`() {
        listOf("write_file", "edit_file").forEach { name ->
            assertTrue(name in WRITE_TOOLS, "$name should be in WRITE_TOOLS")
        }
    }

    @Test
    fun `shell and network tools are not categorized`() {
        listOf("run_bash", "webfetch").forEach { name ->
            assertFalse(name in READ_TOOLS, "$name should not be in READ_TOOLS")
            assertFalse(name in PLANNING_TOOLS, "$name should not be in PLANNING_TOOLS")
            assertFalse(name in WRITE_TOOLS, "$name should not be in WRITE_TOOLS")
        }
    }

    @Test
    fun `default mode only allows read and planning tools`() {
        assertTrue(isToolAllowed("read_file", PermissionMode.DEFAULT))
        assertTrue(isToolAllowed("todo_list", PermissionMode.DEFAULT))
        assertFalse(isToolAllowed("write_file", PermissionMode.DEFAULT))
        assertFalse(isToolAllowed("edit_file", PermissionMode.DEFAULT))
        assertFalse(isToolAllowed("run_bash", PermissionMode.DEFAULT))
        assertFalse(isToolAllowed("webfetch", PermissionMode.DEFAULT))
    }

    @Test
    fun `accept edits mode also allows write tools but not dangerous ones`() {
        assertTrue(isToolAllowed("read_file", PermissionMode.ACCEPT_EDITS))
        assertTrue(isToolAllowed("write_file", PermissionMode.ACCEPT_EDITS))
        assertTrue(isToolAllowed("edit_file", PermissionMode.ACCEPT_EDITS))
        assertFalse(isToolAllowed("run_bash", PermissionMode.ACCEPT_EDITS))
        assertFalse(isToolAllowed("webfetch", PermissionMode.ACCEPT_EDITS))
    }

    @Test
    fun `dangerously skip permissions mode allows every tool`() {
        val toolNames = getToolSchemas().map { (it["function"] as Map<*, *>)["name"] as String }
        toolNames.forEach { name ->
            assertTrue(isToolAllowed(name, PermissionMode.DANGEROUSLY_SKIP_PERMISSIONS))
        }
    }

    @Test
    fun `unrecognized tool name fails closed as dangerous`() {
        assertFalse(isToolAllowed("some_unknown_tool", PermissionMode.DEFAULT))
        assertFalse(isToolAllowed("some_unknown_tool", PermissionMode.ACCEPT_EDITS))
        assertTrue(isToolAllowed("some_unknown_tool", PermissionMode.DANGEROUSLY_SKIP_PERMISSIONS))
    }
}
