package com.github.nizienko.cmdwidget.frontend

import com.github.nizienko.cmdwidget.shared.CmdWidgetConfiguration
import com.github.nizienko.cmdwidget.shared.CmdWidgetSettingsService
import com.github.nizienko.cmdwidget.shared.ExecutionTarget
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class FrontendWidgetServiceTest : BasePlatformTestCase() {
    fun testExplicitDirectoryIsUsedLocallyAndMissingDirectoryDoesNotFallBack() = runBlocking(Dispatchers.IO) {
        val root = java.nio.file.Files.createTempDirectory("cmdwidget-local-directory")
        val settings = service<CmdWidgetSettingsService>()
        val saved = settings.effectiveDefinitions.value
        try {
            withTimeout(5_000) {
                val definition = CmdWidgetConfiguration("directory", "Directory", "pwd", 60,
                    executionTarget = ExecutionTarget.FRONTEND, workingDirectory = root.toString())
                settings.replaceDefinitions(listOf(definition))
                val runtime = project.service<FrontendWidgetService>()
                val snapshot = runtime.state.first { it.widgets.singleOrNull()?.let { widget ->
                    widget.configuration == definition && widget.latestResult != null
                } == true }
                val result = snapshot.widgets.single().latestResult!!
                assertTrue(result.toString(), result.successful)
                assertEquals(root.normalize().toString(), result.context.workingDirectory)
                // Frontend tests on Windows may execute in a mapped Unix test runtime
                // (C:\...\name becomes /tmp/name), so verify the selected directory's
                // identity without requiring the host-specific path prefix.
                assertEquals(root.fileName.toString(), result.stdout.trim().replace('\\', '/').substringAfterLast('/'))
                val missing = root.resolve("missing").toString()
                assertNotNull(runtime.executionContext(missing).error)
                val changed = definition.copy(workingDirectory = missing)
                settings.replaceDefinitions(listOf(changed))
                val failed = runtime.state.first { it.widgets.singleOrNull()?.let { widget ->
                    widget.configuration == changed && widget.latestResult != null
                } == true }.widgets.single()
                assertNotNull(failed.latestResult!!.contextError)
                assertNull(failed.lastSuccessfulResult)
                Unit
            }
        } finally {
            settings.replaceDefinitions(saved)
            com.intellij.openapi.util.io.FileUtil.delete(root.toFile())
        }
    }

    fun testLocalRuntimeExecutesOnlyFrontendDefinitionsAndStopsAfterTargetChange() = runBlocking(Dispatchers.IO) {
        val settings = service<CmdWidgetSettingsService>()
        val saved = settings.effectiveDefinitions.value
        try {
            withTimeout(5_000) {
                val frontend = CmdWidgetConfiguration("local", "Local", "printf local-value", 60,
                    executionTarget = ExecutionTarget.FRONTEND)
                val backend = CmdWidgetConfiguration("remote", "Remote", "printf remote-value", 60)
                settings.replaceDefinitions(listOf(backend, frontend))
                val runtime = project.service<FrontendWidgetService>()
                val snapshot = runtime.state.first { it.widgets.singleOrNull()?.latestResult != null }
                assertEquals(listOf("local"), snapshot.widgets.map { it.configuration.id })
                val result = snapshot.widgets.single().latestResult!!
                assertTrue(result.toString(), result.successful)
                assertEquals("local-value", result.stdout)
                assertEquals(runtime.executionContext(), result.context)
                settings.replaceDefinitions(listOf(backend, frontend.copy(executionTarget = ExecutionTarget.BACKEND)))
                runtime.state.first { it.widgets.isEmpty() }
                Unit
            }
        } finally { settings.replaceDefinitions(saved) }
    }
}
