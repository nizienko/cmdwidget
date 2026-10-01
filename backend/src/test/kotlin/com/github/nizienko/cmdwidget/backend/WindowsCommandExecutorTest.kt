package com.github.nizienko.cmdwidget.backend

import com.github.nizienko.cmdwidget.shared.CommandExecutor
import com.github.nizienko.cmdwidget.shared.CmdWidgetSettingsService
import com.github.nizienko.cmdwidget.shared.ExecutionContext
import com.github.nizienko.cmdwidget.shared.ExecutionContextResolver
import com.github.nizienko.cmdwidget.shared.HostShell
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/** Native Windows coverage; deliberately skipped on Unix hosts. */
class WindowsCommandExecutorTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var scope: CoroutineScope
    private lateinit var executor: CommandExecutor
    private lateinit var context: ExecutionContext

    @Before fun setUp() {
        assumeTrue(HostShell.forOperatingSystem(System.getProperty("os.name")) == HostShell.WINDOWS)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        executor = CommandExecutor(scope)
        context = ExecutionContextResolver.resolve(temporary.newFolder("project with spaces Привет").path)
        assertNull(context.error)
    }

    @After fun tearDown() = runBlocking {
        if (::scope.isInitialized) scope.coroutineContext[Job]!!.cancelAndJoin()
    }

    @Test(timeout = 15_000) fun `cmd handles unicode builtins pipes stderr and exit codes`() = runBlocking {
        val result = executor.execute("echo Привет мир| findstr /c:\"Привет\" & echo diagnostic 1>&2 & exit /b 7", context)
        assertEquals(7, result.exitCode)
        assertEquals("Привет мир", result.stdout.trim())
        assertEquals("diagnostic", result.stderr.trim())
        assertFalse(result.successful)
        assertNull(result.startupError)
    }

    @Test(timeout = 15_000) fun `quoted executable and working directory with spaces execute correctly`() = runBlocking {
        val executable = "${System.getProperty("java.home")}\\bin\\java.exe"
        val result = executor.execute("\"$executable\" -version & cd", context)
        assertTrue(result.toString(), result.successful)
        assertEquals(context.workingDirectory, result.stdout.trim())
        assertTrue(result.stderr.contains("version"))
    }

    @Test(timeout = 15_000) fun `Windows starter commands produce valid widget values`() = runBlocking {
        val outputs = CmdWidgetSettingsService.starterDefinitions("Windows 11").associate { definition ->
            val result = executor.execute(definition.command, context)
            assertTrue("${definition.name}: $result", result.successful)
            assertEquals("", result.stderr)
            definition.name to result.stdout.trim()
        }
        assertTrue(outputs.getValue("Time").matches(Regex("\\d{2}:\\d{2}")))
        assertEquals("project with spaces Привет", outputs.getValue("Project"))
        assertTrue(outputs.getValue("Disk usage").endsWith("%"))
        assertTrue(outputs.getValue("Disk usage").removeSuffix("%").toInt() in 0..100)
    }

    @Test(timeout = 20_000) fun `Windows timeout returns partial output and cleans descendants`() = runBlocking {
        val result = executor.execute("echo partial & ping -n 30 127.0.0.1 >nul", context)
        assertTrue(result.toString(), result.timedOut)
        assertEquals("partial", result.stdout.trim())
        assertNull(result.cleanupError)
        assertTrue(result.durationMillis < CommandExecutor.TIMEOUT_MILLIS + 3_000)
    }

    @Test(timeout = 15_000) fun `Windows cancellation cleans a running child`() = runBlocking {
        val marker = java.nio.file.Path.of(context.workingDirectory!!).resolve("child.pid")
        val request = async {
            executor.execute("powershell.exe -NoProfile -NonInteractive -Command \"" +
                "[IO.File]::WriteAllText('child.pid', [string]\$PID); Start-Sleep -Seconds 30\"", context)
        }
        val pid = withTimeout(5_000) {
            while (!Files.exists(marker) || Files.size(marker) == 0L) delay(20)
            Files.readString(marker).trim().toLong()
        }
        delay(100)
        withTimeout(3_000) { request.cancelAndJoin() }
        assertTrue(request.isCancelled)
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
    }
}
