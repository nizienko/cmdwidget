package com.github.nizienko.cmdwidget.frontend

import com.intellij.openapi.wm.impl.status.TextPanel
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints

/** Keeps the same component and interactions when command output changes type. */
internal class AdaptiveWidgetPanel : TextPanel() {
    var percentage: Double? = null
        set(value) {
            field = value
            revalidate()
            repaint()
        }

    override fun getPreferredSize(): Dimension = super.getPreferredSize().let {
        if (percentage == null) it else Dimension(it.width + JBUI.scale(80), it.height)
    }

    private fun barWidth(): Int = (width - insets.left - insets.right - getFontMetrics(font).stringWidth(text) - JBUI.scale(8))
        .coerceIn(0, JBUI.scale(72))

    override fun getTextX(g: Graphics): Int = if (percentage == null) super.getTextX(g)
        else insets.left + barWidth().let { if (it == 0) 0 else it + JBUI.scale(8) }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val value = percentage ?: return
        val canvas = g.create() as Graphics2D
        try {
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val barWidth = barWidth()
            val barHeight = minOf(JBUI.scale(6), height).coerceAtLeast(0)
            val y = (height - barHeight) / 2
            canvas.color = JBColor.namedColor("ProgressBar.background", JBColor(0xDADADA, 0x45494A))
            canvas.fillRoundRect(insets.left, y, barWidth, barHeight, barHeight, barHeight)
            canvas.color = JBColor.namedColor("ProgressBar.foreground", JBColor(0x3574F0, 0x548AF7))
            canvas.fillRoundRect(insets.left, y, (barWidth * value / 100).toInt(), barHeight, barHeight, barHeight)
        } finally {
            canvas.dispose()
        }
    }
}
