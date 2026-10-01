package com.github.nizienko.cmdwidget.frontend

import com.github.nizienko.cmdwidget.shared.CmdWidgetRpcApi
import com.github.nizienko.cmdwidget.shared.CommandResult
import com.github.nizienko.cmdwidget.shared.ExecutionContext
import com.github.nizienko.cmdwidget.shared.ExecutionTarget
import com.github.nizienko.cmdwidget.shared.CommandExecutor
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.platform.project.projectId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal interface CommandTestBackend {
    suspend fun context(workingDirectory: String = ""): ExecutionContext
    suspend fun test(command: String, workingDirectory: String = ""): CommandResult
}

@Service(Service.Level.PROJECT)
internal class CommandTestService(private val project: Project, private val scope: CoroutineScope) {
    fun session(): CommandTestSession = CommandTestSession(scope, object : CommandTestBackend {
        override suspend fun context(workingDirectory: String) = CmdWidgetRpcApi.getInstance().executionContext(project.projectId(), workingDirectory)
        override suspend fun test(command: String, workingDirectory: String) = CmdWidgetRpcApi.getInstance().testCommand(project.projectId(), command, workingDirectory)
    }, frontend = object : CommandTestBackend {
        override suspend fun context(workingDirectory: String) = project.service<FrontendWidgetService>().executionContext(workingDirectory)
        override suspend fun test(command: String, workingDirectory: String) = service<CommandExecutor>().execute(command, context(workingDirectory))
    })

    companion object {
        fun session(project: Project) = project.service<CommandTestService>().session()
    }
}

/** Single explicit attempt, tied to both editor and project lifetime; no replay on reconnect. */
internal class CommandTestSession(
    parent: CoroutineScope,
    private val backend: CommandTestBackend,
    private val frontend: CommandTestBackend = backend,
    private val dispatch: (() -> Unit) -> Unit = { action ->
        ApplicationManager.getApplication().invokeLater(action, ModalityState.any())
    },
) : Disposable {
    private val lifetime = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + lifetime)
    private var attempt: Job? = null
    @Volatile private var generation = 0L
    @Volatile private var disposed = false

    fun onProjectClosed(accept: () -> Unit) {
        lifetime.invokeOnCompletion {
            dispatch { if (!disposed) accept() }
        }
    }

    fun loadContext(target: ExecutionTarget = ExecutionTarget.BACKEND, workingDirectory: String = "", accept: (ExecutionContext?, String?) -> Unit) {
        scope.launch {
            try {
                val context = runner(target).context(workingDirectory)
                deliver { accept(context, null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                deliver { accept(null, error.message ?: "Backend unavailable") }
            }
        }
    }

    fun test(command: String, target: ExecutionTarget = ExecutionTarget.BACKEND, workingDirectory: String = "", accept: (CommandResult?, String?) -> Unit) {
        check(attempt?.isActive != true) { "A command test is already running" }
        val token = ++generation
        attempt = scope.launch {
            try {
                val result = runner(target).test(command, workingDirectory)
                deliver { if (generation == token) accept(result, null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                deliver { if (generation == token) accept(null, error.message ?: "Command test failed") }
            }
        }
    }

    fun cancelTest() {
        generation++
        attempt?.cancel()
    }

    private fun runner(target: ExecutionTarget) = if (target == ExecutionTarget.FRONTEND) frontend else backend

    private fun deliver(action: () -> Unit) = dispatch {
        if (!disposed && lifetime.isActive) action()
    }

    override fun dispose() {
        disposed = true
        generation++
        scope.cancel()
    }
}
