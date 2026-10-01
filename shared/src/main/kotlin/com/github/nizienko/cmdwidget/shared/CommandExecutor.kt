package com.github.nizienko.cmdwidget.shared

import com.intellij.openapi.components.Service
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.math.min

/** One application service, hence one four-execution budget per IDE process. */
@Service(Service.Level.APP)
class CommandExecutor(private val serviceScope: CoroutineScope) {
    private val slots = Semaphore(MAX_ACTIVE)

    suspend fun execute(command: String, context: ExecutionContext): CommandResult {
        // Ownership by both the caller (project/test) and the plugin's service scope.
        // Cancelling either cancels queued work and waits for bounded process cleanup.
        val request = serviceScope.async(Dispatchers.IO) {
            slots.withPermit { runProcess(command, context) }
        }
        try {
            return request.await()
        } finally {
            withContext(NonCancellable) { request.cancelAndJoin() }
        }
    }

    private suspend fun runProcess(command: String, context: ExecutionContext): CommandResult {
        val contextError = context.error ?: when {
            command.isBlank() -> "Command must not be blank"
            context.workingDirectory == null -> "No usable project root directory"
            !runCatching {
                val root = Path.of(context.workingDirectory)
                root.isAbsolute && Files.isDirectory(root) && Files.isReadable(root) && Files.isExecutable(root)
            }.getOrDefault(false) -> "No usable project root directory"
            else -> null
        }
        if (contextError != null) return CommandResult(
            context = context, completedAtEpochMillis = System.currentTimeMillis(), contextError = contextError,
        )

        val started = System.nanoTime()
        var process: Process? = null
        val descendants = linkedMapOf<Long, ProcessHandle>()
        val stdout = CapturedStream()
        val stderr = CapturedStream()
        var exitCode: Int? = null
        var startupError: String? = null
        var executionError: String? = null
        var cleanupError: String? = null
        var timedOut = false
        try {
            val finished = withTimeoutOrNull(TIMEOUT_MILLIS) {
                try {
                    // This coroutine is already on IO. Keep the handle before any
                    // suspension so cancellation cannot lose a newly started process.
                    process = ProcessBuilder(listOf(context.shell, "-lc", command))
                        .directory(Path.of(context.workingDirectory!!).toFile())
                        .start()
                } catch (failure: IOException) {
                    startupError = failure.message?.take(DIAGNOSTIC_LIMIT) ?: "Could not start shell"
                    return@withTimeoutOrNull true
                }
                val running = process!!
                rememberDescendants(running.toHandle(), descendants)
                currentCoroutineContext().ensureActive()
                running.outputStream.close()
                coroutineScope {
                    val out = async { stdout.drain(running.inputStream, running) }
                    val err = async { stderr.drain(running.errorStream, running) }
                    while (running.isAlive) {
                        rememberDescendants(running.toHandle(), descendants)
                        delay(POLL_MILLIS)
                    }
                    exitCode = running.exitValue()
                    out.await()
                    err.await()
                }
                executionError = stdout.error ?: stderr.error
                true
            }
            timedOut = finished == null
        } catch (failure: IOException) {
            executionError = failure.message?.take(DIAGNOSTIC_LIMIT) ?: "Command I/O failed"
        } finally {
            // Lifecycle cancellation propagates to callers; it is never turned into
            // an error result. Keep the slot until owned processes have been cleaned up.
            withContext(NonCancellable) {
                process?.let { running ->
                    cleanupError = terminateOwnedProcesses(running, descendants)
                    runCatching { running.inputStream.close() }
                    runCatching { running.errorStream.close() }
                    runCatching { running.outputStream.close() }
                }
            }
        }
        return CommandResult(
            context = context,
            stdout = stdout.text(), stderr = stderr.text(), exitCode = exitCode,
            timedOut = timedOut,
            durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
            completedAtEpochMillis = System.currentTimeMillis(),
            stdoutTruncated = stdout.truncated, stderrTruncated = stderr.truncated,
            startupError = startupError, executionError = executionError, cleanupError = cleanupError,
        )
    }

    private fun rememberDescendants(root: ProcessHandle, descendants: MutableMap<Long, ProcessHandle>) {
        root.descendants().use { stream ->
            stream.forEach { descendants[it.pid()] = it }
        }
    }

    private suspend fun terminateOwnedProcesses(
        process: Process,
        descendants: MutableMap<Long, ProcessHandle>,
    ): String? {
        val root = process.toHandle()
        rememberDescendants(root, descendants)
        // Kill descendants first while the shell is still able to reap them. Keep
        // previously observed handles when a parent exits during cancellation.
        descendants.values.toList().asReversed().forEach { if (it.isAlive) it.destroy() }
        val gracefulDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLEANUP_PHASE_MILLIS)
        while (System.nanoTime() < gracefulDeadline && descendants.values.any { it.isAlive }) {
            rememberDescendants(root, descendants)
            descendants.values.toList().asReversed().forEach { if (it.isAlive) it.destroy() }
            delay(POLL_MILLIS)
        }
        if (root.isAlive) root.destroy()
        if (root.isAlive || descendants.values.any { it.isAlive }) delay(POLL_MILLIS)

        // Refresh from still-living parents before forcing termination. This also
        // captures a child created while a parent was handling graceful termination.
        rememberDescendants(root, descendants)
        descendants.values.toList().forEach { if (it.isAlive) rememberDescendants(it, descendants) }
        descendants.values.toList().asReversed().forEach { if (it.isAlive) it.destroyForcibly() }
        val forceDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLEANUP_PHASE_MILLIS)
        // Keep the root alive briefly to reap forcibly terminated children. A single
        // poll is insufficient on a busy host and can leave zombies on Linux.
        val reapDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200)
        while (root.isAlive && descendants.values.any { it.isAlive } && System.nanoTime() < reapDeadline) {
            delay(POLL_MILLIS)
        }
        if (root.isAlive) root.destroyForcibly()
        while (System.nanoTime() < forceDeadline && (root.isAlive || descendants.values.any { it.isAlive })) {
            delay(POLL_MILLIS)
        }
        return if (root.isAlive || descendants.values.any { it.isAlive })
            "Some owned processes did not exit within the cleanup deadline" else null
    }

    private class CapturedStream {
        private val bytes = ByteArrayOutputStream(OUTPUT_LIMIT_BYTES)
        var truncated = false
            private set
        var error: String? = null
            private set

        suspend fun drain(stream: InputStream, process: Process) {
            val buffer = ByteArray(8192)
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val available = stream.available()
                    if (available == 0) {
                        if (!process.isAlive) return
                        delay(POLL_MILLIS)
                        continue
                    }
                    // Never enter a blocking read of an empty pipe. In particular,
                    // cancellation must not wait for an orphan to close a descriptor.
                    val count = stream.read(buffer, 0, min(available, buffer.size))
                    if (count < 0) return
                    val retained = min(count, OUTPUT_LIMIT_BYTES - bytes.size())
                    bytes.write(buffer, 0, retained)
                    if (retained < count) truncated = true
                }
            } catch (failure: IOException) {
                error = failure.message?.take(DIAGNOSTIC_LIMIT) ?: "Could not read command output"
            }
        }

        fun text(): String = bytes.toString(Charsets.UTF_8)
    }

    companion object {
        const val MAX_ACTIVE = 4
        const val TIMEOUT_MILLIS = 10_000L
        const val OUTPUT_LIMIT_BYTES = 64 * 1024
        private const val DIAGNOSTIC_LIMIT = 4096
        private const val POLL_MILLIS = 20L
        private const val CLEANUP_PHASE_MILLIS = 500L
    }
}
