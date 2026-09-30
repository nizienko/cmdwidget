package com.github.nizienko.cmdwidget.shared

import kotlinx.serialization.Serializable

/** Backend-host context, never a frontend filesystem path. */
@Serializable
data class ExecutionContext(
    val host: String,
    val workingDirectory: String?,
    val shell: String,
    val operatingSystem: String,
    val error: String? = null,
)

@Serializable
data class CommandResult(
    val context: ExecutionContext,
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
    val timedOut: Boolean = false,
    val durationMillis: Long = 0,
    val completedAtEpochMillis: Long,
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
    val startupError: String? = null,
    val contextError: String? = null,
    val executionError: String? = null,
    val cleanupError: String? = null,
) {
    val successful: Boolean
        get() = exitCode == 0 && !timedOut && startupError == null && contextError == null &&
            executionError == null && cleanupError == null
}
