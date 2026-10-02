package com.github.nizienko.cmdwidget.shared

import java.util.Locale

/** Shell syntax belongs to the execution host, including in remote development. */
enum class HostShell {
    POSIX, WINDOWS;

    fun commandLine(shell: String, command: String): List<String> = when (this) {
        POSIX -> listOf(shell, "-lc", command)
        // /d disables AutoRun. /u preserves Unicode from cmd built-ins when
        // stdout/stderr are pipes; CommandExecutor detects and decodes that
        // UTF-16LE output while retaining UTF-8 support for child programs.
        WINDOWS -> listOf(shell, "/d", "/s", "/u", "/c", "chcp 65001 >nul && $command")
    }

    companion object {
        fun forOperatingSystem(operatingSystem: String): HostShell? {
            val os = operatingSystem.lowercase(Locale.ROOT)
            return when {
                os.startsWith("windows") -> WINDOWS
                os.contains("mac") || os.contains("linux") -> POSIX
                else -> null
            }
        }
    }
}
