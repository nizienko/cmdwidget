package com.github.nizienko.cmdwidget.frontend

import com.github.nizienko.cmdwidget.shared.WidgetState

internal data class WidgetDetails(val name: String, val command: String, val parameters: List<Pair<String, String>>)

internal data class WidgetPresentation(
    val id: String, val text: String, val tooltip: String, val details: WidgetDetails? = null,
)

internal object TextPresentation {
    fun format(state: WidgetState, connected: Boolean = true): WidgetPresentation {
        val success = state.lastSuccessfulResult
        val latest = state.latestResult
        val stale = success != null && (!connected || latest?.successful == false)
        val value = when {
            success != null -> normalize(success.stdout).ifEmpty { "(empty)" }
            !connected -> "(disconnected)"
            latest != null && !latest.successful -> "(error)"
            else -> "…"
        }
        return WidgetPresentation(
            state.configuration.id,
            "$value${if (stale) " [stale]" else ""}",
            "${normalize(state.configuration.name)}\n${(latest ?: success)?.let { normalize(it.stdout).ifEmpty { "(empty)" } } ?: value}",
            WidgetDetails(
                normalize(state.configuration.name),
                normalize(state.configuration.command, 120),
                buildList {
                    add("Run on" to state.configuration.executionTarget.name.lowercase().replaceFirstChar { it.uppercase() })
                    add("Refresh" to "Every ${state.configuration.refreshIntervalSeconds} s")
                    add("Status" to when {
                        !connected -> "Disconnected"
                        state.refreshing -> "Refreshing"
                        stale -> "Stale: last refresh failed"
                        latest == null -> "Pending"
                        latest.successful -> "Success"
                        else -> "Error"
                    })
                    (latest ?: success)?.let { result ->
                        add("Host" to normalize(result.context.host, 80))
                        add("Directory" to normalize(result.context.workingDirectory ?: "Unavailable", 100))
                        add("Shell" to normalize(result.context.shell, 80))
                        add("Exit code" to (result.exitCode?.toString() ?: "None"))
                        add("Duration" to "${result.durationMillis} ms")
                    }
                },
            ),
        )
    }

    fun normalize(raw: String): String = normalize(raw, 80)

    fun normalize(raw: String, limit: Int): String = ellipsize(
        clean(raw).replace(Regex("[\\s\\p{Z}]+"), " ").trim(), limit,
    )

    private fun ellipsize(value: String, limit: Int): String {
        if (value.codePointCount(0, value.length) <= limit) return value
        return value.substring(0, value.offsetByCodePoints(0, limit - 1)) + "…"
    }

    /** CSI and terminal strings (OSC/DCS/SOS/PM/APC), including C1 forms. */
    private fun clean(raw: String): String = buildString {
        var i = 0
        while (i < raw.length) {
            var char = raw[i++]
            if (char == '\u001b') {
                if (i == raw.length) break
                char = raw[i++]
                if (char !in "[]PX^_") {
                    // Other ESC sequences can include intermediate bytes before their final byte.
                    while (char in ' '..'/' && i < raw.length) char = raw[i++]
                    continue
                }
            } else if (char !in "\u009b\u009d\u0090\u0098\u009e\u009f") {
                if (!char.isISOControl() || char == '\n' || char == '\r' || char == '\t') append(char)
                continue
            }
            if (char == '[' || char == '\u009b') {
                while (i < raw.length && raw[i++] !in '@'..'~') { /* consume CSI */ }
            } else {
                while (i < raw.length) {
                    val next = raw[i++]
                    if (next == '\u0007' || next == '\u009c') break
                    if (next == '\u001b' && i < raw.length && raw[i] == '\\') { i++; break }
                }
            }
        }
    }

}
