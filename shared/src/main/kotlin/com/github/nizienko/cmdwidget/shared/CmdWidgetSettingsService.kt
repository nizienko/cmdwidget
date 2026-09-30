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

        fun configuration(): CmdWidgetConfiguration? {
            val type = PresentationType.entries.firstOrNull { it.name == presentation } ?: return null
            return CmdWidgetConfiguration(id, name, command, refreshIntervalSeconds, type, enabled)
                .takeIf { it.validationError() == null }
        }
    }

    private val definitions = MutableStateFlow<List<CmdWidgetConfiguration>>(emptyList())
    val effectiveDefinitions = definitions.asStateFlow()
    private var modificationCount = 0L

    @Synchronized
    override fun getState(): SettingsState = SettingsState().also { state ->
        state.widgets = definitions.value.map { configuration ->
            Definition().apply {
                id = configuration.id
                name = configuration.name
                command = configuration.command
                refreshIntervalSeconds = configuration.refreshIntervalSeconds
                presentation = configuration.presentation.name
                enabled = configuration.enabled
            }
        }.toMutableList()
    }

    @Synchronized
    override fun loadState(state: SettingsState) {
        val seen = hashSetOf<String>()
        publish(state.widgets.mapNotNull { it.configuration() }.filter { seen.add(it.id) })
    }

    override fun noStateLoaded() = loadState(SettingsState())

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
