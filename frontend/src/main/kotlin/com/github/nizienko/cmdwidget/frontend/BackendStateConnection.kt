package com.github.nizienko.cmdwidget.frontend

import com.github.nizienko.cmdwidget.shared.CmdWidgetRpcApi
import com.github.nizienko.cmdwidget.shared.ProjectWidgetState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.project.projectId
import fleet.rpc.client.durable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal sealed interface BackendStateEvent {
    val session: Long
    data class Snapshot(override val session: Long, val state: ProjectWidgetState, val connected: Boolean = true) : BackendStateEvent
    data class Disconnected(override val session: Long) : BackendStateEvent
}

internal class BackendStateConnection(private val project: Project) {
    suspend fun observe(accept: (BackendStateEvent) -> Unit) {
        var session = 0L
        var failing = false
        while (currentCoroutineContext().isActive) {
            try {
                durable {
                    val currentSession = ++session
                    try {
                        CmdWidgetRpcApi.getInstance().observe(project.projectId()).collect {
                            failing = false
                            accept(BackendStateEvent.Snapshot(currentSession, it))
                        }
                    } finally {
                        accept(BackendStateEvent.Disconnected(currentSession))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Only the first failure of a streak is visible; retries repeat every second.
                val log = logger<BackendStateConnection>()
                if (failing) log.debug("Cmd Widget backend connection failed; retrying", failure)
                else log.warn("Cmd Widget backend connection failed; retrying", failure)
                failing = true
            }
            delay(1_000) // A normally completed subscription also reconnects without spinning.
        }
    }
}
