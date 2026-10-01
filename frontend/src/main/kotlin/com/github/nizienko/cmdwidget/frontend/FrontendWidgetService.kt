package com.github.nizienko.cmdwidget.frontend

import com.github.nizienko.cmdwidget.shared.CmdWidgetSettingsService
import com.github.nizienko.cmdwidget.shared.CommandExecutor
import com.github.nizienko.cmdwidget.shared.ExecutionContext
import com.github.nizienko.cmdwidget.shared.ExecutionContextResolver
import com.github.nizienko.cmdwidget.shared.ExecutionTarget
import com.github.nizienko.cmdwidget.shared.ProjectWidgetRuntime
import com.github.nizienko.cmdwidget.shared.ProjectWidgetState
import com.github.nizienko.cmdwidget.shared.WidgetState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Project-owned local execution continues independently of the backend connection. */
@Service(Service.Level.PROJECT)
internal class FrontendWidgetService(private val project: Project, scope: CoroutineScope) {
    private val settings = service<CmdWidgetSettingsService>()
    private val runtime = ProjectWidgetRuntime(scope, { executionContext() }) { command, context ->
        service<CommandExecutor>().execute(command, context)
    }
    internal val state = runtime.state

    init {
        scope.launch(Dispatchers.IO) {
            settings.effectiveDefinitions.collect { definitions ->
                runtime.reconcile(definitions.filter { it.executionTarget == ExecutionTarget.FRONTEND })
            }
        }
    }

    fun executionContext(): ExecutionContext {
        val projectContext = ExecutionContextResolver.resolve(project)
        return if (projectContext.error == null) projectContext
        else ExecutionContextResolver.resolve(System.getProperty("user.home"))
    }

    suspend fun observe(accept: (BackendStateEvent) -> Unit) = coroutineScope {
        val backend = MutableStateFlow(ProjectWidgetState())
        val connected = MutableStateFlow(false)
        launch {
            BackendStateConnection(project).observe { event ->
                when (event) {
                    is BackendStateEvent.Snapshot -> { backend.value = event.state; connected.value = true }
                    is BackendStateEvent.Disconnected -> connected.value = false
                }
            }
        }
        var version = 0L
        combine(settings.effectiveDefinitions, runtime.state, backend, connected) { definitions, local, remote, online ->
            val localById = local.widgets.associateBy { it.configuration.id }
            val remoteById = remote.widgets.associateBy { it.configuration.id }
            val widgets = definitions.map { definition ->
                val source = if (definition.executionTarget == ExecutionTarget.FRONTEND) localById else remoteById
                // Never display a previous command/target's result while reconciliation catches up.
                source[definition.id]?.takeIf { it.configuration == definition }
                    ?: WidgetState(definition, 0)
            }
            BackendStateEvent.Snapshot(1, ProjectWidgetState(++version, widgets), online)
        }.collect(accept)
    }
}
