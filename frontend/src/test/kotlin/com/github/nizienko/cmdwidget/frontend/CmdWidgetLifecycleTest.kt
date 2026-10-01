package com.github.nizienko.cmdwidget.frontend

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.impl.status.IdeStatusBarImpl
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.github.nizienko.cmdwidget.shared.CmdWidgetConfiguration
import com.github.nizienko.cmdwidget.shared.CommandResult
import com.github.nizienko.cmdwidget.shared.ExecutionContext
import com.github.nizienko.cmdwidget.shared.ExecutionTarget
import com.github.nizienko.cmdwidget.shared.ProjectWidgetState
import com.github.nizienko.cmdwidget.shared.WidgetState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow

/** Exercises the real target-platform status bar, rather than a mock of its lifecycle. */
class CmdWidgetLifecycleTest : BasePlatformTestCase() {
    private val scopes = mutableListOf<CoroutineScope>()

    override fun tearDown() {
        try {
            scopes.forEach { it.cancel() }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        } finally {
            super.tearDown()
        }
    }

    private fun bar(): IdeStatusBarImpl {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += scope
        return IdeStatusBarImpl(scope, { project }, false, MutableStateFlow(null))
    }

    private fun install(bar: IdeStatusBarImpl): CmdWidgetHost {
        val host = CmdWidgetHost()
        bar.addWidget(host, testRootDisposable)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return host
    }

    fun testIndependentUpdateRemovalAndRestoration() {
        val bar = bar()
        val host = install(bar)
        val git = bar.getWidget("CmdWidget.prototype-git")!!
        val disk = bar.getWidget("CmdWidget.prototype-disk")!!
        assertEquals("Git: main", (git.getPresentation() as StatusBarWidget.TextPresentation).getText())
        assertEquals("Disk: 126G", (disk.getPresentation() as StatusBarWidget.TextPresentation).getText())

        host.cyclePrototype()
        assertSame(git, bar.getWidget(git.ID()))
        assertSame(disk, bar.getWidget(disk.ID()))
        assertEquals("Disk: updated", (disk.getPresentation() as StatusBarWidget.TextPresentation).getText())

        host.cyclePrototype()
        assertSame(git, bar.getWidget(git.ID()))
        assertNull(bar.getWidget(disk.ID()))
        assertTrue(Disposer.isDisposed(disk))

        host.cyclePrototype()
        assertSame(git, bar.getWidget(git.ID()))
        assertNotSame(disk, bar.getWidget(disk.ID()))
        assertEquals(3, bar.allWidgets!!.size) // invisible lifecycle host plus two independent values
    }

    fun testTwoWindowsKeepSeparateInstancesAndDisposeOnlyOwnedWidgets() {
        val firstBar = bar()
        val secondBar = bar()
        val first = install(firstBar)
        val second = install(secondBar)
        val secondGit = secondBar.getWidget("CmdWidget.prototype-git")!!
        assertNotSame(firstBar.getWidget(secondGit.ID()), secondGit)
        first.cyclePrototype()
        first.cyclePrototype()
        assertNotNull(secondBar.getWidget("CmdWidget.prototype-disk"))

        // Same factory disposal path is used on extension removal/plugin unload.
        CmdWidgetFactory().disposeWidget(first)
        assertNull(firstBar.getWidget("CmdWidget.prototype-git"))
        assertSame(secondGit, secondBar.getWidget(secondGit.ID()))
        assertFalse(Disposer.isDisposed(secondGit))

        Disposer.dispose(second)
        assertNull(secondBar.getWidget(secondGit.ID()))
        assertTrue(Disposer.isDisposed(secondGit))
    }

    fun testDisposalBeforeDeferredInstallAndReopenDoNotDuplicateWidgets() {
        val bar = bar()
        val pending = CmdWidgetHost()
        bar.addWidget(pending, testRootDisposable)
        Disposer.dispose(pending)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertNull(bar.getWidget("CmdWidget.prototype-git"))
        bar.removeWidget(CmdWidgetHost.ID)

        val reopened = install(bar)
        reopened.reconcile(CmdWidgetHost.PROTOTYPE)
        reopened.reconcile(CmdWidgetHost.PROTOTYPE)
        assertEquals(3, bar.allWidgets!!.size)
        Disposer.dispose(reopened)
        assertNull(bar.getWidget("CmdWidget.prototype-git"))
        assertNull(bar.getWidget("CmdWidget.prototype-disk"))
    }

    fun testParentDisposalReleasesAllWidgets() {
        val bar = bar()
        val windowLifetime = Disposer.newDisposable(testRootDisposable, "prototype project window")
        val host = CmdWidgetHost()
        bar.addWidget(host, windowLifetime)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val git = bar.getWidget("CmdWidget.prototype-git")!!
        val disk = bar.getWidget("CmdWidget.prototype-disk")!!

        Disposer.dispose(windowLifetime)
        assertNull(bar.getWidget(CmdWidgetHost.ID))
        assertNull(bar.getWidget(git.ID()))
        assertNull(bar.getWidget(disk.ID()))
        assertTrue(Disposer.isDisposed(git))
        assertTrue(Disposer.isDisposed(disk))
    }

