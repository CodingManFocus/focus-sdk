package io.modelcontextprotocol.kotlin.sdk.client.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class McpOAuthStreamableHttpTest {

    @Test
    fun `should apply token store bearer auth to streamable http transport requests`() = runTest {
        val deleteSeen = CompletableDeferred<Unit>()
        val tokenStore = McpOAuthTokenStore(
            McpOAuthTokenResponse(
                accessToken = "initial-token",
                raw = buildJsonObject {},
            ),
        )
        val httpClient = HttpClient(
            MockEngine { request ->
                assertEquals("custom-value", request.headers["x-custom"])
                when (request.method) {
                    HttpMethod.Post -> {
                        assertEquals("Bearer initial-token", request.headers[HttpHeaders.Authorization])
                        val body = (request.body as TextContent).text
                        assertEquals(true, body.contains("initialize"))
                        respond(
                            content = "",
                            status = HttpStatusCode.OK,
                            headers = headersOf("mcp-session-id", "oauth-session-id"),
                        )
                    }

                    HttpMethod.Delete -> {
                        assertEquals("Bearer refreshed-token", request.headers[HttpHeaders.Authorization])
                        deleteSeen.complete(Unit)
                        respond(content = "", status = HttpStatusCode.OK)
                    }

                    else -> error("Unexpected method: ${request.method}")
                }
            },
        )
        val transport = httpClient.mcpOAuthStreamableHttpTransport(
            url = "https://mcp.example.com/mcp",
            tokenStore = tokenStore,
        ) {
            headers.append("x-custom", "custom-value")
            headers.append(HttpHeaders.Authorization, "Bearer stale-token")
        }

        transport.start()
        transport.send(JSONRPCRequest(id = "init", method = "initialize", params = buildJsonObject {}))
        tokenStore.update(
            McpOAuthTokenResponse(
                accessToken = "refreshed-token",
                raw = buildJsonObject {},
            ),
        )
        transport.terminateSession()
        withTimeout(5_000) { deleteSeen.await() }
        transport.close()
    }
}
