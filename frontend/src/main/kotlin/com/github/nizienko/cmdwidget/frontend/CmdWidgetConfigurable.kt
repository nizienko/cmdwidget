package com.github.nizienko.cmdwidget.frontend

import com.github.nizienko.cmdwidget.shared.CmdWidgetConfiguration
import com.github.nizienko.cmdwidget.shared.CmdWidgetSettingsService
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Component
import java.util.UUID
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel

class CmdWidgetConfigurable : SearchableConfigurable, Configurable.NoScroll {
    private var panel: CmdWidgetSettingsPanel? = null
    private val settings get() = service<CmdWidgetSettingsService>()
    override fun getId() = "com.github.nizienko.cmdwidget.settings"
    override fun getDisplayName() = "Cmd Widget"
    override fun createComponent(): JComponent = panel ?: CmdWidgetSettingsPanel().also { panel = it }
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

internal class CmdWidgetSettingsPanel : JPanel(BorderLayout(0, 8)) {
    private val model = DefaultListModel<CmdWidgetConfiguration>()
    private val list = JBList(model)
    private val projects = JComboBox<Project>()
    private val editors = mutableSetOf<WidgetEditorDialog>()

    init {
        val header = JPanel(BorderLayout(0, 8)).apply {
            add(JBLabel("Global definitions shared by all open projects. Changes take effect on Apply / OK."), BorderLayout.NORTH)
            add(JPanel(BorderLayout(8, 0)).apply {
                add(JBLabel("Project for command tests:"), BorderLayout.WEST)
                add(projects, BorderLayout.CENTER)
            }, BorderLayout.SOUTH)
        }
        ProjectManager.getInstance().openProjects.filter { !it.isDefault && !it.isDisposed }.forEach(projects::addItem)
        projects.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): Component =
                super.getListCellRendererComponent(list, (value as? Project)?.let { "${it.name} — ${it.basePath ?: "no root"}" } ?: "No open project", index, selected, focus)
        }
        list.selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): Component {
                val definition = value as? CmdWidgetConfiguration
                return super.getListCellRendererComponent(list, definition?.let {
                    "${if (it.enabled) "Enabled" else "Disabled"}  |  ${it.name}  |  ${it.refreshIntervalSeconds} s"
                }, index, selected, focus).apply { (this as javax.swing.JLabel).putClientProperty("html.disable", true) }
            }
        }
        val actions = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT))
        fun button(label: String, needsSelection: Boolean = false, action: () -> Unit): JButton = JButton(label).also { button ->
            actions.add(button)
            button.addActionListener { action() }
            if (needsSelection) {
                button.isEnabled = false
                list.addListSelectionListener { button.isEnabled = list.selectedIndex >= 0 }
            }
        }
        button("Add") { edit(null) }
        button("Edit", true) { edit(list.selectedValue) }
        button("Remove", true) { model.remove(list.selectedIndex) }
        button("Enable / Disable", true) {
            val index = list.selectedIndex
            model[index] = model[index].copy(enabled = !model[index].enabled)
        }
        button("Move up", true) { move(-1) }
        button("Move down", true) { move(1) }
        add(header, BorderLayout.NORTH)
        add(JBScrollPane(list), BorderLayout.CENTER)
        add(actions, BorderLayout.SOUTH)
    }

    fun definitions() = (0 until model.size()).map(model::get)
    fun reset(definitions: List<CmdWidgetConfiguration>) {
        model.clear()
        definitions.forEach(model::addElement)
    }
    private fun move(delta: Int) {
        val index = list.selectedIndex
        val target = index + delta
        if (index < 0 || target !in 0 until model.size()) return
        val value = model.remove(index)
        model.add(target, value)
        list.selectedIndex = target
    }
    private fun edit(existing: CmdWidgetConfiguration?) {
        val project = (projects.selectedItem as? Project)?.takeUnless { it.isDisposed || it.isDefault }
        val definition = existing ?: CmdWidgetConfiguration(UUID.randomUUID().toString(), "", "")
        val dialog = WidgetEditorDialog(project, definition)
        editors += dialog
        try {
            if (dialog.showAndGet()) {
                val edited = dialog.editor.configuration()
                val index = definitions().indexOfFirst { it.id == edited.id }
                if (index < 0) model.addElement(edited) else model[index] = edited
                list.selectedIndex = if (index < 0) model.size() - 1 else index
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
