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
        assertEquals("…", TextPresentation.format(state()).text)
        assertEquals("(empty)", TextPresentation.format(state(result(" \n"))).text)
        val error = result("bad", 7)
        assertEquals("(error)", TextPresentation.format(state(latest = error)).text)
        assertEquals("connected", TextPresentation.format(state(result("connected")).copy(refreshing = true)).text)
        assertEquals("VPN\nconnected", TextPresentation.format(state(result("connected")).copy(refreshing = true)).tooltip)
    }

    @Test fun `failure and disconnection mark retained success stale until new success`() {
        val success = result("connected")
        val failed = state(success, result("", 1))
        assertEquals("connected [stale]", TextPresentation.format(failed).text)
        assertEquals("VPN\n(empty)", TextPresentation.format(failed.copy(refreshing = true)).tooltip)
        assertEquals("connected [stale]", TextPresentation.format(state(success), connected = false).text)
        assertEquals("(disconnected)", TextPresentation.format(state(), connected = false).text)
        assertEquals("connected", TextPresentation.format(state(success)).text)
        assertEquals("(empty)", TextPresentation.format(state(result(""))).text)
    }

    @Test fun `tooltip contains name and latest output on separate lines`() {
        val success = result("connected")
        assertEquals("VPN\n…", TextPresentation.format(state()).tooltip)
        assertEquals("VPN\nconnected", TextPresentation.format(state(success)).tooltip)
        assertEquals("VPN\ndiagnostic", TextPresentation.format(state(success, result("diagnostic", 2))).tooltip)
        assertEquals("VPN\nfirst second", TextPresentation.format(state(result("first\nsecond"))).tooltip)
        val renamed = state(success).copy(configuration = definition.copy(name = "Renamed VPN"))
        assertEquals("Renamed VPN\nconnected", TextPresentation.format(renamed, connected = false).tooltip)
        assertEquals("connected", TextPresentation.format(renamed).text)
    }

    @Test fun `popup bounds command preview and includes configuration and execution parameters`() {
        val configured = state(result("connected")).copy(configuration = definition.copy(
            command = "echo\n" + "🙂".repeat(150), refreshIntervalSeconds = 17,
        ))
        val details = TextPresentation.format(configured).details!!
        assertEquals("VPN", details.name)
        assertEquals(120, details.command.codePointCount(0, details.command.length))
        assertTrue(details.command.endsWith("…"))
        assertFalse(details.command.contains('\n'))
        assertFalse(details.command.contains('\ufffd'))
        val parameters = details.parameters.toMap()
        assertEquals("Every 17 s", parameters["Refresh"])
        assertEquals("Backend", parameters["Run on"])
        assertEquals("Success", parameters["Status"])
        assertEquals("backend-host", parameters["Host"])
        assertEquals("/repo", parameters["Directory"])
        assertEquals("0", parameters["Exit code"])
    }
}
