package com.github.nizienko.cmdwidget.backend

import com.github.nizienko.cmdwidget.shared.CmdWidgetConfiguration
import com.github.nizienko.cmdwidget.shared.CmdWidgetRemoteSettingInfoProvider
import com.github.nizienko.cmdwidget.shared.CmdWidgetSettingsService
import com.intellij.ide.settings.RemoteSettingInfo
import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import org.junit.Assert.*
import org.junit.Test

class CmdWidgetSettingsTest {
    private fun widget(id: String) = CmdWidgetConfiguration(id, id, "printf $id", 60)

    @Test fun `XML round trip preserves IDs order and disabled definitions without runtime state`() {
        val settings = CmdWidgetSettingsService()
        val definitions = listOf(widget("second").copy(enabled = false), widget("first"))
        settings.replaceDefinitions(definitions)
        val xml = XmlSerializer.serialize(settings.state)
        val restored = CmdWidgetSettingsService()
        restored.loadState(XmlSerializer.deserialize(xml, CmdWidgetSettingsService.SettingsState::class.java))
        assertEquals(definitions, restored.effectiveDefinitions.value)
        val text = com.intellij.openapi.util.JDOMUtil.writeElement(xml)
        assertFalse(text.contains("stdout"))
        assertFalse(text.contains("latestResult"))
        // Consumers cannot mutate saved data through the XML bean returned by getState.
        restored.state.widgets.clear()
        assertEquals(definitions, restored.effectiveDefinitions.value)
    }

    @Test fun `missing fields receive defaults and generated IDs become stable after saving`() {
        val xml = Element("Definition")
            .addContent(Element("option").setAttribute("name", "name").setAttribute("value", "Git"))
            .addContent(Element("option").setAttribute("name", "command").setAttribute("value", "git branch --show-current"))
        val bean = XmlSerializer.deserialize(xml, CmdWidgetSettingsService.Definition::class.java)
        val settings = CmdWidgetSettingsService()
        settings.loadState(CmdWidgetSettingsService.SettingsState().apply { widgets.add(bean) })
        val definition = settings.effectiveDefinitions.value.single()
        assertTrue(definition.id.isNotBlank())
        assertEquals(10, definition.refreshIntervalSeconds)
        assertTrue(definition.enabled)
        val restored = CmdWidgetSettingsService()
        restored.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(settings.state), CmdWidgetSettingsService.SettingsState::class.java))
        assertEquals(definition, restored.effectiveDefinitions.value.single())
    }

    @Test fun `invalid loaded entries and duplicate IDs cannot reach executors`() {
        val state = CmdWidgetSettingsService.SettingsState()
        fun add(idValue: String, change: CmdWidgetSettingsService.Definition.() -> Unit = {}) {
            state.widgets.add(CmdWidgetSettingsService.Definition().apply {
                id = idValue; name = idValue; command = "printf safe"; change()
            })
        }
        add("valid")
        add("valid") { command = "printf duplicate" }
        add("blank-command") { command = " " }
        add("blank-name") { name = "" }
        add("long-name") { name = "x".repeat(41) }
        add("interval") { refreshIntervalSeconds = 0 }
        add("unknown-presentation") { presentation = "FUTURE" }
        add("")
        add("disabled") { enabled = false }
        val settings = CmdWidgetSettingsService()
        settings.loadState(state)
        assertEquals(listOf("valid", "disabled"), settings.effectiveDefinitions.value.map { it.id })
        assertEquals("printf safe", settings.effectiveDefinitions.value.first().command)
        assertEquals(2, settings.state.widgets.size)
    }

    @Test fun `Apply validates atomically repeated Apply is unchanged and absent state clears definitions`() {
        val settings = CmdWidgetSettingsService()
        val definitions = listOf(widget("git"))
        settings.replaceDefinitions(definitions)
        val count = settings.stateModificationCount
        settings.replaceDefinitions(definitions)
        settings.loadState(settings.state)
        assertEquals(count, settings.stateModificationCount)
        for (invalid in listOf(listOf(widget("git"), widget("git")), listOf(widget("git").copy(command = "")))) {
            try {
                settings.replaceDefinitions(invalid)
                fail("Invalid Apply must fail")
            } catch (_: IllegalArgumentException) { }
            assertEquals(definitions, settings.effectiveDefinitions.value)
        }
        settings.noStateLoaded()
        assertTrue(settings.effectiveDefinitions.value.isEmpty())
        assertTrue(settings.stateModificationCount > count)
        assertEquals(RemoteSettingInfo.Direction.InitialFromFrontend,
            CmdWidgetRemoteSettingInfoProvider().getRemoteSettingsInfo().getValue("CmdWidgetSettings").direction)
    }
}
