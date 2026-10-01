package com.github.nizienko.cmdwidget.backend

import com.github.nizienko.cmdwidget.shared.CmdWidgetRpcApi
import com.github.nizienko.cmdwidget.shared.ExecutionContextResolver
import com.intellij.openapi.components.service
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull
import com.intellij.platform.rpc.backend.RemoteApiProvider
import fleet.rpc.remoteApiDescriptor

internal class BackendCmdWidgetRpcApi : CmdWidgetRpcApi {
    private fun project(id: ProjectId) = checkNotNull(id.findProjectOrNull()) { "Backend project is closed" }

    override suspend fun observe(projectId: ProjectId) = project(projectId).service<CmdWidgetProjectService>().state

    override suspend fun executionContext(projectId: ProjectId) = ExecutionContextResolver.resolve(project(projectId))

    override suspend fun testCommand(projectId: ProjectId, command: String) =
        project(projectId).service<CmdWidgetProjectService>().testCommand(command)
}

class CmdWidgetRpcApiProvider : RemoteApiProvider {
    override fun RemoteApiProvider.Sink.remoteApis() {
        remoteApi(remoteApiDescriptor<CmdWidgetRpcApi>()) { BackendCmdWidgetRpcApi() }
    }
}
