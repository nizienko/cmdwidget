package com.github.nizienko.cmdwidget.frontend

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import kotlinx.coroutines.CoroutineScope

class CmdWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = CmdWidgetHost.ID
    override fun getDisplayName(): String = "Cmd Widget"
    override fun isAvailable(project: Project): Boolean = !project.isDefault && !project.isDisposed
    override fun isConfigurable(): Boolean = false
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
    override fun createWidget(project: Project, scope: CoroutineScope): StatusBarWidget =
        CmdWidgetHost(scope, BackendStateConnection(project)::observe)
}
