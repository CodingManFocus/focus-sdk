package io.modelcontextprotocol.kotlin.sdk.server

import io.kotest.matchers.collections.shouldExist
import io.kotest.matchers.collections.shouldHaveSize
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

private const val MCP_SERVER_PACKAGE = "io.modelcontextprotocol.kotlin.sdk.server"

class StreamableHttpServerConfigurationTest {

    private fun testServer(): Server = Server(
        Implementation("test-server", "1.0"),
        ServerOptions(capabilities = ServerCapabilities()),
    )

    @Test
    fun `mcpStreamableHttp warns when ContentNegotiation is pre-installed`() {
        LogCapture(MCP_SERVER_PACKAGE).use { logs ->
            testApplication {
                application {
                    install(ServerContentNegotiation) {
                        json() // Pre-install with default (wrong) config
                    }
                    mcpStreamableHttp { testServer() }
                }
                client.get("/mcp")
            }
            logs.messages.shouldExist { "ContentNegotiation is already installed" in it }
        }
    }

    @Test
    fun `mcpStatelessStreamableHttp warns when ContentNegotiation is pre-installed`() {
        LogCapture(MCP_SERVER_PACKAGE).use { logs ->
            testApplication {
                application {
                    install(ServerContentNegotiation) {
                        json() // Pre-install with default (wrong) config
                    }
                    mcpStatelessStreamableHttp { testServer() }
                }
                client.get("/mcp")
            }
            logs.messages.shouldExist { "ContentNegotiation is already installed" in it }
        }
    }

    @Test
    fun `mcp warns when ContentNegotiation is pre-installed`() {
        LogCapture(MCP_SERVER_PACKAGE).use { logs ->
            testApplication {
                application {
                    install(ServerContentNegotiation) {
                        json() // Pre-install with default (wrong) config
                    }
                    mcp { testServer() }
                }
                client.get("/sse")
            }
            logs.messages.shouldExist { "ContentNegotiation is already installed" in it }
        }
    }

    @Test
    fun `installMcpContentNegotiation is idempotent`() {
        LogCapture(MCP_SERVER_PACKAGE).use { logs ->
            testApplication {
                application {
                    installMcpContentNegotiation()
                    installMcpContentNegotiation()
                }
                client.get("/")
            }
            logs.messages.filter { "ContentNegotiation is already installed" in it }
                .shouldHaveSize(0)
        }
    }

    @Test
    fun `installMcpContentNegotiation warns exactly once when ContentNegotiation is pre-installed`() {
        LogCapture(MCP_SERVER_PACKAGE).use { logs ->
            testApplication {
                application {
                    install(ServerContentNegotiation) {
                        json()
                    }
                    installMcpContentNegotiation()
                    installMcpContentNegotiation()
                }
                client.get("/")
            }
            logs.messages.filter { "ContentNegotiation is already installed" in it }
                .shouldHaveSize(1)
        }
    }

    @Test
    fun `mcpOAuthProtectedResourceMetadata serves protected resource metadata`() {
        testApplication {
            application {
                mcpOAuthProtectedResourceMetadata(
                    metadata = McpOAuthProtectedResourceMetadata(
                        resource = "https://mcp.example.com/mcp",
                        authorizationServers = listOf("https://auth.example.com"),
                        scopesSupported = listOf("tools:call", "resources:read"),
                    ),
                    mcpEndpointPath = "/mcp",
                )
            }

            val response = client.get("/.well-known/oauth-protected-resource/mcp")
            val json = McpJson.parseToJsonElement(response.body<String>()).jsonObject

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("https://mcp.example.com/mcp", json["resource"]?.jsonPrimitive?.content)
            assertEquals(
                listOf("https://auth.example.com"),
                json["authorization_servers"]?.jsonArray?.map { it.jsonPrimitive.content },
            )
            assertEquals(
                listOf("tools:call", "resources:read"),
                json["scopes_supported"]?.jsonArray?.map { it.jsonPrimitive.content },
            )
        }
    }

    @Test
    fun `mcpOAuthProtectedResourceMetadata requires authorization server`() {
        assertFailsWith<IllegalArgumentException> {
            McpOAuthProtectedResourceMetadata(
                resource = "https://mcp.example.com/mcp",
                authorizationServers = emptyList(),
            )
        }
    }

    @Test
    fun `respondMcpOAuthUnauthorized sends bearer challenge`() {
        testApplication {
            application {
                routing {
                    get("/mcp") {
                        call.respondMcpOAuthUnauthorized(
                            resourceMetadataUrl = "https://mcp.example.com/.well-known/oauth-protected-resource/mcp",
                            scope = "tools:call",
                            error = "invalid_token",
                        )
                    }
                }
            }

            val response = client.get("/mcp")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(
                """Bearer resource_metadata="https://mcp.example.com/.well-known/oauth-protected-resource/mcp", """ +
                    """scope="tools:call", error="invalid_token"""",
                response.headers[HttpHeaders.WWWAuthenticate],
            )
        }
    }

    @Test
    fun `respondMcpOAuthInsufficientScope sends forbidden scope challenge`() {
        testApplication {
            application {
                routing {
                    get("/mcp") {
                        call.respondMcpOAuthInsufficientScope(
                            resourceMetadataUrl = "https://mcp.example.com/.well-known/oauth-protected-resource/mcp",
                            scope = "tools:call",
                            errorDescription = """requires "tools:call"""",
                        )
                    }
                }
            }

            val response = client.get("/mcp")

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals(
                """Bearer resource_metadata="https://mcp.example.com/.well-known/oauth-protected-resource/mcp", """ +
                    """scope="tools:call", error="insufficient_scope", """ +
                    """error_description="requires \"tools:call\""""",
                response.headers[HttpHeaders.WWWAuthenticate],
            )
        }
    }
}
