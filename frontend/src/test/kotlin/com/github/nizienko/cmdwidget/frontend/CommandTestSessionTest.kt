package com.github.nizienko.cmdwidget.frontend

import com.github.nizienko.cmdwidget.shared.CommandResult
import com.github.nizienko.cmdwidget.shared.ExecutionContext
import com.github.nizienko.cmdwidget.shared.ExecutionTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

class CommandTestSessionTest {
    private val context = ExecutionContext("backend", "/repo", "/bin/sh", "Linux")

    @Test fun frontendContextAndTestUseLocalRunnerWithoutContactingBackend() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val local = context.copy(host = "frontend", workingDirectory = "/local")
        val backend = object : CommandTestBackend {
            override suspend fun context(): ExecutionContext = error("Backend must not be contacted")
            override suspend fun test(command: String): CommandResult = error("Backend must not be contacted")
        }
        val frontend = object : CommandTestBackend {
            override suspend fun context() = local
            override suspend fun test(command: String) =
                CommandResult(local, stdout = command, exitCode = 0, completedAtEpochMillis = 1)
        }
        val session = CommandTestSession(scope, backend, frontend) { it() }
        try {
            val received = CompletableDeferred<ExecutionContext>()
            session.loadContext(ExecutionTarget.FRONTEND) { value, error ->
                assertNull(error)
                received.complete(value!!)
            }
            assertEquals(local, withTimeout(5_000) { received.await() })
            val result = CompletableDeferred<CommandResult>()
            session.test("local command", ExecutionTarget.FRONTEND) { value, error ->
                assertNull(error)
                result.complete(value!!)
            }
            assertEquals("local command", withTimeout(5_000) { result.await() }.stdout)
        } finally { session.dispose(); scope.cancel() }
    }

    @Test fun contextLookupNeverExecutesAndTestRunsOnlyTheExplicitUnsavedCommand() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val delivered = CompletableDeferred<Unit>()
        val callbacks = ConcurrentLinkedQueue<() -> Unit>()
        val commands = mutableListOf<String>()
        val backend = object : CommandTestBackend {
            override suspend fun context() = context
            override suspend fun test(command: String): CommandResult {
                commands += command
                return CommandResult(context, stdout = "unsaved", exitCode = 0, completedAtEpochMillis = 1)
            }
        }
        val session = CommandTestSession(scope, backend) { callbacks.add(it); delivered.complete(Unit) }
        try {
            var received: ExecutionContext? = null
            session.loadContext { value, _ -> received = value }
            withTimeout(5_000) { delivered.await() }
            callbacks.remove().invoke()
            assertEquals(context, received)
            assertTrue(commands.isEmpty())
            val resultArrived = CompletableDeferred<Unit>()
            session.test("printf unsaved") { result, error ->
                assertNull(error)
                assertEquals("unsaved", result!!.stdout)
                resultArrived.complete(Unit)
            }
            withTimeout(5_000) {
                while (callbacks.isEmpty()) kotlinx.coroutines.delay(5)
                callbacks.remove().invoke()
                resultArrived.await()
            }
            assertEquals(listOf("printf unsaved"), commands)
        } finally { session.dispose(); scope.cancel() }
    }

    @Test fun editorAndProjectClosureCancelInFlightTests() = runBlocking {
        for (closeProject in listOf(false, true)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val backend = object : CommandTestBackend {
                override suspend fun context() = context
                override suspend fun test(command: String): CommandResult {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                }
            }
            val session = CommandTestSession(scope, backend) { it() }
            try {
                session.test("sleep 30") { _, _ -> fail("Cancelled test must not publish") }
                withTimeout(5_000) { started.await() }
                if (closeProject) scope.cancel() else session.dispose()
                withTimeout(5_000) { cancelled.await() }
            } finally { session.dispose(); scope.cancel() }
        }
    }

    @Test fun cancelledOrClosedEditorRejectsAlreadyQueuedResults() = runBlocking {
        for (closeEditor in listOf(false, true)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val queued = CompletableDeferred<() -> Unit>()
            val backend = object : CommandTestBackend {
                override suspend fun context() = context
                override suspend fun test(command: String) = CommandResult(context, completedAtEpochMillis = 1)
            }
            val session = CommandTestSession(scope, backend) { queued.complete(it) }
            try {
                session.test("pwd") { _, _ -> fail("Obsolete result must not publish") }
                val callback = withTimeout(5_000) { queued.await() }
                if (closeEditor) session.dispose() else session.cancelTest()
                callback()
            } finally { session.dispose(); scope.cancel() }
        }
    }
}
