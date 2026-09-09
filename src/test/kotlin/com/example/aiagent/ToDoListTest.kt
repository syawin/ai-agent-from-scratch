package com.example.aiagent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToDoListTest {
    // --- TaskStatus parsing tests ---

    /**
     * Validates correct mapping of all wire-format status strings
     */
    @Test
    fun `from resolves every wire spelling`() {
        assertEquals(TaskStatus.PENDING, TaskStatus.from("pending"))
        assertEquals(TaskStatus.IN_PROGRESS, TaskStatus.from("in_progress"))
        assertEquals(TaskStatus.DONE, TaskStatus.from("done"))
        assertEquals(TaskStatus.CANCELLED, TaskStatus.from("cancelled"))
        assertEquals(TaskStatus.FAILED, TaskStatus.from("failed"))
    }

    @Test
    fun `from rejects an unknown status and enumerates the valid ones`() {
        // Asserts on the message, not just the type: the code throws a bare Exception, so matching
        // on the type alone would also swallow an NPE from a broken refactor and still report green.
        // This is also the only check that proves the message is derived from TaskStatus.entries.
        val error = assertFailsWith<Exception> { TaskStatus.from("bogus") }
        assertEquals(
            "Invalid status bogus. Valid to-do statuses: pending, in_progress, done, cancelled, failed",
            error.message,
        )
    }

    @Test
    fun `from rejects the space-separated spelling of in_progress`() {
        // Guards the bug this refactor fixed: "in progress" was once the only accepted spelling.
        assertFailsWith<Exception> { TaskStatus.from("in progress") }
    }

    /**
     * Verifies completion logic for specific task statuses
     */
    @Test
    fun `only done and cancelled count as completed`() {
        assertTrue(TaskStatus.DONE.isCompleted)
        assertTrue(TaskStatus.CANCELLED.isCompleted)
        assertFalse(TaskStatus.PENDING.isCompleted)
        assertFalse(TaskStatus.IN_PROGRESS.isCompleted)
        assertFalse(TaskStatus.FAILED.isCompleted, "A failed task is still actionable")
    }
    // Tests field access and wire-format serialization of items

    // --- ToDoItem tests ---

    /**
     * Tests field access and wire-format serialization of items
     */
    @Test
    fun `ToDoItem exposes its fields and renders the status as a wire string`() {
        val item = ToDoItem(id = "1", content = "task", status = TaskStatus.FAILED, retries = 2)

        assertEquals("1", item.id)
        assertEquals("task", item.content)
        assertEquals(TaskStatus.FAILED, item.status)
        assertEquals(2, item.retries)
        assertEquals(
            mapOf<String, Any>(
                "id" to "1",
                "content" to "task",
                "status" to "failed",
                "retries" to 2,
            ),
            item.toMap(),
        )
    }

    @Test
    fun `ToDoItem defaults retries to zero`() {
        assertEquals(0, ToDoItem(id = "1", content = "task", status = TaskStatus.PENDING).retries)
    }

    // --- append tests ---

    @Test
    fun `append returns the new item with a wire status string`() {
        val list = ToDoList()

        val item = list.append("1", "write tests", TaskStatus.IN_PROGRESS)

        assertEquals(
            mapOf<String, Any>(
                "id" to "1",
                "content" to "write tests",
                "status" to "in_progress",
                "retries" to 0,
            ),
            item,
        )
    }

    /**
     * Enforces uniqueness constraints on task identifiers
     */
    @Test
    fun `append rejects a duplicate id`() {
        val list = ToDoList()
        list.append("1", "first", TaskStatus.PENDING)

        val error = assertFailsWith<Exception> { list.append("1", "second", TaskStatus.PENDING) }

        // Validates presence and absence detection for list items
        assertEquals("To do item with id 1 already exists.", error.message)
    }

    @Test
    fun `contains reports presence and absence`() {
        val list = ToDoList()
        list.append("1", "task", TaskStatus.PENDING)

        assertTrue(list.contains("1"))
        assertFalse(list.contains("2"))
    }

    // --- read tests ---

    @Test
    fun `read hides completed items but keeps failed ones visible`() {
        val list = seededList()

        val ids = list.read().map { it["id"] }

        assertEquals(listOf("pending-1", "in-progress-1", "failed-1"), ids)
    }

    @Test
    fun `read includes completed items on request`() {
        val list = seededList()

        val ids = list.read(includeCompleted = true).map { it["id"] }

        assertEquals(listOf("pending-1", "in-progress-1", "done-1", "cancelled-1", "failed-1"), ids)
    }

    @Test
    fun `read returns an empty list when there is nothing to do`() {
        assertEquals(emptyList(), ToDoList().read())
    }

    @Test
    fun `read returns copies that cannot mutate the list`() {
        val list = ToDoList()
        list.append("1", "original", TaskStatus.PENDING)

        val snapshot = list.read().single().toMutableMap()
        snapshot["content"] = "tampered"

        assertEquals("original", list.read().single()["content"])
    }

    // --- update tests ---

    @Test
    fun `update changes content and status`() {
        val list = ToDoList()
        list.append("1", "old", TaskStatus.PENDING)

        val updated = list.update("1", content = "new", status = TaskStatus.DONE)

        assertEquals("new", updated["content"])
        assertEquals("done", updated["status"])
    }

    @Test
    fun `update leaves the item untouched when given no changes`() {
        val list = ToDoList()
        val original = list.append("1", "unchanged", TaskStatus.PENDING)

        assertEquals(original, list.update("1"))
    }

    @Test
    fun `update increments retries when a failed task returns to in_progress`() {
        val list = ToDoList()
        list.append("1", "flaky", TaskStatus.FAILED)

        val retried = list.update("1", status = TaskStatus.IN_PROGRESS)

        assertEquals(1, retried["retries"])
    }

    @Test
    fun `update accumulates retries across repeated failures`() {
        val list = ToDoList()
        list.append("1", "flaky", TaskStatus.PENDING)

        repeat(3) {
            list.update("1", status = TaskStatus.FAILED)
            list.update("1", status = TaskStatus.IN_PROGRESS)
        }

        assertEquals(3, list.read().single()["retries"])
    }

    @Test
    fun `update does not increment retries for other transitions`() {
        val list = ToDoList()
        list.append("1", "smooth", TaskStatus.PENDING)

        // Only failed -> in_progress is a retry; a first attempt is not.
        assertEquals(0, list.update("1", status = TaskStatus.IN_PROGRESS)["retries"])
        assertEquals(0, list.update("1", status = TaskStatus.DONE)["retries"])
        assertEquals(0, list.update("1", status = TaskStatus.FAILED)["retries"])
        // Abandoning a failed task is not a retry either.
        assertEquals(0, list.update("1", status = TaskStatus.CANCELLED)["retries"])
    }

    @Test
    fun `update rejects an unknown id`() {
        val error = assertFailsWith<Exception> { ToDoList().update("missing", content = "x") }

        assertEquals("To do item with id missing not found", error.message)
    }

    @Test
    fun `update targets only the requested item`() {
        val list = ToDoList()
        list.append("1", "first", TaskStatus.PENDING)
        list.append("2", "second", TaskStatus.PENDING)

        list.update("2", content = "edited")

        val contents = list.read().associate { it["id"] as String to it["content"] as String }
        assertEquals(mapOf("1" to "first", "2" to "edited"), contents)
    }

    private fun seededList(): ToDoList =
        ToDoList().apply {
            append("pending-1", "pending task", TaskStatus.PENDING)
            append("in-progress-1", "active task", TaskStatus.IN_PROGRESS)
            append("done-1", "finished task", TaskStatus.DONE)
            append("cancelled-1", "abandoned task", TaskStatus.CANCELLED)
            append("failed-1", "broken task", TaskStatus.FAILED)
        }
}
