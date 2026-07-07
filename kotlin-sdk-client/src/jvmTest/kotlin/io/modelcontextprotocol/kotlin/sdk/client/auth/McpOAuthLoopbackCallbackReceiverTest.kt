package io.modelcontextprotocol.kotlin.sdk.client.auth

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class McpOAuthLoopbackCallbackReceiverTest {
    private val httpClient = HttpClient.newHttpClient()

    @Test
    fun `should receive loopback authorization callback and verify state`() = runTest {
        val receiver = startMcpOAuthLoopbackCallbackReceiver()

        val response = get("${receiver.redirectUri}?code=code-123&state=state-123")
        val callback = receiver.awaitCallback(expectedState = "state-123", timeoutMillis = 1_000)

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("Authorization complete"))
        assertEquals("code-123", callback.code)
        assertEquals("state-123", callback.state)
    }

    @Test
    fun `should receive loopback authorization callback for prepared flow`() = runTest {
        val receiver = startMcpOAuthLoopbackCallbackReceiver(path = "/oauth/callback")
        val preparedFlow = McpOAuthPreparedAuthorizationCodeFlow(
            discovery = McpOAuthDiscoveryResult(
                resourceMetadata = OAuthProtectedResourceMetadata(raw = buildJsonObject {}),
                authorizationServerMetadata = OAuthAuthorizationServerMetadata(raw = buildJsonObject {}),
            ),
            resource = "https://mcp.example.com/mcp",
            authorizationUrl = "https://auth.example.com/authorize",
            redirectUri = receiver.redirectUri,
            pkce = McpOAuthPkce(codeVerifier = "verifier", codeChallenge = "challenge"),
            clientCredentials = McpOAuthClientCredentials("client"),
            tokenEndpoint = "https://auth.example.com/token",
            tokenEndpointAuthMethod = McpOAuthTokenEndpointAuthMethod.None,
            state = "state-123",
        )

        get("${receiver.redirectUri}?code=code-123&state=state-123")
        val callback = receiver.awaitCallback(preparedFlow = preparedFlow, timeoutMillis = 1_000)

        assertEquals("code-123", callback.code)
    }

    @Test
    fun `should reject loopback callback state mismatch`() = runTest {
        val receiver = startMcpOAuthLoopbackCallbackReceiver()

        get("${receiver.redirectUri}?code=code-123&state=wrong-state")

        assertFailsWith<McpOAuthException> {
            receiver.awaitCallback(expectedState = "state-123", timeoutMillis = 1_000)
        }
    }

    @Test
    fun `should reject callback path prefix matches`() = runTest {
        val receiver = startMcpOAuthLoopbackCallbackReceiver(path = "/callback")

        val response = get("${receiver.redirectUri}/extra?code=code-123&state=state-123")

        assertEquals(404, response.statusCode())
        assertFailsWith<TimeoutCancellationException> {
            receiver.awaitCallback(expectedState = "state-123", timeoutMillis = 100)
        }
    }

    @Test
    fun `should reject non loopback callback host`() {
        assertFailsWith<IllegalArgumentException> {
            startMcpOAuthLoopbackCallbackReceiver(host = "0.0.0.0")
        }
    }

    @Test
    fun `should reject callback path with query`() {
        assertFailsWith<IllegalArgumentException> {
            startMcpOAuthLoopbackCallbackReceiver(path = "/callback?x=1")
        }
    }

    private fun get(url: String): HttpResponse<String> = httpClient.send(
        HttpRequest.newBuilder(URI.create(url)).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )
}