    fun testQueuedCleanupCannotRemoveReplacementWidgets() {
        val bar = bar()
        val previous = install(bar)
        // Simulate off-EDT disposal followed by a replacement before queued cleanup runs.
        val disposal = Thread { Disposer.dispose(previous) }
        disposal.start()
        disposal.join(5_000)
        assertFalse("Disposal must not wait for EDT", disposal.isAlive)
        bar.removeWidget(CmdWidgetHost.ID)
        val replacement = CmdWidgetHost()
        bar.addWidget(replacement, testRootDisposable)
        replacement.reconcile(CmdWidgetHost.PROTOTYPE)
        val git = bar.getWidget("CmdWidget.prototype-git")!!

        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertSame(git, bar.getWidget(git.ID()))
        assertEquals(3, bar.allWidgets!!.size)
    }

    private fun state(version: Long, text: String, enabled: Boolean = true): ProjectWidgetState {
        val result = CommandResult(ExecutionContext("remote-host", "/repo", "/bin/sh", "Linux"),
            stdout = text, exitCode = 0, completedAtEpochMillis = 1_000)
        return ProjectWidgetState(version, listOf(WidgetState(
            CmdWidgetConfiguration("live", "Live", "printf value", enabled = enabled), 1,
            lastSuccessfulResult = result, latestResult = result,
        )))
    }

    fun testLiveStateRejectsOldVersionsSessionsAndResponsesAfterDisconnect() {
        val bar = bar()
        val host = install(bar)
        host.accept(BackendStateEvent.Snapshot(1, state(10, "current")))
        val widget = bar.getWidget("CmdWidget.live")!!
        fun text() = (widget.getPresentation() as StatusBarWidget.TextPresentation).getText()
        host.accept(BackendStateEvent.Snapshot(1, state(9, "obsolete")))
        assertEquals("Live: current", text())
        host.accept(BackendStateEvent.Disconnected(1))
        assertEquals("Live: current [stale]", text())
        host.accept(BackendStateEvent.Snapshot(1, state(11, "too late")))
        assertEquals("Live: current [stale]", text())
        host.accept(BackendStateEvent.Snapshot(2, state(1, "reconnected")))
        assertSame(widget, bar.getWidget(widget.ID()))
        assertEquals("Live: reconnected", text())
        host.accept(BackendStateEvent.Disconnected(1))
        host.accept(BackendStateEvent.Snapshot(1, state(99, "old connection")))
        assertEquals("Live: reconnected", text())
        host.accept(BackendStateEvent.Snapshot(2, state(2, "disabled", enabled = false)))
        assertNull(bar.getWidget(widget.ID()))
    }

    fun testFailureUpdatesTooltipRetainsWidgetAndMarksSuccessStale() {
        val bar = bar()
        val host = install(bar)
        val initial = state(1, "connected")
        host.accept(BackendStateEvent.Snapshot(1, initial))
        val widget = bar.getWidget("CmdWidget.live")!!
        val failed = initial.widgets.single().copy(latestResult = initial.widgets.single().latestResult!!.copy(
            exitCode = 7, stdout = "", stderr = "diagnostic", completedAtEpochMillis = 2_000,
        ))
        host.accept(BackendStateEvent.Snapshot(1, ProjectWidgetState(2, listOf(failed))))
        assertSame(widget, bar.getWidget(widget.ID()))
        val presentation = widget.getPresentation() as StatusBarWidget.TextPresentation
        assertEquals("Live: connected [stale]", presentation.getText())
        assertTrue(presentation.getTooltipText()!!.contains("diagnostic"))
        assertTrue(presentation.getTooltipText()!!.contains("remote-host"))
        Disposer.dispose(host)
        host.accept(BackendStateEvent.Snapshot(2, state(3, "after disposal")))
        assertNull(bar.getWidget(widget.ID()))
    }

    fun testBackendDisconnectMarksOnlyBackendWidgetsStaleAndLocalUpdatesContinue() {
        val bar = bar()
        val host = install(bar)
        val backend = state(1, "remote").widgets.single()
        val local = backend.copy(configuration = backend.configuration.copy(
            id = "local", name = "Local", executionTarget = ExecutionTarget.FRONTEND,
        ))
        host.accept(BackendStateEvent.Snapshot(1, ProjectWidgetState(1, listOf(backend, local)), connected = false))
        fun text(id: String) = (bar.getWidget("CmdWidget.$id")!!.getPresentation() as StatusBarWidget.TextPresentation).getText()
        assertEquals("Live: remote [stale]", text("live"))
        assertEquals("Local: remote", text("local"))
        val nextResult = local.latestResult!!.copy(stdout = "updated")
        host.accept(BackendStateEvent.Snapshot(1, ProjectWidgetState(2, listOf(backend,
            local.copy(latestResult = nextResult, lastSuccessfulResult = nextResult))), connected = false))
        assertEquals("Local: updated", text("local"))
        assertEquals("Live: remote [stale]", text("live"))
    }

    fun testDisposalCancelsSubscriptionAndQueuedDeliveryCannotRecreateWidgets() {
        val bar = bar()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val host = CmdWidgetHost(scope) { accept ->
            accept(BackendStateEvent.Snapshot(1, state(1, "live")))
            started.complete(Unit)
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        }
        bar.addWidget(host, testRootDisposable)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        runBlocking { withTimeout(3_000) { started.await() } }
        Disposer.dispose(host)
        runBlocking { withTimeout(3_000) { stopped.await() } }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertNull(bar.getWidget("CmdWidget.live"))
    }
}
