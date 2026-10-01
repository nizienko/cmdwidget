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

    fun testReorderingUpdatesStatusBarPositionsAndKeepsPresentations() {
        val bar = bar()
        val host = install(bar)
        val original = CmdWidgetHost.PROTOTYPE
        fun assertOrder(definitions: List<Pair<String, String>>) {
            val components = definitions.map { (id, text) ->
                val widget = bar.getWidget("CmdWidget.$id") as CmdTextWidget
                assertEquals(text, widget.label)
                assertFalse(Disposer.isDisposed(widget))
                widget.component
            }
            val positions = components.map {
                (it.parent.layout as java.awt.GridBagLayout).getConstraints(it).gridx
            }
            assertTrue("Widgets must follow the configured order", positions.zipWithNext().all { (left, right) -> left < right })
            assertEquals(definitions.size + 1, bar.allWidgets!!.size)
        }

        assertOrder(original)
        host.reconcile(original.reversed())
        assertOrder(original.reversed())
        val reordered = original.map { (id, _) -> bar.getWidget("CmdWidget.$id")!! }
        host.reconcile(original.reversed())
        reordered.forEach { assertSame(it, bar.getWidget(it.ID())) }
        host.reconcile(original)
        assertOrder(original)

        Disposer.dispose(host)
        original.forEach { (id, _) -> assertNull(bar.getWidget("CmdWidget.$id")) }
    }

    fun testInsertingWidgetBetweenExistingWidgetsUpdatesPositions() {
        val bar = bar()
        val host = install(bar)
        val first = bar.getWidget("CmdWidget.prototype-git")!!
        val definitions = listOf(CmdWidgetHost.PROTOTYPE[0], "middle" to "Middle", CmdWidgetHost.PROTOTYPE[1])
        host.reconcile(definitions)
        assertSame(first, bar.getWidget(first.ID()))
        val positions = definitions.map { (id, text) ->
            val widget = bar.getWidget("CmdWidget.$id") as CmdTextWidget
            assertEquals(text, widget.label)
            val component = widget.component
            (component.parent.layout as java.awt.GridBagLayout).getConstraints(component).gridx
        }
        assertTrue("The new widget must appear between existing widgets", positions.zipWithNext().all { (left, right) -> left < right })
        assertEquals(4, bar.allWidgets!!.size)
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

    fun testPresentationAdaptsInPlaceBetweenTextAndPercentage() {
        val bar = bar()
        val host = install(bar)
        host.accept(BackendStateEvent.Snapshot(1, state(1, "42%")))
        val widget = bar.getWidget("CmdWidget.live") as CmdTextWidget
        val component = widget.component as AdaptiveWidgetPanel
        val percentageWidth = component.preferredSize.width
        assertEquals(42.0, component.percentage!!, 0.001)
        component.size = component.preferredSize
        fun paint(): java.awt.image.BufferedImage {
            val image = java.awt.image.BufferedImage(component.width, component.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try { component.paint(graphics) } finally { graphics.dispose() }
            return image
        }
        fun barPixel(image: java.awt.image.BufferedImage, offset: Int) =
            image.getRGB(component.insets.left + com.intellij.util.ui.JBUI.scale(offset), component.height / 2)
        val partial = paint()
        val filledColor = barPixel(partial, 18)
        val emptyColor = barPixel(partial, 54)
        assertFalse("42% must fill the first quarter but leave the third quarter empty", filledColor == emptyColor)
        host.accept(BackendStateEvent.Disconnected(1))
        assertEquals(42.0, component.percentage!!, 0.001)
        assertEquals("42% [stale]", component.text)
        host.accept(BackendStateEvent.Snapshot(2, state(2, "abc")))
        assertSame(widget, bar.getWidget(widget.ID()))
        assertSame(component, widget.component)
        assertNull(component.percentage)
        assertEquals("abc", component.text)
        assertTrue(component.preferredSize.width < percentageWidth)
        host.accept(BackendStateEvent.Snapshot(2, state(3, "100%")))
        assertSame(component, widget.component)
        assertEquals(100.0, component.percentage!!, 0.001)
        assertEquals("Live\n100%", widget.tooltip)
        component.size = component.preferredSize
        val full = paint()
        assertEquals(filledColor, barPixel(full, 18))
        assertEquals(filledColor, barPixel(full, 54))
        host.accept(BackendStateEvent.Snapshot(2, state(4, "0%")))
        val empty = paint()
        assertEquals(emptyColor, barPixel(empty, 18))
        assertEquals(emptyColor, barPixel(empty, 54))
        component.setSize(20, component.height)
        paint()
    }

    fun testLiveStateRejectsOldVersionsSessionsAndResponsesAfterDisconnect() {
        val bar = bar()
        val host = install(bar)
        host.accept(BackendStateEvent.Snapshot(1, state(10, "current")))
        val widget = bar.getWidget("CmdWidget.live")!!
        fun text() = (widget.getPresentation() as StatusBarWidget.TextPresentation).getText()
        host.accept(BackendStateEvent.Snapshot(1, state(9, "obsolete")))
        assertEquals("current", text())
        host.accept(BackendStateEvent.Disconnected(1))
        assertEquals("current [stale]", text())
        host.accept(BackendStateEvent.Snapshot(1, state(11, "too late")))
        assertEquals("current [stale]", text())
        host.accept(BackendStateEvent.Snapshot(2, state(1, "reconnected")))
        assertSame(widget, bar.getWidget(widget.ID()))
        assertEquals("reconnected", text())
        host.accept(BackendStateEvent.Disconnected(1))
        host.accept(BackendStateEvent.Snapshot(1, state(99, "old connection")))
        assertEquals("reconnected", text())
        host.accept(BackendStateEvent.Snapshot(2, state(2, "disabled", enabled = false)))
        assertNull(bar.getWidget(widget.ID()))
    }

    fun testFailureRetainsNameTooltipWidgetAndMarksSuccessStale() {
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
        assertEquals("connected [stale]", presentation.getText())
        assertEquals("Live\n(empty)", presentation.getTooltipText())
        Disposer.dispose(host)
        host.accept(BackendStateEvent.Snapshot(2, state(3, "after disposal")))
        assertNull(bar.getWidget(widget.ID()))
    }

    fun testPopupDetailsUpdateWhenDisplayedValueDoesNotChange() {
        val bar = bar()
        val host = install(bar)
        val initial = state(1, "connected")
        host.accept(BackendStateEvent.Snapshot(1, initial))
        val widget = bar.getWidget("CmdWidget.live") as CmdTextWidget
        val changed = initial.widgets.single().let {
            it.copy(configuration = it.configuration.copy(command = "echo updated", refreshIntervalSeconds = 23))
        }
        host.accept(BackendStateEvent.Snapshot(1, ProjectWidgetState(2, listOf(changed))))
        assertSame(widget, bar.getWidget(widget.ID()))
        assertEquals("connected", widget.label)
        assertEquals("echo updated", widget.details!!.command)
        assertEquals("Every 23 s", widget.details!!.parameters.toMap()["Refresh"])
        val content = widget.createDetailsPanel()
        val header = content.components.first() as javax.swing.JPanel
        assertEquals("Live", (header.components.first() as javax.swing.JLabel).text)
        val settings = header.components.last() as javax.swing.JButton
        assertNotNull(settings.icon)
        assertEquals("Open Cmd Widget settings", settings.accessibleContext.accessibleName)
        assertNull(com.intellij.ide.HelpTooltip.getTooltipFor(widget.component)!!.link)
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
        assertEquals("remote [stale]", text("live"))
        assertEquals("remote", text("local"))
        val nextResult = local.latestResult!!.copy(stdout = "updated")
        host.accept(BackendStateEvent.Snapshot(1, ProjectWidgetState(2, listOf(backend,
            local.copy(latestResult = nextResult, lastSuccessfulResult = nextResult))), connected = false))
        assertEquals("updated", text("local"))
        assertEquals("remote [stale]", text("live"))
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
