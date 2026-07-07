package io.modelcontextprotocol.kotlin.sdk.client.auth

import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class McpOAuthSystemBrowserTest {

    @Test
    fun `should open https authorization URL with browser adapter`() {
        var openedUri: URI? = null

        val opened = openMcpOAuthAuthorizationUrlInBrowser(
            authorizationUrl = "https://auth.example.com/authorize?client_id=client",
            isBrowserSupported = { true },
            browse = { openedUri = it },
        )

        assertEquals(true, opened)
        assertEquals("https://auth.example.com/authorize?client_id=client", openedUri.toString())
    }

    @Test
    fun `should return false when system browser is unsupported`() {
        val opened = openMcpOAuthAuthorizationUrlInBrowser(
            authorizationUrl = "https://auth.example.com/authorize",
            isBrowserSupported = { false },
            browse = { error("browse should not be called") },
        )

        assertEquals(false, opened)
    }

    @Test
    fun `should reject unsafe authorization URLs`() {
        assertFailsWith<McpOAuthException> {
            openMcpOAuthAuthorizationUrlInBrowser(
                authorizationUrl = "mcp://auth.example.com/authorize",
                isBrowserSupported = { true },
                browse = { error("browse should not be called") },
            )
        }
        assertFailsWith<McpOAuthException> {
            openMcpOAuthAuthorizationUrlInBrowser(
                authorizationUrl = "http://auth.example.com/authorize",
                isBrowserSupported = { true },
                browse = { error("browse should not be called") },
            )
        }
        assertFailsWith<McpOAuthException> {
            openMcpOAuthAuthorizationUrlInBrowser(
                authorizationUrl = "http://127.0.0.1:3000/authorize",
                isBrowserSupported = { true },
                browse = { error("browse should not be called") },
            )
        }
        assertFailsWith<McpOAuthException> {
            openMcpOAuthAuthorizationUrlInBrowser(
                authorizationUrl = "https://user:pass@auth.example.com/authorize",
                isBrowserSupported = { true },
                browse = { error("browse should not be called") },
            )
        }
    }

    @Test
    fun `should wrap browser launch failures`() {
        assertFailsWith<McpOAuthException> {
            openMcpOAuthAuthorizationUrlInBrowser(
                authorizationUrl = "https://auth.example.com/authorize",
                isBrowserSupported = { true },
                browse = { throw IOException("boom") },
            )
        }
    }
}
