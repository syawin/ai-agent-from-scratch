package com.example.aiagent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolPermissionsTest {
    /**
     * Validates all exposed tools possess assigned categories
     */
    @Test
    fun `every tool exposed to the model has a category`() {
        val toolNames = getToolSchemas().map { (it["function"] as Map<*, *>)["name"] as String }
        toolNames.forEach { name ->
            assertTrue(name in TOOL_CATEGORIES, "Tool '$name' is missing from TOOL_CATEGORIES")
        }
        assertEquals(toolNames.toSet(), TOOL_CATEGORIES.keys)
    }

    @Test
    fun `read and planning tools are read-only`() {
        val readOnlyTools =
            listOf(
                "read_file",
                "glob_files",
                "grep",
                "read_scratchpad",
                "write_scratchpad",
                "todo_append",
                "todo_list",
                "todo_update",
            )
        readOnlyTools.forEach { name ->
            assertEquals(ToolCategory.READ_ONLY, TOOL_CATEGORIES[name], "$name should be READ_ONLY")
        }
    }

    @Test
    fun `file mutation tools are write`() {
        listOf("write_file", "edit_file").forEach { name ->
            assertEquals(ToolCategory.WRITE, TOOL_CATEGORIES[name], "$name should be WRITE")
        }
    }

    @Test
    fun `shell and network tools are dangerous`() {
        listOf("run_bash", "webfetch").forEach { name ->
            assertEquals(ToolCategory.DANGEROUS, TOOL_CATEGORIES[name], "$name should be DANGEROUS")
        }
    }

    @Test
    fun `default mode only allows read-only tools`() {
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
        TOOL_CATEGORIES.keys.forEach { name ->
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
