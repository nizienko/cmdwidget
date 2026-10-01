package com.github.nizienko.cmdwidget.frontend

import com.github.nizienko.cmdwidget.shared.CmdWidgetConfiguration
import com.github.nizienko.cmdwidget.shared.CommandResult
import com.github.nizienko.cmdwidget.shared.ExecutionTarget
import com.intellij.openapi.Disposable
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

internal class WidgetEditorPanel(
    private val original: CmdWidgetConfiguration,
    private val session: CommandTestSession?,
) : JPanel(BorderLayout(0, 8)), Disposable {
    internal val name = JBTextField(original.name, 35)
    internal val command = JBTextArea(original.command, 3, 45)
    internal val workingDirectory = JBTextField(original.workingDirectory, 45).apply {
        toolTipText = "Literal path on the selected host. Spaces need no quotes; ~ and shell variables are not expanded."
    }
    internal val interval = JBTextField(original.refreshIntervalSeconds.toString(), 10)
    internal val enabledBox = JBCheckBox("Enabled", original.enabled)
    internal val executionTarget = JComboBox(ExecutionTarget.entries.toTypedArray()).apply {
        selectedItem = original.executionTarget
    }
    internal val testButton = JButton("Test command")
    internal val cancelButton = JButton("Cancel test")
    internal val output = JBTextArea(9, 45).apply { isEditable = false }
    private val context = JBTextArea(4, 45).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    private var usableContext = false
    private var running = false
    private var contextGeneration = 0L

    init {
        val fields = JPanel(GridBagLayout())
        fun row(index: Int, label: String, component: JComponent) {
            fields.add(JBLabel(label), GridBagConstraints().apply {
                gridx = 0; gridy = index; anchor = GridBagConstraints.NORTHWEST
                insets = java.awt.Insets(4, 0, 4, 10)
            })
            fields.add(component, GridBagConstraints().apply {
                gridx = 1; gridy = index; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
                insets = java.awt.Insets(4, 0, 4, 0)
            })
        }
        row(0, "Name", name)
        row(1, "Command", JBScrollPane(command))
        row(2, "Refresh interval (seconds)", interval)
        row(3, "", enabledBox)
        row(4, "Run command on", executionTarget)
        row(5, "Working directory", workingDirectory)
        row(6, "", JBLabel("Empty: default. Relative: project root. Absolute: selected host."))
        row(7, "Execution context", JBScrollPane(context))
        val policy = JBTextArea(
            "Runs on the selected host with that user's permissions. With an empty working directory, frontend uses a local project root " +
                "or the local user's home directory when no local root is available. " +
                "Commands may have side effects. Inherits the selected host's environment; the non-interactive login shell " +
                "(-lc) may modify it. This can differ from the IDE terminal. " +
                "Timeout: 10 seconds. Output capture: 64 KiB per stream. Test does not save settings.",
        ).apply { isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false }
        val buttons = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
            add(testButton); add(cancelButton)
        }
        val bottom = JPanel(BorderLayout(0, 8)).apply {
            add(buttons, BorderLayout.NORTH)
            add(JBScrollPane(output), BorderLayout.CENTER)
            add(policy, BorderLayout.SOUTH)
        }
        add(fields, BorderLayout.NORTH)
        add(bottom, BorderLayout.CENTER)
        preferredSize = Dimension(650, 600)
        context.text = if (session == null) "No open project. Command testing is unavailable." else "Loading execution context…"
        executionTarget.addActionListener {
            session?.cancelTest()
            running = false
            output.text = ""
            loadContext()
        }
        workingDirectory.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                session?.cancelTest()
                running = false
                output.text = ""
                loadContext()
            }
        })
        command.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = updateButtons()
        })
        testButton.addActionListener {
            if (!usableContext || running || command.text.isBlank()) return@addActionListener
            running = true
            val testedCommand = command.text
            output.text = "Testing command:\n$testedCommand\n\nRunning…"
            updateButtons()
            session!!.test(testedCommand, selectedTarget(), workingDirectory.text) { result, error ->
                running = false
                output.text = "Tested command:\n$testedCommand\n\n" + (result?.let(::formatTestResult) ?: error)
                output.caretPosition = 0
                result?.let { showContext(it.context) }
                updateButtons()
            }
        }
        cancelButton.addActionListener {
            session?.cancelTest()
            running = false
            output.text = "Test cancelled. Process cleanup may still be completing on the selected host."
            updateButtons()
        }
        updateButtons()
        session?.onProjectClosed {
            usableContext = false
            running = false
            context.text = "Project closed. Command testing is unavailable."
            output.text = "Command test session closed."
            updateButtons()
        }
        loadContext()
    }

    private fun selectedTarget() = executionTarget.selectedItem as ExecutionTarget

    private fun loadContext() {
        val token = ++contextGeneration
        usableContext = false
        updateButtons()
        if (session == null) return
        context.text = "Loading execution context…"
        session.loadContext(selectedTarget(), workingDirectory.text) { value, error ->
            if (token != contextGeneration) return@loadContext
            if (value != null) showContext(value)
            else { usableContext = false; context.text = error }
            updateButtons()
        }
    }

    private fun showContext(value: com.github.nizienko.cmdwidget.shared.ExecutionContext) {
        usableContext = value.error == null && value.workingDirectory != null
        context.text = "Host: ${value.host} (${value.operatingSystem})\n" +
            "Directory: ${value.workingDirectory ?: "unavailable"}\nShell: ${value.shell}" +
            (value.error?.let { "\n$errorPrefix$it" } ?: "")
    }

    private fun updateButtons() {
        testButton.isEnabled = usableContext && !running && command.text.isNotBlank()
        cancelButton.isEnabled = running
    }

    fun validation(): ValidationInfo? {
        if (name.text.isBlank()) return ValidationInfo("Name must not be blank", name)
        if (name.text.length > 40) return ValidationInfo("Name must be at most 40 characters", name)
        if (command.text.isBlank()) return ValidationInfo("Command must not be blank", command)
        if (interval.text.toIntOrNull()?.let { it >= 1 } != true)
            return ValidationInfo("Refresh interval must be a positive whole number of seconds", interval)
        return null
    }

    fun configuration(): CmdWidgetConfiguration {
        check(validation() == null)
        return original.copy(name = name.text, command = command.text,
            refreshIntervalSeconds = interval.text.toInt(), enabled = enabledBox.isSelected,
            executionTarget = selectedTarget(), workingDirectory = workingDirectory.text)
    }

    override fun dispose() { session?.dispose() }

    companion object {
        private const val errorPrefix = "Unavailable: "
        internal fun formatTestResult(result: CommandResult): String = buildString {
            appendLine("Exit code: ${result.exitCode ?: "unavailable"}; duration: ${result.durationMillis} ms")
            appendLine("Timed out: ${result.timedOut}")
            listOfNotNull(result.contextError, result.startupError, result.executionError, result.cleanupError)
                .forEach { appendLine("Error: $it") }
            appendLine("stdout${if (result.stdoutTruncated) " [truncated at 64 KiB]" else ""}:")
            appendLine(result.stdout)
            appendLine("stderr${if (result.stderrTruncated) " [truncated at 64 KiB]" else ""}:")
            append(result.stderr)
        }
    }
}
