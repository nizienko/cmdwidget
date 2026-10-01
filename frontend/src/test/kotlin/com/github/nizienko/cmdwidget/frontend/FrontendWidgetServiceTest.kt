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
