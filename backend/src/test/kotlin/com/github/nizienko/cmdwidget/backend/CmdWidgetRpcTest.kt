package com.github.nizienko.cmdwidget.backend

import com.github.nizienko.cmdwidget.shared.CmdWidgetRpcApi
import com.github.nizienko.cmdwidget.shared.CmdWidgetConfiguration
import com.github.nizienko.cmdwidget.shared.ExecutionTarget
import com.github.nizienko.cmdwidget.shared.CmdWidgetSettingsService
import com.intellij.openapi.components.service
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.util.Disposer
import com.intellij.platform.project.projectId
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.platform.rpc.backend.RemoteApiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** The module test sandbox omits the root plugin descriptor; register its real provider in the fixture. */
class CmdWidgetRpcTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        RemoteApiProvider.EP_NAME.point.registerExtension(CmdWidgetRpcApiProvider(), testRootDisposable)
    }

    override fun tearDown() {
        try { service<CmdWidgetSettingsService>().replaceDefinitions(emptyList()) }
        finally { super.tearDown() }
    }

    fun testBackendExecutesOnlyBackendDefinitionsAndStopsAfterTargetChange() {
        // Light fixtures reuse a project whose previous test's directory may have been deleted.
        val root = java.nio.file.Files.createTempDirectory("cmdwidget-target-project")
        val testProject = ProjectManagerEx.getInstanceEx().newProject(root, OpenProjectTask {
            isNewProject = true
            useDefaultProjectAsTemplate = false
        })!!
        try {
            runBlocking(Dispatchers.IO) {
                withTimeout(5_000) {
                    val settings = service<CmdWidgetSettingsService>()
                    val backend = CmdWidgetConfiguration("backend", "Backend", "printf backend", 60)
                    val frontend = CmdWidgetConfiguration("frontend", "Frontend", "printf frontend", 60,
                        executionTarget = ExecutionTarget.FRONTEND)
                    settings.replaceDefinitions(listOf(frontend, backend))
                    val runtime = testProject.service<CmdWidgetProjectService>()
                    val snapshot = runtime.state.first {
                        it.widgets.singleOrNull()?.let { widget -> widget.configuration == backend && widget.latestResult != null } == true
                    }
                    assertEquals(listOf("backend"), snapshot.widgets.map { it.configuration.id })
                    assertTrue(snapshot.widgets.single().latestResult.toString(), snapshot.widgets.single().latestResult!!.successful)
                    assertEquals("backend", snapshot.widgets.single().latestResult!!.stdout)
                    settings.replaceDefinitions(listOf(frontend, backend.copy(executionTarget = ExecutionTarget.FRONTEND)))
                    runtime.state.first { it.widgets.isEmpty() }
                }
            }
        } finally {
            WriteAction.run<RuntimeException> { Disposer.dispose(testProject) }
            com.intellij.openapi.util.io.FileUtil.delete(root.toFile())
        }
    }

    fun testSavedDefinitionsReachNewProjectsAndApplyKeepsIndependentResults() {
        val root = java.nio.file.Files.createTempDirectory("cmdwidget-settings-project")
        val firstRoot = java.nio.file.Files.createTempDirectory("cmdwidget-settings-first-project")
        val firstProject = ProjectManagerEx.getInstanceEx().newProject(firstRoot, OpenProjectTask {
            isNewProject = true
            useDefaultProjectAsTemplate = false
        })!!
        val second = ProjectManagerEx.getInstanceEx().newProject(root, OpenProjectTask {
            isNewProject = true
            useDefaultProjectAsTemplate = false
        })!!
        try {
            runBlocking(Dispatchers.IO) {
                withTimeout(10_000) {
                    val settings = service<CmdWidgetSettingsService>()
                    val definition = CmdWidgetConfiguration("shared", "Root", "pwd", 60)
                    settings.replaceDefinitions(listOf(definition))
                    val firstRuntime = firstProject.service<CmdWidgetProjectService>()
                    val secondRuntime = second.service<CmdWidgetProjectService>()
                    val first = firstRuntime.state.first { it.widgets.singleOrNull()?.latestResult != null }
                    val other = secondRuntime.state.first { it.widgets.singleOrNull()?.latestResult != null }
                    assertEquals(definition, first.widgets.single().configuration)
                    assertEquals(definition, other.widgets.single().configuration)
                    assertTrue(first.widgets.single().latestResult.toString(), first.widgets.single().latestResult!!.successful)
                    assertTrue(other.widgets.single().latestResult.toString(), other.widgets.single().latestResult!!.successful)
                    assertFalse(first.widgets.single().latestResult!!.stdout == other.widgets.single().latestResult!!.stdout)

                    settings.replaceDefinitions(listOf(definition.copy(name = "Renamed")))
                    val renamedFirst = firstRuntime.state.first { it.widgets.singleOrNull()?.configuration?.name == "Renamed" }
                    val renamedOther = secondRuntime.state.first { it.widgets.singleOrNull()?.configuration?.name == "Renamed" }
                    assertEquals(first.widgets.single().latestResult, renamedFirst.widgets.single().latestResult)
                    assertEquals(other.widgets.single().latestResult, renamedOther.widgets.single().latestResult)
                    val version = renamedFirst.version
                    settings.loadState(settings.state) // Repeated synchronized state does not create another attempt.
                    assertEquals(version, firstRuntime.state.value.version)
                    settings.replaceDefinitions(emptyList())
                    firstRuntime.state.first { it.widgets.isEmpty() }
                    secondRuntime.state.first { it.widgets.isEmpty() }
                }
            }
        } finally {
            WriteAction.run<RuntimeException> {
                Disposer.dispose(second)
                Disposer.dispose(firstProject)
            }
            com.intellij.openapi.util.io.FileUtil.delete(root.toFile())
            com.intellij.openapi.util.io.FileUtil.delete(firstRoot.toFile())
        }
    }
    fun testObservationResolvesExistingRuntimeAndTestingDoesNotPublish() = runBlocking(Dispatchers.IO) {
        withTimeout(5_000) {
            val api = CmdWidgetRpcApi.getInstance()
            val id = project.projectId()
            val runtime = project.service<CmdWidgetProjectService>()
            val settings = service<CmdWidgetSettingsService>()
            settings.replaceDefinitions(emptyList())
            runtime.state.first { it.version > 0 && it.widgets.isEmpty() }
            val initial = api.observe(id).first()
            assertEquals(runtime.state.value, initial)
            val context = api.executionContext(id)
            assertNull("RPC test requires a usable backend project root", context.error)
            val tested = api.testCommand(id, "printf explicit-test")
            assertTrue(tested.toString(), tested.successful)
            assertEquals("explicit-test", tested.stdout)
            assertEquals(initial, api.observe(id).first())

            settings.replaceDefinitions(listOf(CmdWidgetConfiguration("rpc", "RPC", "printf live", 60)))
            val observed = api.observe(id).first { it.widgets.singleOrNull()?.latestResult != null }
            assertEquals(runtime.state.value, observed)
            val result = observed.widgets.single().latestResult!!
            assertEquals("live", result.stdout)
            assertEquals(observed, api.observe(id).first()) // Re-observation does not change state or start execution.
            val before = observed.widgets.single()
            settings.replaceDefinitions(listOf(before.configuration.copy(name = "Renamed")))
            val renamed = api.observe(id).first { it.widgets.singleOrNull()?.configuration?.name == "Renamed" }
            assertEquals(before.revision, renamed.widgets.single().revision)
            assertEquals(before.latestResult, renamed.widgets.single().latestResult)
            settings.replaceDefinitions(emptyList())
            api.observe(id).first { it.widgets.isEmpty() }
            Unit
        }
    }
}
