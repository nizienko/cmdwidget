package com.github.nizienko.cmdwidget.backend

import com.github.nizienko.cmdwidget.shared.CommandResult
import com.github.nizienko.cmdwidget.shared.CommandExecutor
import com.github.nizienko.cmdwidget.shared.ExecutionContextResolver
import com.github.nizienko.cmdwidget.shared.ExecutionTarget
import com.github.nizienko.cmdwidget.shared.ProjectWidgetRuntime
import com.github.nizienko.cmdwidget.shared.CmdWidgetSettingsService
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** One settings subscription per project, independent of frontend RPC connections. */
@Service(Service.Level.PROJECT)
class CmdWidgetProjectService(private val project: Project, private val scope: CoroutineScope) {
    private val runtime = ProjectWidgetRuntime(
        scope,
        { ExecutionContextResolver.resolve(project) },
        { command, context -> service<CommandExecutor>().execute(command, context) },
    )
    val state = runtime.state

    init {
        scope.launch(Dispatchers.IO) {
            service<CmdWidgetSettingsService>().effectiveDefinitions.collect { definitions ->
                runtime.reconcile(definitions.filter { it.executionTarget == ExecutionTarget.BACKEND })
            }
        }
    }

    suspend fun testCommand(command: String): CommandResult {
        // Owned by the RPC caller/editor and project lifecycle, in addition to the executor's scope.
        val request = scope.async(Dispatchers.IO) {
            service<CommandExecutor>().execute(command, ExecutionContextResolver.resolve(project))
        }
        try { return request.await() }
        finally { withContext(NonCancellable) { request.cancelAndJoin() } }
    }
}

class CmdWidgetRuntimeActivity : com.intellij.openapi.startup.ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<CmdWidgetProjectService>()
    }
}
