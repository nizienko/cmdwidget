package com.github.nizienko.cmdwidget.shared

import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor
import kotlinx.coroutines.flow.Flow

@Rpc
interface CmdWidgetRpcApi : RemoteApi<Unit> {
    suspend fun observe(projectId: ProjectId): Flow<ProjectWidgetState>
    suspend fun executionContext(projectId: ProjectId): ExecutionContext
    /** Explicit request only. Does not reconcile definitions or publish live values. */
    suspend fun testCommand(projectId: ProjectId, command: String): CommandResult

    companion object {
        suspend fun getInstance(): CmdWidgetRpcApi =
            RemoteApiProviderService.resolve(remoteApiDescriptor<CmdWidgetRpcApi>())
    }
}
