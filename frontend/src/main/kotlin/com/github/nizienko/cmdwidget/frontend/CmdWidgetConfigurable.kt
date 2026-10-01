package com.github.nizienko.cmdwidget.frontend

import com.github.nizienko.cmdwidget.shared.CmdWidgetConfiguration
import com.github.nizienko.cmdwidget.shared.CmdWidgetSettingsService
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.CheckBoxList
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import java.util.UUID
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel

class CmdWidgetConfigurable(private val project: Project) : SearchableConfigurable, Configurable.NoScroll {
    private var panel: CmdWidgetSettingsPanel? = null
    private val settings get() = service<CmdWidgetSettingsService>()
    override fun getId() = "com.github.nizienko.cmdwidget.settings"
    override fun getDisplayName() = "Cmd Widget"
    override fun createComponent(): JComponent = panel ?: CmdWidgetSettingsPanel(project).also { panel = it }
    override fun isModified() = panel?.definitions()?.let { it != settings.effectiveDefinitions.value } ?: false
    override fun reset() { panel?.reset(settings.effectiveDefinitions.value) }
    override fun apply() {
        val definitions = panel?.definitions() ?: return
        definitions.firstNotNullOfOrNull { it.validationError() }?.let { throw ConfigurationException(it) }
        try { settings.replaceDefinitions(definitions) }
        catch (error: IllegalArgumentException) { throw ConfigurationException(error.message ?: "Invalid widget definitions") }
    }
    override fun disposeUIResources() {
        panel?.disposeEditors()
        panel = null
    }
}

internal class CmdWidgetSettingsPanel(private val project: Project) : JPanel(BorderLayout(0, 8)) {
    private val list = object : CheckBoxList<CmdWidgetConfiguration>() {
        override fun adjustRendering(
            rootComponent: JComponent,
            checkBox: JCheckBox,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ): JComponent = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            background = rootComponent.background
            border = rootComponent.border
            rootComponent.border = JBUI.Borders.empty()
            add(rootComponent, BorderLayout.WEST)
            add(JBLabel(getItemAt(index)?.command.orEmpty()).apply {
                putClientProperty("html.disable", true)
                foreground = JBColor.GRAY
                font = checkBox.font
            }, BorderLayout.CENTER)
        }
    }
    private val editors = mutableSetOf<WidgetEditorDialog>()

    init {
        list.selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val index = list.locationToIndex(event.point)
                if (index < 0 || list.getCellBounds(index, index)?.contains(event.point) != true) return false
                list.selectedIndex = index
                edit(definitions()[index])
                return true
            }
        }.installOn(list)
        val decorator = ToolbarDecorator.createDecorator(list)
            .setAddAction { edit(null) }
            .setEditAction {
                if (list.selectedIndex >= 0) edit(definitions()[list.selectedIndex])
            }
        add(decorator.createPanel(), BorderLayout.CENTER)
    }

    fun definitions() = (0 until list.model.size).map { index ->
        requireNotNull(list.getItemAt(index)).copy(enabled = list.isItemSelected(index))
    }
    fun reset(definitions: List<CmdWidgetConfiguration>) {
        list.clear()
        definitions.forEach(::addDefinition)
    }
    private fun addDefinition(definition: CmdWidgetConfiguration) {
        list.addItem(definition, definition.name, definition.enabled)
        list.model.getElementAt(list.model.size - 1).putClientProperty("html.disable", true)
    }
    private fun edit(existing: CmdWidgetConfiguration?) {
        val project = project.takeUnless { it.isDisposed || it.isDefault }
        val definition = existing ?: CmdWidgetConfiguration(UUID.randomUUID().toString(), "", "")
        val dialog = WidgetEditorDialog(project, definition)
        editors += dialog
        try {
            if (dialog.showAndGet()) {
                val edited = dialog.editor.configuration()
                val index = definitions().indexOfFirst { it.id == edited.id }
                if (index < 0) addDefinition(edited) else {
                    list.updateItem(requireNotNull(list.getItemAt(index)), edited, edited.name)
                    list.setItemSelected(edited, edited.enabled)
                }
                list.selectedIndex = if (index < 0) list.model.size - 1 else index
            }
        } finally { editors -= dialog }
    }
    fun disposeEditors() { editors.toList().forEach { it.close(DialogWrapper.CANCEL_EXIT_CODE) }; editors.clear() }
}

private class WidgetEditorDialog(project: Project?, definition: CmdWidgetConfiguration) : DialogWrapper(project, true) {
    val editor = WidgetEditorPanel(definition, project?.let(CommandTestService::session))
    init {
        title = "Cmd Widget — ${if (definition.name.isBlank()) "Add" else "Edit"}"
        Disposer.register(disposable, editor)
        init()
        initValidation()
    }
    override fun createCenterPanel() = editor
    override fun getPreferredFocusedComponent() = editor.name
    override fun doValidate() = editor.validation()
}
