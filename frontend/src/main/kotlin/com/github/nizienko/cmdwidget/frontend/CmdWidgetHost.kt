package com.github.nizienko.cmdwidget.frontend

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.diagnostic.logger
import com.github.nizienko.cmdwidget.shared.ProjectWidgetState
import com.github.nizienko.cmdwidget.shared.ExecutionTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.intellij.util.ui.JBUI
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * One factory-owned platform widget containing independently updated command elements.
 * The platform owns installation/removal; this host owns only its Swing children.
 */
internal class CmdWidgetHost(
    private val scope: CoroutineScope? = null,
    private val observe: (suspend ((BackendStateEvent) -> Unit) -> Unit)? = null,
) : CustomStatusBarWidget {
    private val panel = JPanel().apply {
        isOpaque = false
        isVisible = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = JBUI.Borders.empty()
    }
    private var statusBar: StatusBar? = null
    private val widgets = linkedMapOf<String, CmdTextWidget>()
    private var prototypeStep = 0
    private var subscription: Job? = null
    private var session = 0L
    private var endedSession = -1L
    private var version = -1L
    private var latestState: ProjectWidgetState? = null
    private var reportedSession = -1L
    @Volatile private var disposed = false

    override fun ID(): String = ID
    override fun getComponent(): JComponent = panel

    override fun install(statusBar: StatusBar) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        check(this.statusBar == null || this.statusBar === statusBar)
        if (disposed) return
        this.statusBar = statusBar
        // Defer observation until factory installation has finished.
        ApplicationManager.getApplication().invokeLater({
            if (!disposed && this.statusBar === statusBar) {
                if (observe == null) reconcile(PROTOTYPE)
                else if (subscription == null) subscription = scope!!.launch(Dispatchers.IO) {
                    observe.invoke { event ->
                        ApplicationManager.getApplication().invokeLater({ accept(event) }, ModalityState.any())
                    }
                }.also { if (disposed) it.cancel() }
            }
        }, ModalityState.any())
    }

    fun reconcile(definitions: List<Pair<String, String>>) {
        reconcilePresentations(definitions.map { (id, text) -> WidgetPresentation(id, text, "Lifecycle prototype") })
    }

    internal fun accept(event: BackendStateEvent) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (disposed || event.session < session) return
        if (event.session > session) { session = event.session; version = -1 }
        when (event) {
            is BackendStateEvent.Snapshot -> {
                if (event.session <= endedSession) return
                if (event.state.version <= version) return
                version = event.state.version
                latestState = event.state
                render(event.state, connected = event.connected)
                if (reportedSession != session && event.state.widgets.any { it.latestResult != null }) {
                    reportedSession = session
                    logger<CmdWidgetHost>().info(
                        "Cmd Widget frontend received backend results: session=$session, version=$version, widgets=${event.state.widgets.size}",
                    )
                }
            }
            is BackendStateEvent.Disconnected -> {
                endedSession = event.session
                latestState?.let { render(it, connected = false) }
            }
        }
    }

    private fun render(state: ProjectWidgetState, connected: Boolean) {
        reconcilePresentations(state.widgets.filter { it.configuration.enabled }.map {
            TextPresentation.format(it, connected || it.configuration.executionTarget == ExecutionTarget.FRONTEND)
        })
    }

    private fun reconcilePresentations(definitions: List<WidgetPresentation>) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (disposed) return
        val bar = statusBar ?: return
        require(definitions.map { it.id }.distinct().size == definitions.size)
        val desiredIds = definitions.map { it.id }.toSet()
        for (id in widgets.keys.filter { it !in desiredIds }) {
            val widget = widgets.remove(id)!!
            panel.remove(widget.component)
            Disposer.dispose(widget)
        }
        for ((index, presentation) in definitions.withIndex()) {
            val (id, text, tooltip, details, percentage) = presentation
            val existing = widgets[id]
            val element = if (existing == null) {
                val widget = CmdTextWidget(id, text, tooltip, details, percentage)
                widgets[id] = widget
                widget.install(bar)
                panel.add(widget.component)
                widget
            } else {
                existing.details = details
                if (existing.label != text || existing.tooltip != tooltip || existing.percentage != percentage) {
                    existing.label = text
                    existing.tooltip = tooltip
                    existing.percentage = percentage
                }
                existing
            }
            panel.setComponentZOrder(element.component, index)
        }
        panel.isVisible = definitions.isNotEmpty()
        panel.revalidate()
        panel.repaint()
    }

    internal fun element(id: String): CmdTextWidget? = widgets[id.removePrefix("CmdWidget.")]

    /** Deliberately manual: exercises update, remove, and re-add without timers or commands. */
    fun cyclePrototype() {
        prototypeStep = (prototypeStep + 1) % 3
        reconcile(when (prototypeStep) {
            1 -> listOf(PROTOTYPE[0], "prototype-disk" to "Disk: updated")
            2 -> listOf(PROTOTYPE[0])
            else -> PROTOTYPE
        })
    }

    override fun dispose() {
        disposed = true
        subscription?.cancel()
        val cleanup = Runnable {
            statusBar = null
            for (widget in widgets.values) {
                Disposer.dispose(widget)
            }
            widgets.clear()
            panel.removeAll()
            panel.isVisible = false
            panel.revalidate()
            panel.repaint()
        }
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) cleanup.run()
        else application.invokeLater(cleanup, ModalityState.any())
    }

    companion object {
        const val ID = "CmdWidget.Host"
        val PROTOTYPE = listOf("prototype-git" to "Git: main", "prototype-disk" to "Disk: 126G")
    }
}
