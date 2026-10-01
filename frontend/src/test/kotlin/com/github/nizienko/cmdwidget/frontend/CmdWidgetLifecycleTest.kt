package com.github.nizienko.cmdwidget.frontend

import com.intellij.openapi.util.Disposer
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

    private fun IdeStatusBarImpl.element(id: String): CmdTextWidget? =
        (getWidget(CmdWidgetHost.ID) as? CmdWidgetHost)?.element(id)

    fun testIndependentUpdateRemovalAndRestoration() {
        val bar = bar()
        val host = install(bar)
        val git = bar.element("CmdWidget.prototype-git")!!
        val disk = bar.element("CmdWidget.prototype-disk")!!
        assertEquals("Git: main", git.label)
        assertEquals("Disk: 126G", disk.label)

        host.cyclePrototype()
        assertSame(git, bar.element("CmdWidget.${git.definitionId}"))
        assertSame(disk, bar.element("CmdWidget.${disk.definitionId}"))
        assertEquals("Disk: updated", disk.label)

        host.cyclePrototype()
        assertSame(git, bar.element("CmdWidget.${git.definitionId}"))
        assertNull(bar.element("CmdWidget.${disk.definitionId}"))
        assertTrue(Disposer.isDisposed(disk))

        host.cyclePrototype()
        assertSame(git, bar.element("CmdWidget.${git.definitionId}"))
        assertNotSame(disk, bar.element("CmdWidget.${disk.definitionId}"))
        assertEquals(1, bar.allWidgets!!.size)
        assertEquals(2, host.getComponent().componentCount)
    }

    fun testTwoWindowsKeepSeparateInstancesAndDisposeOnlyOwnedWidgets() {
        val firstBar = bar()
        val secondBar = bar()
        val first = install(firstBar)
        val second = install(secondBar)
        val secondGit = secondBar.element("CmdWidget.prototype-git")!!
        assertNotSame(firstBar.element("CmdWidget.${secondGit.definitionId}"), secondGit)
        first.cyclePrototype()
        first.cyclePrototype()
        assertNotNull(secondBar.element("CmdWidget.prototype-disk"))

        // Same factory disposal path is used on extension removal/plugin unload.
        CmdWidgetFactory().disposeWidget(first)
        assertNull(firstBar.element("CmdWidget.prototype-git"))
        assertSame(secondGit, secondBar.element("CmdWidget.${secondGit.definitionId}"))
        assertFalse(Disposer.isDisposed(secondGit))

        Disposer.dispose(second)
        assertNull(secondBar.element("CmdWidget.${secondGit.definitionId}"))
        assertTrue(Disposer.isDisposed(secondGit))
    }

    fun testReorderingUpdatesStatusBarPositionsAndKeepsPresentations() {
        val bar = bar()
        val host = install(bar)
        val original = CmdWidgetHost.PROTOTYPE
        fun assertOrder(definitions: List<Pair<String, String>>) {
            val components = definitions.map { (id, text) ->
                val widget = bar.element("CmdWidget.$id") as CmdTextWidget
                assertEquals(text, widget.label)
                assertFalse(Disposer.isDisposed(widget))
                widget.component
            }
            val positions = components.map {
                it.parent.getComponentZOrder(it)
            }
            assertTrue("Widgets must follow the configured order", positions.zipWithNext().all { (left, right) -> left < right })
            assertEquals(1, bar.allWidgets!!.size)
            assertEquals(definitions.size, host.getComponent().componentCount)
        }

        assertOrder(original)
        val originalElements = original.map { (id, _) -> bar.element("CmdWidget.$id")!! }
        host.reconcile(original.reversed())
        assertOrder(original.reversed())
        originalElements.forEach { assertSame(it, bar.element("CmdWidget.${it.definitionId}")) }
        val reordered = original.map { (id, _) -> bar.element("CmdWidget.$id")!! }
        host.reconcile(original.reversed())
        reordered.forEach { assertSame(it, bar.element("CmdWidget.${it.definitionId}")) }
        host.reconcile(original)
        assertOrder(original)

        Disposer.dispose(host)
        original.forEach { (id, _) -> assertNull(bar.element("CmdWidget.$id")) }
    }

    fun testInsertingWidgetBetweenExistingWidgetsUpdatesPositions() {
        val bar = bar()
        val host = install(bar)
        val first = bar.element("CmdWidget.prototype-git")!!
        val definitions = listOf(CmdWidgetHost.PROTOTYPE[0], "middle" to "Middle", CmdWidgetHost.PROTOTYPE[1])
        host.reconcile(definitions)
        assertSame(first, bar.element("CmdWidget.${first.definitionId}"))
        val positions = definitions.map { (id, text) ->
            val widget = bar.element("CmdWidget.$id") as CmdTextWidget
            assertEquals(text, widget.label)
            val component = widget.component
            component.parent.getComponentZOrder(component)
        }
        assertTrue("The new widget must appear between existing widgets", positions.zipWithNext().all { (left, right) -> left < right })
        assertEquals(1, bar.allWidgets!!.size)
        assertEquals(3, host.getComponent().componentCount)
        val panel = host.getComponent()
        panel.size = panel.preferredSize
        panel.doLayout()
        val children = panel.components.toList()
        assertTrue(children.all { it.width > 0 && it.height > 0 })
        assertTrue(children.zipWithNext().all { (left, right) -> left.x + left.width <= right.x })
    }

    fun testEmptyDefinitionsHideContainerAndRestoringRetainsSinglePlatformWidget() {
        val bar = bar()
        val host = install(bar)
        val elements = CmdWidgetHost.PROTOTYPE.map { (id, _) -> host.element(id)!! }
        host.reconcile(emptyList())
        assertFalse(host.getComponent().isVisible)
        assertEquals(0, host.getComponent().componentCount)
        assertEquals(0, host.getComponent().preferredSize.width)
        assertEquals(1, bar.allWidgets!!.size)
        elements.forEach { assertTrue(Disposer.isDisposed(it)) }

        host.reconcile(CmdWidgetHost.PROTOTYPE)
        assertTrue(host.getComponent().isVisible)
        assertEquals(2, host.getComponent().componentCount)
        assertEquals(1, bar.allWidgets!!.size)
    }

    fun testDisposalBeforeDeferredInstallAndReopenDoNotDuplicateWidgets() {
        val bar = bar()
        val pending = CmdWidgetHost()
        bar.addWidget(pending, testRootDisposable)
        Disposer.dispose(pending)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertNull(bar.element("CmdWidget.prototype-git"))
        bar.removeWidget(CmdWidgetHost.ID)

        val reopened = install(bar)
        reopened.reconcile(CmdWidgetHost.PROTOTYPE)
        reopened.reconcile(CmdWidgetHost.PROTOTYPE)
        assertEquals(1, bar.allWidgets!!.size)
        Disposer.dispose(reopened)
        assertNull(bar.element("CmdWidget.prototype-git"))
        assertNull(bar.element("CmdWidget.prototype-disk"))
    }

    fun testParentDisposalReleasesAllWidgets() {
        val bar = bar()
        val windowLifetime = Disposer.newDisposable(testRootDisposable, "prototype project window")
        val host = CmdWidgetHost()
        bar.addWidget(host, windowLifetime)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val git = bar.element("CmdWidget.prototype-git")!!
        val disk = bar.element("CmdWidget.prototype-disk")!!

        Disposer.dispose(windowLifetime)
        assertNull(bar.getWidget(CmdWidgetHost.ID))
        assertNull(bar.element("CmdWidget.${git.definitionId}"))
        assertNull(bar.element("CmdWidget.${disk.definitionId}"))
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
        val git = bar.element("CmdWidget.prototype-git")!!

        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertSame(git, bar.element("CmdWidget.${git.definitionId}"))
        assertEquals(1, bar.allWidgets!!.size)
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
        val widget = bar.element("CmdWidget.live") as CmdTextWidget
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
        assertSame(widget, bar.element("CmdWidget.${widget.definitionId}"))
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
        val widget = bar.element("CmdWidget.live")!!
        fun text() = widget.label
        host.accept(BackendStateEvent.Snapshot(1, state(9, "obsolete")))
        assertEquals("current", text())
        host.accept(BackendStateEvent.Disconnected(1))
        assertEquals("current [stale]", text())
        host.accept(BackendStateEvent.Snapshot(1, state(11, "too late")))
        assertEquals("current [stale]", text())
        host.accept(BackendStateEvent.Snapshot(2, state(1, "reconnected")))
        assertSame(widget, bar.element("CmdWidget.${widget.definitionId}"))
        assertEquals("reconnected", text())
        host.accept(BackendStateEvent.Disconnected(1))
        host.accept(BackendStateEvent.Snapshot(1, state(99, "old connection")))
        assertEquals("reconnected", text())
        host.accept(BackendStateEvent.Snapshot(2, state(2, "disabled", enabled = false)))
        assertNull(bar.element("CmdWidget.${widget.definitionId}"))
    }

    fun testFailureRetainsNameTooltipWidgetAndMarksSuccessStale() {
        val bar = bar()
        val host = install(bar)
        val initial = state(1, "connected")
        host.accept(BackendStateEvent.Snapshot(1, initial))
        val widget = bar.element("CmdWidget.live")!!
        val failed = initial.widgets.single().copy(latestResult = initial.widgets.single().latestResult!!.copy(
            exitCode = 7, stdout = "", stderr = "diagnostic", completedAtEpochMillis = 2_000,
        ))
        host.accept(BackendStateEvent.Snapshot(1, ProjectWidgetState(2, listOf(failed))))
        assertSame(widget, bar.element("CmdWidget.${widget.definitionId}"))
        assertEquals("connected [stale]", widget.label)
        assertEquals("Live\n(empty)", widget.tooltip)
        Disposer.dispose(host)
        host.accept(BackendStateEvent.Snapshot(2, state(3, "after disposal")))
        assertNull(bar.element("CmdWidget.${widget.definitionId}"))
    }

    fun testPopupDetailsUpdateWhenDisplayedValueDoesNotChange() {
        val bar = bar()
        val host = install(bar)
        val initial = state(1, "connected")
        host.accept(BackendStateEvent.Snapshot(1, initial))
        val widget = bar.element("CmdWidget.live") as CmdTextWidget
        val changed = initial.widgets.single().let {
            it.copy(configuration = it.configuration.copy(command = "echo updated", refreshIntervalSeconds = 23))
        }
        host.accept(BackendStateEvent.Snapshot(1, ProjectWidgetState(2, listOf(changed))))
        assertSame(widget, bar.element("CmdWidget.${widget.definitionId}"))
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
        fun text(id: String) = bar.element("CmdWidget.$id")!!.label
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
        assertNull(bar.element("CmdWidget.live"))
    }
}
