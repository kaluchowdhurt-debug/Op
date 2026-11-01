package com.example

import com.example.util.CrossPlatformAgentRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests ported from `cross_platform_ai_assistant/tests/test_agent.py`
 * and `desktop/desktop_agent.py`.
 */
class CrossPlatformAgentRouterTest {

    @Test
    fun test_unknown_command_is_handled() {
        val result = CrossPlatformAgentRouter.runCommand("not_a_real_command")
        assertTrue(result.shouldContinue)
        assertTrue(result.outputMessage.contains("Unknown command"))
    }

    @Test
    fun test_exit_returns_false() {
        val result = CrossPlatformAgentRouter.runCommand("exit")
        assertFalse(result.shouldContinue)
    }

    @Test
    fun test_open_url_rejects_non_http_scheme() {
        val result = CrossPlatformAgentRouter.runCommand("open_url file:///etc/passwd")
        assertTrue(result.shouldContinue)
        assertTrue(result.outputMessage.contains("Usage: open_url https://example.com"))
    }

    @Test
    fun test_open_url_accepts_https_scheme() {
        var openedUrl: String? = null
        val result = CrossPlatformAgentRouter.runCommand(
            line = "open_url https://example.com",
            onOpenUrl = { url ->
                openedUrl = url
                true
            }
        )
        assertTrue(result.shouldContinue)
        assertEquals("https://example.com", openedUrl)
        assertTrue(result.outputMessage.contains("Opened URL"))
    }

    @Test
    fun test_desktop_type_command_and_quotes() {
        var typedText: String? = null
        val result = CrossPlatformAgentRouter.runCommand(
            line = "type \"Hello from my assistant\"",
            onTypeText = { text ->
                typedText = text
                true
            }
        )
        assertTrue(result.shouldContinue)
        assertEquals("Hello from my assistant", typedText)
    }

    @Test
    fun test_desktop_open_app_allowlist() {
        val disallowed = CrossPlatformAgentRouter.runCommand("open_app powershell")
        assertTrue(disallowed.shouldContinue)
        assertTrue(disallowed.outputMessage.contains("Allowed apps:"))

        var openedApp: String? = null
        val allowed = CrossPlatformAgentRouter.runCommand(
            line = "open_app notepad",
            onOpenApp = { app ->
                openedApp = app
                true
            }
        )
        assertTrue(allowed.shouldContinue)
        assertEquals("notepad", openedApp)
    }
}
