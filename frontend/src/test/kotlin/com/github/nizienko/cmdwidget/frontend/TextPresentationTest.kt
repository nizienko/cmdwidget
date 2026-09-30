package com.github.nizienko.cmdwidget.frontend

import com.github.nizienko.cmdwidget.shared.CmdWidgetConfiguration
import com.github.nizienko.cmdwidget.shared.CommandResult
import com.github.nizienko.cmdwidget.shared.ExecutionContext
import com.github.nizienko.cmdwidget.shared.WidgetState
import org.junit.Assert.*
import org.junit.Test

class TextPresentationTest {
    private val definition = CmdWidgetConfiguration("vpn", "VPN", "vpn status")
    private val context = ExecutionContext("backend-host", "/repo", "/bin/sh", "Linux")
    private fun result(text: String, exit: Int = 0) = CommandResult(
        context, stdout = text, exitCode = exit, completedAtEpochMillis = 1_000,
    )
    private fun state(success: CommandResult? = null, latest: CommandResult? = success) =
        WidgetState(definition, 1, lastSuccessfulResult = success, latestResult = latest)

    @Test fun `multiline color terminal strings and controls are removed`() {
        assertEquals("foo bar baz", TextPresentation.normalize("  \u001b[32mfoo\u001b[0m\n\tbar\r\n baz\u0000  "))
        assertEquals("link end", TextPresentation.normalize("\u001b]8;;https://example.com\u0007link\u001b]8;;\u001b\\ end"))
        assertEquals("ab", TextPresentation.normalize("a\u009b31mb\u009b0m\u001bPsecret\u001b\\"))
        assertEquals("a", TextPresentation.normalize("a\u001b]unterminated"))
        assertEquals("a b", TextPresentation.normalize("a\u00a0\u2003b"))
    }

    @Test fun `value is bounded to eighty unicode characters including ellipsis`() {
        val formatted = TextPresentation.normalize("🙂".repeat(100))
        assertEquals(80, formatted.codePointCount(0, formatted.length))
        assertTrue(formatted.endsWith("…"))
        assertFalse(formatted.contains('\ufffd'))
        assertEquals("x".repeat(80), TextPresentation.normalize("x".repeat(80)))
    }

    @Test fun `pending empty errors and refresh use distinct states`() {
        assertEquals("VPN: …", TextPresentation.format(state()).text)
        assertEquals("VPN: (empty)", TextPresentation.format(state(result(" \n"))).text)
        val error = result("bad", 7)
        assertEquals("VPN: (error)", TextPresentation.format(state(latest = error)).text)
        assertEquals("VPN: connected", TextPresentation.format(state(result("connected")).copy(refreshing = true)).text)
        assertTrue(TextPresentation.format(state(result("connected")).copy(refreshing = true)).tooltip.contains("Refreshing"))
    }

    @Test fun `failure and disconnection mark retained success stale until new success`() {
        val success = result("connected")
        val failed = state(success, result("", 1))
        assertEquals("VPN: connected [stale]", TextPresentation.format(failed).text)
        assertTrue(TextPresentation.format(failed.copy(refreshing = true)).tooltip.contains("Retrying"))
        assertEquals("VPN: connected [stale]", TextPresentation.format(state(success), connected = false).text)
        assertEquals("VPN: (disconnected)", TextPresentation.format(state(), connected = false).text)
        assertEquals("VPN: connected", TextPresentation.format(state(success)).text)
        assertEquals("VPN: (empty)", TextPresentation.format(state(result(""))).text)
    }

    @Test fun `diagnostics are bounded escaped and describe backend context and failures`() {
        val failed = result("<html>" + "x".repeat(65_536), 2).copy(
            stderr = "\u001b[31m<script>&\"\u001b[0m" + "y".repeat(65_536),
            stdoutTruncated = true, stderrTruncated = true, timedOut = true,
            durationMillis = 10_001, startupError = "failed <start>",
        )
        val tooltip = TextPresentation.format(state(result("connected"), failed)).tooltip
        assertTrue(tooltip.length < 8_000)
        assertTrue(tooltip.contains("&lt;html&gt;"))
        assertFalse(tooltip.contains("<script>"))
        assertFalse(tooltip.contains('\u001b'))
        listOf("backend-host", "/repo", "/bin/sh", "1970-01-01T00:00:01Z", "10001 ms", "Timed out", "capture truncated", "failed &lt;start&gt;")
            .forEach { assertTrue(it, tooltip.contains(it)) }
    }
}
