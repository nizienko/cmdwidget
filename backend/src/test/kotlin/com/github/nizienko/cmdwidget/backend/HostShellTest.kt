package com.github.nizienko.cmdwidget.backend

import com.github.nizienko.cmdwidget.shared.CmdWidgetSettingsService
import com.github.nizienko.cmdwidget.shared.ExecutionContextResolver
import com.github.nizienko.cmdwidget.shared.HostShell
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HostShellTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `shell strategy follows host OS`() {
        assertEquals(HostShell.WINDOWS, HostShell.forOperatingSystem("Windows 11"))
        assertEquals(HostShell.WINDOWS, HostShell.forOperatingSystem("WINDOWS 10"))
        assertEquals(HostShell.POSIX, HostShell.forOperatingSystem("Mac OS X"))
        assertEquals(HostShell.POSIX, HostShell.forOperatingSystem("Linux"))
        assertNull(HostShell.forOperatingSystem("FreeBSD"))
        assertNull(HostShell.forOperatingSystem("Darwin"))
    }

    @Test fun `command remains one argument with quotes pipes and unicode`() {
        val command = "\"C:\\Program Files\\tool.exe\" \"Привет мир\" | findstr мир & exit /b 7"
        assertEquals(listOf("cmd.exe", "/d", "/s", "/u", "/c", "chcp 65001 >nul && $command"),
            HostShell.WINDOWS.commandLine("cmd.exe", command))
        assertEquals(listOf("/bin/sh", "-lc", command), HostShell.POSIX.commandLine("/bin/sh", command))
    }

    @Test fun `Windows resolves case insensitive ComSpec and ignores Unix SHELL`() {
        val cmd = temporary.newFile("cmd.exe").apply { setExecutable(true) }
        val resolved = ExecutionContextResolver.resolve(temporary.root.path,
            mapOf("comspec" to cmd.absolutePath, "SHELL" to "/bin/sh"), "Windows 11")
        assertNull(resolved.error)
        assertEquals(cmd.absolutePath, resolved.shell)
        val fallback = ExecutionContextResolver.resolve(temporary.root.path,
            mapOf("ComSpec" to "relative.exe", "systemroot" to temporary.root.path), "Windows 11")
        assertNull(fallback.error)
        assertEquals(temporary.root.resolve("System32/cmd.exe").path, fallback.shell)
    }

    @Test fun `Windows defaults preserve identities and do not replace saved commands`() {
        val windows = CmdWidgetSettingsService.starterDefinitions("Windows 11")
        val unix = CmdWidgetSettingsService.starterDefinitions("Linux")
        assertEquals(unix.map { it.id }, windows.map { it.id })
        assertEquals(unix.map { it.name }, windows.map { it.name })
        assertEquals(unix.map { it.refreshIntervalSeconds }, windows.map { it.refreshIntervalSeconds })
        assertTrue(windows.all { it.validationError() == null && it.command.startsWith("powershell.exe ") &&
            it.command.contains("[Console]::OutputEncoding") })
        val settings = CmdWidgetSettingsService()
        settings.replaceDefinitions(windows)
        val restored = CmdWidgetSettingsService()
        restored.loadState(settings.state)
        assertEquals(windows, restored.effectiveDefinitions.value)
    }
}
