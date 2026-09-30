package com.github.nizienko.cmdwidget.frontend

import com.github.nizienko.cmdwidget.shared.WidgetState
import java.time.Instant

internal data class WidgetPresentation(val id: String, val text: String, val tooltip: String)

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
        val status = when {
            !connected -> "Disconnected from backend"
            stale -> "Stale: latest refresh failed"
            state.refreshing -> "Refreshing (or queued)"
            latest == null -> "Pending"
            latest.successful -> "Success"
            else -> "Error"
        }
        val context = (latest ?: success)?.context
        val details = buildString {
            appendLine(status)
            if (state.refreshing && stale && connected) appendLine("Retrying (or queued)")
            appendLine("Last successful update: ${success?.let { Instant.ofEpochMilli(it.completedAtEpochMillis) } ?: "never"}")
            context?.let {
                appendLine("Host: ${bounded(it.host, 256)}")
                appendLine("Directory: ${bounded(it.workingDirectory ?: "unavailable", 1024)}")
                appendLine("Shell: ${bounded(it.shell, 512)}")
            }
            latest?.let {
                appendLine("Completed: ${Instant.ofEpochMilli(it.completedAtEpochMillis)}")
                appendLine("Exit code: ${it.exitCode ?: "none"}; duration: ${it.durationMillis} ms")
                if (it.timedOut) appendLine("Timed out")
                listOf(it.contextError, it.startupError, it.executionError, it.cleanupError).filterNotNull()
                    .forEach { error -> appendLine(bounded(error, 512)) }
                appendLine("stdout${if (it.stdoutTruncated) " (capture truncated)" else ""}:")
                appendLine(bounded(it.stdout, 2048))
                appendLine("stderr${if (it.stderrTruncated) " (capture truncated)" else ""}:")
                append(bounded(it.stderr, 2048))
            }
        }
        return WidgetPresentation(
            state.configuration.id,
            "${normalize(state.configuration.name)}: $value${if (stale) " [stale]" else ""}",
            "<html><pre>${escapeHtml(details)}</pre></html>",
        )
    }

    fun normalize(raw: String): String = ellipsize(
        clean(raw).replace(Regex("[\\s\\p{Z}]+"), " ").trim(), 80,
    )

    private fun bounded(raw: String, limit: Int): String = ellipsize(clean(raw), limit)

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

    private fun escapeHtml(value: String) = value.replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
