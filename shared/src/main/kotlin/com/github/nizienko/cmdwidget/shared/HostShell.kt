package com.github.nizienko.cmdwidget.shared

import java.util.Locale

/** Shell syntax belongs to the execution host, including in remote development. */
enum class HostShell {
    POSIX, WINDOWS;

    fun commandLine(shell: String, command: String): List<String> = when (this) {
        POSIX -> listOf(shell, "-lc", command)
        // /d disables AutoRun; /a avoids UTF-16 output from cmd built-ins.
        // The code page applies to the shell and newly launched console programs.
        WINDOWS -> listOf(shell, "/d", "/s", "/a", "/c", "chcp 65001 >nul && $command")
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
