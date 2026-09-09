package com.example.aiagent

const val RETRY_LIMIT = 3

/**
 * The lifecycle states a to-do task can occupy.
 *
 * Each constant carries the [wire] spelling used when the status crosses a boundary — tool-call
 * arguments coming in, serialized tool results going out. Keeping that spelling on the enum means
 * the set of valid values, their external names, and the "is this task finished" rule all live in
 * one place instead of being restated as string literals.
 */
enum class TaskStatus(
    val wire: String,
) {
    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    DONE("done"),
    CANCELLED("cancelled"),
    FAILED("failed"),
    ;

    /**
     * Whether the task is finished and should be hidden from [ToDoList.read] by default.
     *
     * `FAILED` is deliberately *not* completed: a failed task is still actionable (it can be
     * retried) and must stay visible.
     */
    val isCompleted: Boolean
        get() = this == DONE || this == CANCELLED

    companion object {
        /**
         * Resolves a [wire] spelling to its [TaskStatus].
         *
         * Used at boundaries where the status arrives as an untyped string, such as tool-call
         * arguments. Preferred over the generated `valueOf`, whose message is unhelpful — the
         * message thrown here is read by the model, so it enumerates the accepted values. That
         * list is derived from [entries] rather than hardcoded, so it cannot drift.
         *
         * @param value The external spelling of a status, e.g. `"in_progress"`.
         * @return The matching [TaskStatus].
         * @throws Exception If no status has that spelling.
         */
        fun from(value: String): TaskStatus =
            entries.firstOrNull { it.wire == value }
                ?: throw Exception(
                    "Invalid status $value. Valid to-do statuses: ${entries.joinToString(", ") { it.wire }}",
                )
    }
}

/**
 * A single to-do task.
 *
 * [content], [status], and [retries] are `var` by design: [ToDoList.update] mutates the task in
 * place, and [retries] has to accumulate across calls. Switching them to `val` plus `copy()` would
 * either lose the retry count or require re-inserting into the backing list. [id] is `val` because
 * it is the task's identity.
 */
data class ToDoItem(
    val id: String,
    var content: String,
    var status: TaskStatus,
    var retries: Int = 0,
) {
    /**
     * Renders the task as a plain map for callers outside this file.
     *
     * [status] is emitted as its [TaskStatus.wire] string rather than the enum, so that serializing
     * a tool result yields `"in_progress"` and not `"IN_PROGRESS"`. The copy also keeps callers
     * from mutating the list through a returned reference.
     */
    fun toMap(): Map<String, Any> =
        mapOf(
            "id" to id,
            "content" to content,
            "status" to status.wire,
            "retries" to retries,
        )
}

/**
 * A class to manage a list of to-do tasks.
 *
 * This class provides functionality to add, update, retrieve, and verify
 * the existence of tasks. Each task is stored as a [ToDoItem] and contains
 * attributes such as `id`, `content`, `status`, and the number of `retries`.
 * Valid statuses are enforced by the [TaskStatus] type.
 */
class ToDoList {
    private val items = mutableListOf<ToDoItem>()

    /**
     * Reads and retrieves a list of to-do items.
     *
     * @param includeCompleted Indicates whether to include completed items — those with a status of
     * "done" or "cancelled" — in the results. Note that "failed" items are not completed and are
     * always returned. Defaults to `false`.
     * @return A list of to-do items represented as maps, filtered or unfiltered based on the `includeCompleted` parameter.
     */
    fun read(includeCompleted: Boolean = false): List<Map<String, Any>> =
        items
            .filter { includeCompleted || !it.status.isCompleted }
            .map { it.toMap() }

    /**
     * Adds a new to-do item to the list if it does not already exist.
     *
     * @param id The unique identifier for the to-do item.
     * @param content The description or content of the to-do item.
     * @param status The status of the to-do item. Use [TaskStatus.from] to convert an external string.
     * @return A map representing the newly added to-do item.
     * @throws Exception If a to-do item with the given id already exists.
     */
    fun append(
        id: String,
        content: String,
        status: TaskStatus,
    ): Map<String, Any> {
        if (contains(id)) {
            throw Exception("To do item with id $id already exists.")
        }
        val newItem = ToDoItem(id = id, content = content, status = status)
        items.add(newItem)
        return newItem.toMap()
    }

    /**
     * Checks whether the to-do list contains an item with the specified identifier.
     *
     * @param id The unique identifier of the to-do item to check for.
     * @return `true` if an item with the specified identifier exists in the list, `false` otherwise.
     */
    fun contains(id: String): Boolean = items.any { it.id == id }

    /**
     * Updates an existing to-do item with the provided content and/or status.
     *
     * @param id The unique identifier of the to-do item to update.
     * @param content The new content or description for the to-do item. If not provided, the content remains unchanged.
     * @param status The new status for the to-do item. Use [TaskStatus.from] to convert an external
     * string. If not provided, the status remains unchanged.
     * @return A map representing the updated to-do item.
     * @throws Exception If a to-do item with the specified ID is not found.
     */
    fun update(
        id: String,
        content: String? = null,
        status: TaskStatus? = null,
    ): Map<String, Any> {
        val item = items.find { it.id == id } ?: throw Exception("To do item with id $id not found")
        if (content != null) {
            item.content = content
        }
        if (status != null) {
            // Read the outgoing status before overwriting it: a failed task being set back to
            // in_progress is a retry attempt.
            if (item.status == TaskStatus.FAILED && status == TaskStatus.IN_PROGRESS) {
                item.retries++
            }
            item.status = status
        }
        return item.toMap()
    }
}
