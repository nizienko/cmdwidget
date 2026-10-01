package com.github.nizienko.cmdwidget.shared

import com.intellij.ide.settings.RemoteSettingInfo
import com.intellij.ide.settings.RemoteSettingInfoProvider
import com.intellij.openapi.components.PersistentStateComponentWithModificationTracker
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Frontend edits this component; the platform synchronizes its state to the backend. */
@Service(Service.Level.APP)
@State(name = "CmdWidgetSettings", storages = [Storage("cmd-widget.xml")])
class CmdWidgetSettingsService : PersistentStateComponentWithModificationTracker<CmdWidgetSettingsService.SettingsState> {
    class SettingsState {
        // Keep saved settings non-default even after all starter widgets are removed.
        var initialized: Boolean = false
        var widgets: MutableList<Definition> = mutableListOf()
    }

    /** XML beans have defaults for fields absent in older configurations. */
    class Definition {
        var id: String = UUID.randomUUID().toString()
        var name: String = ""
        var command: String = ""
        var refreshIntervalSeconds: Int = 10
        var presentation: String = PresentationType.TEXT.name
        var enabled: Boolean = true
        var executionTarget: String = ExecutionTarget.BACKEND.name
        var workingDirectory: String = ""

        fun configuration(): CmdWidgetConfiguration? {
            val type = PresentationType.entries.firstOrNull { it.name == presentation } ?: return null
            val target = ExecutionTarget.entries.firstOrNull { it.name == executionTarget } ?: return null
            return CmdWidgetConfiguration(id, name, command, refreshIntervalSeconds, type, enabled, target, workingDirectory)
                .takeIf { it.validationError() == null }
        }
    }

    private val definitions = MutableStateFlow<List<CmdWidgetConfiguration>>(emptyList())
    val effectiveDefinitions = definitions.asStateFlow()
    private var modificationCount = 0L

    @Synchronized
    override fun getState(): SettingsState = SettingsState().also { state ->
        state.initialized = true
        state.widgets = definitions.value.map { configuration ->
            Definition().apply {
                id = configuration.id
                name = configuration.name
                command = configuration.command
                refreshIntervalSeconds = configuration.refreshIntervalSeconds
                presentation = configuration.presentation.name
                enabled = configuration.enabled
                executionTarget = configuration.executionTarget.name
                workingDirectory = configuration.workingDirectory
            }
        }.toMutableList()
    }

    @Synchronized
    override fun loadState(state: SettingsState) {
        val seen = hashSetOf<String>()
        publish(state.widgets.mapNotNull { it.configuration() }.filter { seen.add(it.id) })
    }

    /** Seed only absent settings; a saved empty list means the user removed all widgets. */
    @Synchronized
    override fun noStateLoaded() {
        publish(starterDefinitions(System.getProperty("os.name")))
    }

    companion object {
        /** Defaults follow the settings host; saved commands are never rewritten. */
        fun starterDefinitions(operatingSystem: String): List<CmdWidgetConfiguration> {
            if (HostShell.forOperatingSystem(operatingSystem) == HostShell.WINDOWS) {
                fun powershell(script: String) = "powershell.exe -NoLogo -NoProfile -NonInteractive -Command \"" +
                    "[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(); $script\""
                return listOf(
                    CmdWidgetConfiguration("default-time", "Time", powershell("Get-Date -Format 'HH:mm'"), 10),
                    CmdWidgetConfiguration("default-project", "Project", powershell("Split-Path -Leaf (Get-Location).Path"), 60),
                    CmdWidgetConfiguration("default-disk-usage", "Disk usage", powershell(
                        "\$d = Get-PSDrive -Name (Get-Item .).PSDrive.Name; " +
                            "[string][math]::Round(100 * \$d.Used / (\$d.Used + \$d.Free)) + '%'"), 60),
                )
            }
            return listOf(
                CmdWidgetConfiguration("default-time", "Time", "date '+%H:%M'", 10),
                CmdWidgetConfiguration("default-project", "Project", "basename \"\$PWD\"", 60),
                CmdWidgetConfiguration("default-disk-usage", "Disk usage", "df -P . | awk 'NR == 2 {print \$5}'", 60),
            )
        }
    }

    /** Apply is atomic: invalid drafts never change saved or running definitions. */
    @Synchronized
    fun replaceDefinitions(requested: List<CmdWidgetConfiguration>) {
        require(requested.all { it.validationError() == null }) { "Invalid widget definition" }
        require(requested.map { it.id }.distinct().size == requested.size) { "Duplicate widget IDs" }
        publish(requested.toList())
    }

    private fun publish(requested: List<CmdWidgetConfiguration>) {
        if (requested == definitions.value) return
        modificationCount++
        definitions.value = requested
    }

    @Synchronized
    override fun getStateModificationCount(): Long = modificationCount
}

class CmdWidgetRemoteSettingInfoProvider : RemoteSettingInfoProvider {
    override fun getRemoteSettingsInfo() = mapOf(
        "CmdWidgetSettings" to RemoteSettingInfo(RemoteSettingInfo.Direction.InitialFromFrontend),
    )
}
