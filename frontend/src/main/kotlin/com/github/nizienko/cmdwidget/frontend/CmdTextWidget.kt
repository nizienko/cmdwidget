package com.github.nizienko.cmdwidget.frontend

import com.intellij.openapi.wm.StatusBarWidget

/** A separate platform widget for every definition; IDs survive presentation edits. */
internal class CmdTextWidget(val definitionId: String, var label: String, var tooltip: String = "") :
    StatusBarWidget, StatusBarWidget.TextPresentation {
    override fun ID(): String = "CmdWidget.$definitionId"
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun getText(): String = label
    override fun getAlignment(): Float = 0f
    override fun getTooltipText(): String = tooltip
}
