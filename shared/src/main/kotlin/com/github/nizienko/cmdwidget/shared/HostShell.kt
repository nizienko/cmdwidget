package com.github.nizienko.cmdwidget.shared

import java.util.Locale

/** Shell syntax belongs to the execution host, including in remote development. */
enum class HostShell {
    POSIX, WINDOWS;

    fun commandLine(shell: String, command: String): List<String> = when (this) {
        POSIX -> listOf(shell, "-lc", command)
        // /d disables AutoRun. Do not pass /a: it forces redirected output from
        // cmd built-ins through the legacy ANSI code page and replaces Unicode
        // characters before CommandExecutor can decode the stream as UTF-8.
        WINDOWS -> listOf(shell, "/d", "/s", "/c", "chcp 65001 >nul && $command")
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
