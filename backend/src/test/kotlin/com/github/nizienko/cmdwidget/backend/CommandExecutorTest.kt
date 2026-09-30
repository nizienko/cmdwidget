package com.github.nizienko.cmdwidget.backend

import com.github.nizienko.cmdwidget.shared.ExecutionContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** Real process tests; run on both supported backend-host OSs. No IDE UI is required. */
class CommandExecutorTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var scope: CoroutineScope
    private lateinit var executor: CommandExecutor
    private lateinit var context: ExecutionContext

    @Before fun setUp() {
        val os = System.getProperty("os.name").lowercase()
        assumeTrue(os.contains("mac") || os.contains("linux"))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        executor = CommandExecutor(scope)
        context = ExecutionContextResolver.resolve(temporary.root.absolutePath, mapOf("SHELL" to "/bin/sh"))
    }

    @After fun tearDown() = runBlocking {
        if (::scope.isInitialized) scope.coroutineContext[Job]!!.cancelAndJoin()
    }

    @Test(timeout = 15_000) fun `working directory stdin and shell arguments are correct`() = runBlocking {
        val result = executor.execute("pwd; cat; printf '%s' 'space and \"quotes\" and \$HOME'", context)
        assertTrue(result.toString(), result.successful)
        assertEquals(temporary.root.canonicalPath + "\nspace and \"quotes\" and \$HOME", result.stdout)
        assertEquals("", result.stderr)
        assertNotNull(result.exitCode)
        assertTrue(result.durationMillis >= 0)
        assertTrue(result.completedAtEpochMillis <= System.currentTimeMillis())
    }

    @Test(timeout = 15_000) fun `nonzero exit and stderr are reported without losing stdout`() = runBlocking {
        val result = executor.execute("printf value; printf diagnostic >&2; exit 7", context)
        assertEquals(7, result.exitCode)
        assertEquals("value", result.stdout)
        assertEquals("diagnostic", result.stderr)
        assertFalse(result.successful)
        assertFalse(result.timedOut)
    }

    @Test(timeout = 15_000) fun `empty output and stderr alone are successful`() = runBlocking {
        val empty = executor.execute(":", context)
        assertTrue(empty.toString(), empty.successful)
        assertEquals("", empty.stdout)
        val warning = executor.execute("printf warning >&2", context)
        assertTrue(warning.toString(), warning.successful)
        assertEquals("", warning.stdout)
        assertEquals("warning", warning.stderr)
    }

    @Test(timeout = 15_000) fun `startup errors do not look like empty success`() = runBlocking {
        val result = executor.execute("printf never", context.copy(shell = temporary.root.resolve("missing-shell").path))
        assertNotNull(result.startupError)
        assertNull(result.exitCode)
        assertFalse(result.timedOut)
        assertFalse(result.successful)
    }

    @Test(timeout = 15_000) fun `invalid roots and blank commands never start a process`() = runBlocking {
        val marker = temporary.root.resolve("should-not-exist")
        val result = executor.execute("touch '${marker.path}'", context.copy(workingDirectory = null))
        assertNotNull(result.contextError)
        assertFalse(marker.exists())
        val deletedRoot = context.copy(workingDirectory = temporary.root.resolve("missing-root").path)
        assertNotNull(executor.execute("pwd", deletedRoot).contextError)
        assertNotNull(executor.execute("  ", context).contextError)
    }

    @Test(timeout = 15_000) fun `large concurrent streams drain past the capture limit`() = runBlocking {
        val result = executor.execute(
            "awk 'BEGIN { for (i=0;i<262144;i++) printf \"x\" }' & " +
                "awk 'BEGIN { for (i=0;i<262144;i++) printf \"y\" }' >&2 & wait",
            context,
        )
        assertTrue(result.toString().take(500), result.successful)
        assertEquals(CommandExecutor.OUTPUT_LIMIT_BYTES, result.stdout.toByteArray().size)
        assertEquals(CommandExecutor.OUTPUT_LIMIT_BYTES, result.stderr.toByteArray().size)
        assertTrue(result.stdoutTruncated)
        assertTrue(result.stderrTruncated)
        assertTrue(result.stdout.all { it == 'x' })
        assertTrue(result.stderr.all { it == 'y' })
    }

    @Test(timeout = 20_000) fun `timeout includes login shell startup and cleans descendants`() = runBlocking {
        val pidFile = temporary.root.toPath().resolve("startup-child.pid")
        val shell = temporary.root.toPath().resolve("slow-login-shell")
        Files.writeString(shell, "#!/bin/sh\nsleep 30 &\nprintf '%s' \"\$!\" > '${pidFile}'\nwait\n")
        Files.setPosixFilePermissions(shell, PosixFilePermissions.fromString("rwx------"))
        val result = executor.execute("printf should-not-run", context.copy(shell = shell.toString()))
        assertTrue(result.toString(), result.timedOut)
        assertFalse(result.successful)
        assertTrue(result.durationMillis >= CommandExecutor.TIMEOUT_MILLIS - 100)
        assertTrue("Cleanup must be bounded", result.durationMillis < CommandExecutor.TIMEOUT_MILLIS + 3_000)
        assertEquals("", result.stdout)
        assertDead(readPid(pidFile))
        assertNull(result.cleanupError)
    }

    @Test(timeout = 15_000) fun `cancelling a pipeline kills shell and child processes`() = runBlocking {
        val shellPid = temporary.root.toPath().resolve("shell.pid")
        val childPid = temporary.root.toPath().resolve("child.pid")
        val request = async {
            executor.execute(
                "printf '%s' \"\$\$\" > '$shellPid'; " +
                    "(printf '%s' \"\$\$\" > '$childPid'; sleep 30) | cat & wait",
                context,
            )
        }
        awaitFile(childPid)
        val root = ProcessHandle.of(readPid(shellPid)).orElseThrow()
        val owned = withTimeout(3_000) {
            var children = root.descendants().use { it.toList() }
            while (children.size < 2) {
                delay(20)
                children = root.descendants().use { it.toList() }
            }
            children
        }
        delay(100) // allow ownership sampling before deliberately cancelling
        withTimeout(3_000) { request.cancelAndJoin() }
        assertTrue(request.isCancelled)
        assertDead(root.pid())
        owned.forEach { assertDead(it.pid()) }
    }

    @Test(timeout = 20_000) fun `timeout cleans a pipeline and returns partial output`() = runBlocking {
        val pidFile = temporary.root.toPath().resolve("timeout-shell.pid")
        val request = async {
            executor.execute("printf '%s' \"\$\$\" > '$pidFile'; printf partial; sleep 30 | cat", context)
        }
        awaitFile(pidFile)
        val root = ProcessHandle.of(readPid(pidFile)).orElseThrow()
        val children = withTimeout(3_000) {
            var handles = root.descendants().use { it.toList() }
            while (handles.size < 2) {
                delay(20)
                handles = root.descendants().use { it.toList() }
            }
            handles
        }
        val result = request.await()
        assertTrue(result.timedOut)
        assertEquals("partial", result.stdout)
        assertNull(result.cleanupError)
        assertDead(root.pid())
        children.forEach { assertDead(it.pid()) }
    }

    @Test(timeout = 15_000) fun `cleanup forcibly terminates processes ignoring graceful termination`() = runBlocking {
        val pidFile = temporary.root.toPath().resolve("stubborn-shell.pid")
        val childFile = temporary.root.toPath().resolve("stubborn-child.pid")
        val request = async {
            executor.execute(
                "trap '' TERM; printf '%s' \"\$\$\" > '$pidFile'; " +
                    "sleep 30 & printf '%s' \"\$!\" > '$childFile'; wait",
                context,
            )
        }
        awaitFile(childFile)
        delay(100)
        withTimeout(3_000) { request.cancelAndJoin() }
        assertDead(readPid(pidFile))
        assertDead(readPid(childFile))
    }

    @Test(timeout = 15_000) fun `a fifth request queues and cancellation prevents startup`() = runBlocking {
        val active = (1..4).map { index ->
            async { executor.execute("printf ready > 'active-$index'; sleep 30", context) }
        }
        (1..4).forEach { awaitFile(temporary.root.toPath().resolve("active-$it")) }
        val queuedMarker = temporary.root.toPath().resolve("queued-started")
        val queued = async { executor.execute("printf unexpected > '$queuedMarker'", context) }
        delay(150)
        assertFalse(Files.exists(queuedMarker))
        withTimeout(3_000) { queued.cancelAndJoin() }
        active.forEach { withTimeout(3_000) { it.cancelAndJoin() } }
        assertFalse(Files.exists(queuedMarker))
        assertTrue(executor.execute("printf next", context).successful)
    }

    @Test(timeout = 15_000) fun `service cancellation stops running and queued requests`() = runBlocking {
        val active = (1..4).map { index ->
            async { executor.execute("printf '%s' \"\$\$\" > 'scope-$index'; sleep 30", context) }
        }
        val pids = (1..4).map { index ->
            val file = temporary.root.toPath().resolve("scope-$index")
            awaitFile(file)
            readPid(file)
        }
        val queuedMarker = temporary.root.toPath().resolve("scope-queued")
        val queued = async { executor.execute("printf unexpected > '$queuedMarker'", context) }
        delay(100)
        withTimeout(3_000) { scope.coroutineContext[Job]!!.cancelAndJoin() }
        (active + queued).forEach { it.join(); assertTrue(it.isCancelled) }
        pids.forEach { assertDead(it) }
        assertFalse(Files.exists(queuedMarker))
    }

    @Test fun `context validates OS directory and executable absolute shell`() {
        assertEquals("/bin/sh", ExecutionContextResolver.resolve(context.workingDirectory, mapOf("SHELL" to "sh")).shell)
        assertEquals("/bin/sh", ExecutionContextResolver.resolve(context.workingDirectory, mapOf("SHELL" to "/missing/shell")).shell)
        assertEquals("/bin/sh", ExecutionContextResolver.resolve(context.workingDirectory, mapOf("SHELL" to temporary.root.path)).shell)
        assertNull(context.error)
        assertNotNull(ExecutionContextResolver.resolve(null).error)
        assertNotNull(ExecutionContextResolver.resolve("relative-root").error)
        assertNotNull(ExecutionContextResolver.resolve(temporary.root.path, operatingSystem = "Windows 11").error)
    }

    private suspend fun awaitFile(path: Path) = withTimeout(4_000) {
        while (!Files.exists(path) || Files.size(path) == 0L) delay(20)
    }

    private fun readPid(path: Path): Long = Files.readString(path).trim().toLong()

    private fun assertDead(pid: Long) {
        assertFalse("Process $pid survived cleanup", ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
    }
}
