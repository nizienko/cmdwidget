package com.github.nizienko.cmdwidget.frontend

import com.intellij.ide.HelpTooltip
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.wm.StatusBar
import com.intellij.ui.ClickListener
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.GridLayout
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JButton

/** An independently updated command element inside the factory-owned status bar widget. */
internal class CmdTextWidget(
    val definitionId: String, label: String, tooltip: String = "", var details: WidgetDetails? = null,
    percentage: Double? = null,
) : Disposable {
    private val panel = lazy {
        AdaptiveWidgetPanel().apply {
            border = JBUI.CurrentTheme.StatusBar.Widget.border()
            text = this@CmdTextWidget.label
            this.percentage = this@CmdTextWidget.percentage
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            object : ClickListener() {
                override fun onClick(event: MouseEvent, clickCount: Int): Boolean {
                    if (event.button != MouseEvent.BUTTON1 || disposed) return false
                    showDetails()
                    return true
                }
            }.installOn(this)
        }
    }
    private var statusBar: StatusBar? = null
    private var popup: Balloon? = null
    private var disposed = false
    var percentage: Double? = percentage
        set(value) {
            field = value
            if (panel.isInitialized()) panel.value.percentage = value
        }
    var label: String = label
        set(value) {
            field = value
            if (panel.isInitialized()) panel.value.text = value
        }
    var tooltip: String = tooltip
        set(value) {
            if (field == value) return
            field = value
            if (panel.isInitialized() && !disposed) installTooltip()
        }

    val component: JComponent get() = panel.value

    fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        installTooltip()
    }

    private fun installTooltip() {
        HelpTooltip.dispose(panel.value)
        HelpTooltip()
            .setPlainTextTitle(tooltip.substringBefore('\n'))
            .setDescription(HtmlChunk.text(tooltip.substringAfter('\n', "")))
            .installOn(panel.value)
        HelpTooltip.setMasterPopupOpenCondition(panel.value) { popup == null }
    }

    private fun showDetails() {
        HelpTooltip.hide(panel.value)
        popup?.let { it.hide(); return }
        val content = createDetailsPanel()
        val balloon = JBPopupFactory.getInstance().createBalloonBuilder(content)
            .setFillColor(UIUtil.getToolTipBackground())
            .setBorderColor(JBUI.CurrentTheme.Tooltip.borderColor())
            .setShowCallout(true)
            .setCornerRadius(JBUI.scale(8))
            .setShadow(true)
            .setHideOnClickOutside(true)
            .setHideOnKeyOutside(true)
            .setHideOnAction(true)
            .setHideOnFrameResize(true)
            .setRequestFocus(true)
            .setDisposable(this)
            .createBalloon()
        popup = balloon
        balloon.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                if (popup === balloon) popup = null
            }
        })
        balloon.show(RelativePoint(panel.value, Point(panel.value.width / 2, 0)), Balloon.Position.above)
    }

    internal fun createDetailsPanel(): JPanel {
        val info = details ?: WidgetDetails(tooltip.substringBefore('\n'), "", emptyList())
        fun plainLabel(text: String) = JBLabel(text).apply {
            putClientProperty("html.disable", true)
        }
        val settings = JButton(AllIcons.General.Settings).apply {
            isContentAreaFilled = false
            isBorderPainted = false
            toolTipText = "Open Cmd Widget settings"
            accessibleContext.accessibleName = toolTipText
            addActionListener {
                popup?.hide()
                HelpTooltip.hide(panel.value)
                if (!disposed) {
                    val project = statusBar?.project?.takeUnless { it.isDisposed }
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, CmdWidgetConfigurable::class.java)
                }
            }
        }
        return JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(10))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(12)
            add(JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
                isOpaque = false
                add(plainLabel(info.name).apply { font = font.deriveFont(java.awt.Font.BOLD) }, BorderLayout.CENTER)
                add(settings, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(JPanel(GridLayout(0, 1, 0, JBUI.scale(6))).apply {
                isOpaque = false
                if (info.command.isNotEmpty()) add(plainLabel(info.command).apply {
                    font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, font.size)
                })
                info.parameters.forEach { (name, value) -> add(plainLabel("$name: $value")) }
            }, BorderLayout.CENTER)
        }
    }

    override fun dispose() {
        disposed = true
        popup?.hide()
        popup = null
        statusBar = null
        if (panel.isInitialized()) HelpTooltip.dispose(panel.value)
    }
}
