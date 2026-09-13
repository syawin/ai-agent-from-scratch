package com.example.aiagent

/**
 * Represents the different modes of permissions that can be assigned or used within the system.
 *
 * @property value The string representation of the permission mode.
 */
enum class PermissionMode(
    val value: String,
) {
    DEFAULT("default"),
    ACCEPT_EDITS("acceptEdits"),
    DANGEROUSLY_SKIP_PERMISSIONS("dangerouslySkipPermissions")
}
