package com.github.nizienko.cmdwidget.shared

import kotlinx.serialization.Serializable

@Serializable
enum class PresentationType { TEXT }

@Serializable
enum class ExecutionTarget { BACKEND, FRONTEND }

@Serializable
data class CmdWidgetConfiguration(
    val id: String,
    val name: String,
    val command: String,
    val refreshIntervalSeconds: Int = 10,
    val presentation: PresentationType = PresentationType.TEXT,
    val enabled: Boolean = true,
    val executionTarget: ExecutionTarget = ExecutionTarget.BACKEND,
) {
    fun validationError(): String? = when {
        id.isBlank() -> "Widget ID must not be blank"
        name.isBlank() -> "Name must not be blank"
        name.length > 40 -> "Name must be at most 40 characters"
        command.isBlank() -> "Command must not be blank"
        refreshIntervalSeconds < 1 -> "Refresh interval must be at least one second"
        else -> null
    }
}

/** Raw execution state. Formatting and terminal-sequence removal belong to the frontend. */
@Serializable
data class WidgetState(
    val configuration: CmdWidgetConfiguration,
    val revision: Long,
    val refreshing: Boolean = false,
    val lastSuccessfulResult: CommandResult? = null,
    val latestResult: CommandResult? = null,
)

@Serializable
data class ProjectWidgetState(val version: Long = 0, val widgets: List<WidgetState> = emptyList())
