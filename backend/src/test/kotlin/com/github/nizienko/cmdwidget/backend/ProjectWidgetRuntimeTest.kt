package com.github.nizienko.cmdwidget.backend

import com.github.nizienko.cmdwidget.shared.CommandExecutor
import com.github.nizienko.cmdwidget.shared.ExecutionContextResolver
import com.github.nizienko.cmdwidget.shared.ExecutionTarget
import com.github.nizienko.cmdwidget.shared.ProjectWidgetRuntime

import com.github.nizienko.cmdwidget.shared.CmdWidgetConfiguration
import com.github.nizienko.cmdwidget.shared.CommandResult
import com.github.nizienko.cmdwidget.shared.ExecutionContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class ProjectWidgetRuntimeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val context = ExecutionContext("test-host", "/project", "/bin/sh", "Linux")
    private fun definition(id: String = "git", interval: Int = 1) =
        CmdWidgetConfiguration(id, id, "branch", interval)
    private fun result(text: String, success: Boolean = true) = CommandResult(
        context, stdout = text, exitCode = if (success) 0 else 1, completedAtEpochMillis = System.currentTimeMillis(),
    )

    @After fun tearDown() = runBlocking { owner.coroutineContext[Job]!!.cancelAndJoin() }

    @Test(timeout = 8_000) fun `changing execution target cancels old work and clears previous host results`() = runBlocking {
        val started = Channel<Unit>(Channel.UNLIMITED)
        val stopped = CompletableDeferred<Unit>()
        val runtime = ProjectWidgetRuntime(owner, { context }) { _, _ ->
            started.send(Unit)
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        }
        val initial = definition()
        runtime.reconcile(listOf(initial))
        withTimeout(2_000) { started.receive() }
        val revision = runtime.state.value.widgets.single().revision
        runtime.reconcile(listOf(initial.copy(executionTarget = ExecutionTarget.FRONTEND)))
        assertTrue(stopped.isCompleted)
        assertTrue(runtime.state.value.widgets.single().revision > revision)
        assertNull(runtime.state.value.widgets.single().latestResult)
        withTimeout(2_000) { started.receive() }
        runtime.close()
    }

    @Test(timeout = 8_000) fun `directory changes restart execution and clear results from previous directory`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val runtime = ProjectWidgetRuntime(owner, { directory -> context.copy(workingDirectory = directory) }) { _, ctx ->
            if (ctx.workingDirectory == "/second") {
                started.complete(Unit)
                release.await()
            }
            CommandResult(ctx, stdout = ctx.workingDirectory!!, exitCode = 0, completedAtEpochMillis = 1)
        }
        val initial = definition(interval = 60).copy(workingDirectory = "/first")
        runtime.reconcile(listOf(initial))
        val first = withTimeout(2_000) { runtime.state.first { it.widgets.single().latestResult != null } }.widgets.single()
        assertEquals("/first", first.latestResult!!.stdout)
        runtime.reconcile(listOf(initial.copy(workingDirectory = "/second")))
        withTimeout(2_000) { started.await() }
        val changed = runtime.state.value.widgets.single()
        assertTrue(changed.revision > first.revision)
        assertNull(changed.latestResult)
        assertNull(changed.lastSuccessfulResult)
        release.complete(Unit)
        val second = withTimeout(2_000) { runtime.state.first { it.widgets.single().latestResult != null } }
        assertEquals("/second", second.widgets.single().latestResult!!.stdout)
        runtime.close()
    }

    @Test(timeout = 8_000) fun `slow attempts do not overlap and intervals begin after completion`() = runBlocking {
        val starts = Channel<Long>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val runtime = ProjectWidgetRuntime(owner, { context }) { _, _ ->
            starts.send(System.nanoTime())
            if (calls.incrementAndGet() == 1) release.await()
            result("main")
        }
        runtime.reconcile(listOf(definition()))
        withTimeout(2_000) { starts.receive() }
        delay(1_200)
        assertEquals(1, calls.get())
        assertTrue(starts.tryReceive().isFailure)
        val completed = System.nanoTime()
        release.complete(Unit)
        val next = withTimeout(2_000) { starts.receive() }
        assertTrue("Delay must follow completion", next - completed >= 950_000_000)
        runtime.close()
    }

    @Test(timeout = 8_000) fun `rename and reorder keep attempts and revision while other widgets refresh`() = runBlocking {
        val slowStarted = CompletableDeferred<Unit>()
        val slowCalls = AtomicInteger()
        val fastCalls = AtomicInteger()
        val runtime = ProjectWidgetRuntime(owner, { context }) { command, _ ->
            if (command == "slow") {
                slowCalls.incrementAndGet()
                slowStarted.complete(Unit)
                awaitCancellation()
            } else result("value-${fastCalls.incrementAndGet()}")
        }
        val slow = definition("slow").copy(command = "slow")
        val fast = definition("fast")
        runtime.reconcile(listOf(slow, fast))
        withTimeout(2_000) { slowStarted.await() }
        val revision = runtime.state.value.widgets.first().revision
        runtime.reconcile(listOf(fast, slow.copy(name = "renamed")))
        withTimeout(3_000) { runtime.state.first { it.widgets.first().latestResult?.stdout == "value-2" } }
        assertEquals(listOf("fast", "slow"), runtime.state.value.widgets.map { it.configuration.id })
        assertEquals(revision, runtime.state.value.widgets.last().revision)
        assertEquals("renamed", runtime.state.value.widgets.last().configuration.name)
        assertEquals(1, slowCalls.get())
        runtime.close()
    }

    @Test(timeout = 8_000) fun `command edit awaits cleanup and discards obsolete success`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        val runtime = ProjectWidgetRuntime(owner, { context }) { command, _ ->
            if (command == "old") {
                started.complete(Unit)
                // A deliberately uncooperative executor returns a stale result after cancellation.
                try { awaitCancellation() } catch (_: CancellationException) { /* simulate late return */ }
                withContext(NonCancellable) { delay(100) }
                cleaned.complete(Unit)
                result("obsolete")
            } else {
                assertTrue(cleaned.isCompleted)
                result("current")
            }
        }
        runtime.reconcile(listOf(definition().copy(command = "old")))
        withTimeout(2_000) { started.await() }
        val previousRevision = runtime.state.value.widgets.single().revision
        runtime.reconcile(listOf(definition().copy(command = "new")))
        withTimeout(2_000) { runtime.state.first { it.widgets.single().latestResult?.stdout == "current" } }
        assertTrue(runtime.state.value.widgets.single().revision > previousRevision)
        assertEquals("current", runtime.state.value.widgets.single().lastSuccessfulResult?.stdout)
        runtime.close()
    }

    @Test(timeout = 8_000) fun `disable removal and project closure cancel owned attempts`() = runBlocking {
        val starts = Channel<String>(Channel.UNLIMITED)
        val stops = Channel<String>(Channel.UNLIMITED)
        val runtime = ProjectWidgetRuntime(owner, { context }) { command, _ ->
            starts.send(command)
            try { awaitCancellation() } finally { stops.trySend(command) }
        }
        val first = definition("first").copy(command = "first")
        val second = definition("second").copy(command = "second")
        runtime.reconcile(listOf(first, second))
        withTimeout(2_000) { repeat(2) { starts.receive() } }
        runtime.reconcile(listOf(first.copy(enabled = false), second))
        assertEquals("first", withTimeout(2_000) { stops.receive() })
        assertTrue(stops.tryReceive().isFailure)
        runtime.reconcile(listOf(first.copy(enabled = false)))
        assertEquals("second", withTimeout(2_000) { stops.receive() })
        runtime.reconcile(listOf(first))
        assertEquals("first", withTimeout(2_000) { starts.receive() })
        owner.coroutineContext[Job]!!.cancelAndJoin()
        assertEquals("first", withTimeout(2_000) { stops.receive() })
        assertTrue(starts.tryReceive().isFailure)
    }

    @Test(timeout = 8_000) fun `projects keep separate results and failures preserve last success`() = runBlocking {
        val calls = AtomicInteger()
        val first = ProjectWidgetRuntime(owner, { context.copy(workingDirectory = "/first") }) { _, ctx ->
            result(ctx.workingDirectory!!, calls.incrementAndGet() == 1).copy(context = ctx)
        }
        val second = ProjectWidgetRuntime(owner, { context.copy(workingDirectory = "/second") }) { _, ctx ->
            result(ctx.workingDirectory!!).copy(context = ctx)
        }
        first.reconcile(listOf(definition()))
        second.reconcile(listOf(definition()))
        withTimeout(3_000) { first.state.first { it.widgets.single().latestResult?.successful == false } }
        withTimeout(2_000) { second.state.first { it.widgets.single().latestResult != null } }
        assertEquals("/first", first.state.value.widgets.single().lastSuccessfulResult?.stdout)
        assertFalse(first.state.value.widgets.single().latestResult!!.successful)
        assertEquals("/second", second.state.value.widgets.single().lastSuccessfulResult?.stdout)
        first.close()
        second.close()
    }

    @Test(timeout = 8_000) fun `invalid and duplicate definitions leave running configuration intact`() = runBlocking {
        val calls = AtomicInteger()
        val runtime = ProjectWidgetRuntime(owner, { context }) { _, _ -> calls.incrementAndGet(); result("ok") }
        val valid = definition()
        runtime.reconcile(listOf(valid))
        withTimeout(2_000) { runtime.state.first { it.widgets.single().latestResult != null } }
        val version = runtime.state.value.version
        val invalid = listOf(
            valid.copy(id = ""), valid.copy(name = " "), valid.copy(name = "x".repeat(41)),
            valid.copy(command = ""), valid.copy(refreshIntervalSeconds = 0),
        ).map { listOf(it) } + listOf(listOf(valid, valid))
        for (definitions in invalid) {
            try { runtime.reconcile(definitions); fail("Expected validation failure") }
            catch (_: IllegalArgumentException) { /* expected */ }
        }
        assertEquals(version, runtime.state.value.version)
        assertEquals(valid, runtime.state.value.widgets.single().configuration)
        assertEquals(1, calls.get())
        runtime.close()
    }

    @Test(timeout = 8_000) fun `interval edit restarts immediately and repeated definitions do not restart`() = runBlocking {
        val starts = Channel<Unit>(Channel.UNLIMITED)
        val calls = AtomicInteger()
        val runtime = ProjectWidgetRuntime(owner, { context }) { _, _ ->
            starts.send(Unit)
            result("value-${calls.incrementAndGet()}")
        }
        val initial = definition(interval = 60)
        runtime.reconcile(listOf(initial))
        withTimeout(2_000) { runtime.state.first { it.widgets.single().latestResult != null } }
        starts.receive()
        repeat(3) { runtime.reconcile(listOf(initial)) }
        assertEquals(1, calls.get())
        runtime.reconcile(listOf(initial.copy(refreshIntervalSeconds = 30)))
        withTimeout(2_000) { starts.receive() }
        withTimeout(2_000) { runtime.state.first { it.widgets.single().latestResult?.stdout == "value-2" } }
        assertEquals(2, calls.get())
        runtime.close()
    }

    @Test(timeout = 12_000) fun `runtime closure cleans real processes and cancels fifth queued widget`() = runBlocking {
        val executor = CommandExecutor(owner)
        val root = temporary.root.toPath()
        val backendContext = ExecutionContextResolver.resolve(root.toString(), mapOf("SHELL" to "/bin/sh"))
        val runtime = ProjectWidgetRuntime(owner, { backendContext }, executor::execute)
        val definitions = (1..5).map { index ->
            definition(index.toString()).copy(command = "printf '%s' \"\$\$\" > '$root/pid-$index'; sleep 30")
        }
        runtime.reconcile(definitions)
        val pids = withTimeout(4_000) {
            var started = emptyList<Long>()
            while (started.size < 4) {
                started = (1..5).mapNotNull { index ->
                    val path = root.resolve("pid-$index")
                    if (Files.exists(path)) Files.readString(path).trim().toLongOrNull() else null
                }
                delay(20)
            }
            started
        }
        delay(100)
        withTimeout(3_000) { runtime.close() }
        pids.forEach { pid -> assertFalse("Process $pid survived", ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) }
        assertEquals(4, (1..5).count { Files.exists(root.resolve("pid-$it")) })
    }
}
