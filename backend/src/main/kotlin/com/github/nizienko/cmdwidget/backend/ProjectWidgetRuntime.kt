package com.github.nizienko.cmdwidget.backend

import com.github.nizienko.cmdwidget.shared.CmdWidgetConfiguration
import com.github.nizienko.cmdwidget.shared.CommandResult
import com.github.nizienko.cmdwidget.shared.ExecutionContext
import com.github.nizienko.cmdwidget.shared.ProjectWidgetState
import com.github.nizienko.cmdwidget.shared.WidgetState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One owner per project; reconnecting consumers only observe [state]. */
internal class ProjectWidgetRuntime(
    parentScope: CoroutineScope,
    private val context: () -> ExecutionContext,
    private val execute: suspend (String, ExecutionContext) -> CommandResult,
) {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime + Dispatchers.IO)
    private val reconciliation = Mutex()
    private val monitor = Any()
    private val mutableState = MutableStateFlow(ProjectWidgetState())
    val state = mutableState.asStateFlow()
    private var entries = linkedMapOf<String, Entry>()
    private var revision = 0L

    private class Entry(var state: WidgetState, var job: Job? = null)

    suspend fun reconcile(definitions: List<CmdWidgetConfiguration>) = reconciliation.withLock {
        val requested = definitions.toList()
        require(requested.all { it.validationError() == null }) { "Invalid widget definition" }
        require(requested.map { it.id }.distinct().size == requested.size) { "Duplicate widget IDs" }
        val retiring = mutableListOf<Job>()
        val starting = mutableListOf<Entry>()
        synchronized(monitor) {
            check(lifetime.isActive) { "Project runtime is closed" }
            val previous = entries
            entries = linkedMapOf()
            for (definition in requested) {
                val old = previous[definition.id]
                val before = old?.state?.configuration
                val restart = before == null || before.command != definition.command ||
                    before.refreshIntervalSeconds != definition.refreshIntervalSeconds ||
                    before.enabled != definition.enabled
                val entry = if (!restart) {
                    old.apply { state = state.copy(configuration = definition) }
                } else {
                    old?.job?.let(retiring::add)
                    val keepResult = before?.command == definition.command
                    Entry(WidgetState(
                        configuration = definition,
                        revision = ++revision,
                        lastSuccessfulResult = old?.state?.lastSuccessfulResult?.takeIf { keepResult },
                        latestResult = old?.state?.latestResult?.takeIf { keepResult },
                    )).also { if (definition.enabled) starting.add(it) }
                }
                entries[definition.id] = entry
            }
            previous.filterKeys { it !in entries }.values.mapNotNullTo(retiring) { it.job }
            publish()
        }
        // Invalidate entries before cancellation; await cleanup before replacements start.
        // Cancel all first so independent commands clean up concurrently.
        withContext(NonCancellable) {
            retiring.forEach { it.cancel() }
            retiring.forEach { it.join() }
            synchronized(monitor) {
                for (entry in starting) {
                    entry.job = scope.launch(start = CoroutineStart.LAZY) { refresh(entry) }
                    entry.job!!.start()
                }
            }
        }
    }

    private suspend fun refresh(entry: Entry) {
        while (true) {
            val definition = synchronized(monitor) {
                if (entries[entry.state.configuration.id] !== entry) return
                entry.state = entry.state.copy(refreshing = true)
                publish()
                entry.state.configuration
            }
            val result = execute(definition.command, context())
            synchronized(monitor) {
                if (entries[definition.id] !== entry) return
                entry.state = entry.state.copy(
                    refreshing = false,
                    latestResult = result,
                    lastSuccessfulResult = if (result.successful) result else entry.state.lastSuccessfulResult,
                )
                publish()
            }
            delay(definition.refreshIntervalSeconds.toLong() * 1_000)
        }
    }

    /** Called while holding monitor. The version also changes for rename/order edits. */
    private fun publish() {
        mutableState.value = ProjectWidgetState(mutableState.value.version + 1, entries.values.map { it.state })
    }

    suspend fun close() { lifetime.cancelAndJoin() }
}
