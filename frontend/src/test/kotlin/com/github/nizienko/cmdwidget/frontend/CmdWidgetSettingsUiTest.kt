package com.github.nizienko.cmdwidget.frontend

import com.github.nizienko.cmdwidget.shared.CmdWidgetConfiguration
import com.github.nizienko.cmdwidget.shared.CmdWidgetSettingsService
import com.github.nizienko.cmdwidget.shared.CommandResult
import com.github.nizienko.cmdwidget.shared.ExecutionContext
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Container
import javax.swing.JButton
import javax.swing.JList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedQueue

class CmdWidgetSettingsUiTest : BasePlatformTestCase() {
    private val first = CmdWidgetConfiguration("first", "First", "pwd")
    private val second = CmdWidgetConfiguration("second", "Second", "printf second")

    private fun descendants(container: Container): List<java.awt.Component> =
        container.components.flatMap { listOf(it) + if (it is Container) descendants(it) else emptyList() }

    fun testApplyCommitsDraftWhileCancelAndResetDiscardChanges() {
        val settings = service<CmdWidgetSettingsService>()
        val saved = settings.effectiveDefinitions.value
        val configurable = CmdWidgetConfigurable()
        try {
            settings.replaceDefinitions(listOf(first, second))
            val panel = configurable.createComponent() as CmdWidgetSettingsPanel
            configurable.reset()
            val list = descendants(panel).filterIsInstance<JList<*>>().single()
            fun click(label: String) = descendants(panel).filterIsInstance<JButton>().single { it.text == label }.doClick()
            list.selectedIndex = 0
            click("Enable / Disable")
            assertTrue(configurable.isModified)
            assertEquals(listOf(first, second), settings.effectiveDefinitions.value)
            configurable.reset()
            assertFalse(configurable.isModified)
            list.selectedIndex = 0
            click("Move down")
            click("Enable / Disable")
            configurable.apply()
            assertEquals(listOf(second, first.copy(enabled = false)), settings.effectiveDefinitions.value)
            assertFalse(configurable.isModified)
            list.selectedIndex = 0
            click("Remove")
            assertTrue(configurable.isModified)
            configurable.disposeUIResources() // Settings Cancel closes without Apply.
            assertEquals(listOf(second, first.copy(enabled = false)), settings.effectiveDefinitions.value)
        } finally {
            configurable.disposeUIResources()
            settings.replaceDefinitions(saved)
        }
    }

    fun testEditorValidatesUnsavedValuesPreservesIdAndCannotTestWithoutContext() {
        val editor = WidgetEditorPanel(first, null)
        try {
            assertFalse(editor.testButton.isEnabled)
            editor.name.text = "Renamed"
            editor.command.text = "printf unsaved"
            editor.interval.text = "1.5"
            assertNotNull(editor.validation())
            editor.interval.text = "0"
            assertNotNull(editor.validation())
            editor.interval.text = "2147483648"
            assertNotNull(editor.validation())
            editor.interval.text = "5"
            editor.name.text = "a".repeat(41)
            assertNotNull(editor.validation())
            editor.name.text = " "
            assertNotNull(editor.validation())
            editor.name.text = "Renamed"
            editor.command.text = " "
            assertNotNull(editor.validation())
            editor.command.text = "printf unsaved"
            editor.enabledBox.isSelected = false
            assertNull(editor.validation())
            assertEquals(first.copy(name = "Renamed", command = "printf unsaved", refreshIntervalSeconds = 5, enabled = false), editor.configuration())
            assertEquals("pwd", first.command)
        } finally { editor.dispose() }
    }

    fun testResultDisplaysBothStreamsErrorsExitDurationAndTruncationAsPlainText() {
        val result = CommandResult(ExecutionContext("backend", "/repo", "/bin/sh", "Linux"),
            stdout = "<html>stdout", stderr = "stderr", exitCode = 7, durationMillis = 123,
            completedAtEpochMillis = 1, stdoutTruncated = true, stderrTruncated = true, cleanupError = "cleanup")
        val display = WidgetEditorPanel.formatTestResult(result)
        assertTrue(display.contains("Exit code: 7; duration: 123 ms"))
        assertTrue(display.contains("stdout [truncated at 64 KiB]:\n<html>stdout"))
        assertTrue(display.contains("stderr [truncated at 64 KiB]:\nstderr"))
        assertTrue(display.contains("Error: cleanup"))
    }

    fun testTypingDoesNotExecuteAndButtonTestsUnsavedCommandWithoutSaving() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val callbacks = ConcurrentLinkedQueue<() -> Unit>()
        val context = ExecutionContext("backend", "/repo", "/bin/sh", "Linux")
        val tested = CompletableDeferred<String>()
        val backend = object : CommandTestBackend {
            override suspend fun context() = context
            override suspend fun test(command: String): CommandResult {
                tested.complete(command)
                return CommandResult(context, stdout = "unsaved value", exitCode = 0, completedAtEpochMillis = 1)
            }
        }
        val editor = WidgetEditorPanel(first, CommandTestSession(scope, backend) { callbacks.add(it) })
        fun deliverNext() = runBlocking {
            withTimeout(5_000) { while (callbacks.isEmpty()) kotlinx.coroutines.delay(5) }
            callbacks.remove().invoke()
        }
        val settings = service<CmdWidgetSettingsService>()
        val saved = settings.effectiveDefinitions.value
        try {
            assertFalse(editor.testButton.isEnabled)
            deliverNext()
            assertTrue(editor.testButton.isEnabled)
            editor.command.text = "printf unsaved"
            editor.name.text = "Unsaved name"
            assertFalse(tested.isCompleted)
            editor.testButton.doClick()
            assertFalse(editor.testButton.isEnabled)
            deliverNext()
            assertEquals("printf unsaved", runBlocking { tested.await() })
            assertTrue(editor.output.text.contains("unsaved value"))
            assertEquals(saved, settings.effectiveDefinitions.value)
            assertTrue(editor.testButton.isEnabled)
            scope.cancel() // Project/service cancellation also disables an editor that remains open.
            deliverNext()
            assertFalse(editor.testButton.isEnabled)
            assertFalse(editor.cancelButton.isEnabled)
        } finally { editor.dispose(); scope.cancel() }
    }

    fun testInvalidBackendContextDisablesTesting() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val callback = CompletableDeferred<() -> Unit>()
        val backend = object : CommandTestBackend {
            override suspend fun context() = ExecutionContext("backend", null, "/bin/sh", "Linux", "No usable project root directory")
            override suspend fun test(command: String): CommandResult = error("Invalid context must not execute")
        }
        val editor = WidgetEditorPanel(first, CommandTestSession(scope, backend) { callback.complete(it) })
        try {
            runBlocking { withTimeout(5_000) { callback.await() } }.invoke()
            assertFalse(editor.testButton.isEnabled)
            editor.command.text = "printf changed"
            assertFalse(editor.testButton.isEnabled)
        } finally { editor.dispose(); scope.cancel() }
    }
}
