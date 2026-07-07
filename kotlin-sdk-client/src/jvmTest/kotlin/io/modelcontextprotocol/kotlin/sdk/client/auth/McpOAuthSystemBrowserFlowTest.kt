package io.modelcontextprotocol.kotlin.sdk.client.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import java.net.http.HttpClient as JavaHttpClient

class McpOAuthSystemBrowserFlowTest {
    private val javaHttpClient = JavaHttpClient.newHttpClient()

    @Test
    fun `should complete system browser authorization code flow`() = runTest {
        val requestedUrls = mutableListOf<String>()
        var tokenForm: FormDataContent? = null
        var openedAuthorizationUrl: String? = null
        val httpClient = HttpClient(
            MockEngine { request ->
                requestedUrls += request.url.toString()
                when (request.url.toString()) {
                    "https://mcp.example.com/.well-known/oauth-protected-resource/mcp" -> respondJson(
                        """
                        {
                          "resource": "https://mcp.example.com/mcp",
                          "authorization_servers": ["https://auth.example.com"],
                          "scopes_supported": ["tools:call"]
                        }
                        """.trimIndent(),
                    )

                    "https://auth.example.com/.well-known/oauth-authorization-server" -> respondJson(
                        """
                        {
                          "issuer": "https://auth.example.com",
                          "authorization_endpoint": "https://auth.example.com/authorize",
                          "token_endpoint": "https://auth.example.com/token",
                          "code_challenge_methods_supported": ["S256"],
                          "token_endpoint_auth_methods_supported": ["none"]
                        }
                        """.trimIndent(),
                    )

                    "https://auth.example.com/token" -> {
                        tokenForm = request.body as FormDataContent
                        respondJson(
                            """
                            {
                              "access_token": "access-123",
                              "token_type": "Bearer",
                              "expires_in": 600,
                              "refresh_token": "refresh-123",
                              "scope": "tools:call"
                            }
                            """.trimIndent(),
                        )
                    }

                    else -> error("Unexpected URL: ${request.url}")
                }
            },
        )

        val result = authorizeMcpOAuthWithSystemBrowser(
            httpClient = httpClient,
            request = McpOAuthSystemBrowserAuthorizationCodeFlowRequest(
                serverUrl = "https://mcp.example.com/mcp",
                clientCredentials = McpOAuthClientCredentials("client"),
                timeoutMillis = 1_000,
            ),
            randomBytes = { size -> ByteArray(size) { 1 } },
            currentEpochSeconds = { 1_000L },
            openAuthorizationUrl = { authorizationUrl ->
                openedAuthorizationUrl = authorizationUrl
                val parsed = Url(authorizationUrl)
                val redirectUri = requireNotNull(parsed.parameters["redirect_uri"])
                val state = requireNotNull(parsed.parameters["state"])
                get("$redirectUri?code=code-123&state=$state")
                true
            },
        )

        val authorizationUrl = Url(requireNotNull(openedAuthorizationUrl))
        assertEquals("https", authorizationUrl.protocol.name)
        assertEquals("client", authorizationUrl.parameters["client_id"])
        assertEquals("https://mcp.example.com/mcp", authorizationUrl.parameters["resource"])
        assertEquals("tools:call", authorizationUrl.parameters["scope"])
        assertNotNull(authorizationUrl.parameters["code_challenge"])
        assertNotNull(authorizationUrl.parameters["state"])
        assertEquals("code-123", result.callback.code)
        assertEquals("access-123", result.tokens.accessToken)
        assertEquals(1_000L, result.receivedAtEpochSeconds)
        assertEquals(1_600L, result.tokenStore().expiresAtEpochSeconds)
        assertEquals("authorization_code", tokenForm?.formData?.get("grant_type"))
        assertEquals("code-123", tokenForm?.formData?.get("code"))
        assertEquals("https://mcp.example.com/mcp", tokenForm?.formData?.get("resource"))
        assertEquals("client", tokenForm?.formData?.get("client_id"))
        assertEquals(
            listOf(
                "https://mcp.example.com/.well-known/oauth-protected-resource/mcp",
                "https://auth.example.com/.well-known/oauth-authorization-server",
                "https://auth.example.com/token",
            ),
            requestedUrls,
        )
    }

    @Test
    fun `should fail when system browser is unavailable`() = runTest {
        val httpClient = HttpClient(
            MockEngine { request ->
                when (request.url.toString()) {
                    "https://mcp.example.com/.well-known/oauth-protected-resource/mcp" -> respondJson(
                        """
                        {
                          "resource": "https://mcp.example.com/mcp",
                          "authorization_servers": ["https://auth.example.com"]
                        }
                        """.trimIndent(),
                    )

                    "https://auth.example.com/.well-known/oauth-authorization-server" -> respondJson(
                        """
                        {
                          "issuer": "https://auth.example.com",
                          "authorization_endpoint": "https://auth.example.com/authorize",
                          "token_endpoint": "https://auth.example.com/token",
                          "code_challenge_methods_supported": ["S256"],
                          "token_endpoint_auth_methods_supported": ["none"]
                        }
                        """.trimIndent(),
                    )

                    else -> error("Unexpected URL: ${request.url}")
                }
            },
        )

        assertFailsWith<McpOAuthException> {
            authorizeMcpOAuthWithSystemBrowser(
                httpClient = httpClient,
                request = McpOAuthSystemBrowserAuthorizationCodeFlowRequest(
                    serverUrl = "https://mcp.example.com/mcp",
                    clientCredentials = McpOAuthClientCredentials("client"),
                ),
                randomBytes = { size -> ByteArray(size) { 1 } },
                currentEpochSeconds = { 1_000L },
                openAuthorizationUrl = { false },
            )
        }
    }

    private fun get(url: String): HttpResponse<String> = javaHttpClient.send(
        HttpRequest.newBuilder(URI.create(url)).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun MockRequestHandleScope.respondJson(content: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(
            content = content,
            status = status,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
}
