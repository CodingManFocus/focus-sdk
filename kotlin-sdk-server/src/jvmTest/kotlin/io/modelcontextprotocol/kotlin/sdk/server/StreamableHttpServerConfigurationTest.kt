package io.modelcontextprotocol.kotlin.sdk.server

import io.kotest.matchers.collections.shouldExist
import io.kotest.matchers.collections.shouldHaveSize
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

private const val MCP_SERVER_PACKAGE = "io.modelcontextprotocol.kotlin.sdk.server"
private const val RESOURCE_METADATA_URL = "https://mcp.example.com/.well-known/oauth-protected-resource/mcp"

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
                            resourceMetadataUrl = RESOURCE_METADATA_URL,
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
                            resourceMetadataUrl = RESOURCE_METADATA_URL,
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

    @Test
    fun `requireMcpOAuthBearer allows valid bearer token with required scopes`() {
        testApplication {
            application {
                routing {
                    get("/mcp") {
                        if (!call.requireMcpOAuthBearer(
                                resourceMetadataUrl = RESOURCE_METADATA_URL,
                                requiredScopes = setOf("tools:call"),
                                validator = McpOAuthBearerTokenValidator { _, accessToken ->
                                    assertEquals("valid-token", accessToken)
                                    McpOAuthBearerTokenValidationResult.Valid(
                                        scopes = setOf("tools:call", "resources:read"),
                                    )
                                },
                            )
                        ) {
                            return@get
                        }
                        call.respondText("ok")
                    }
                }
            }

            val response = client.get("/mcp") {
                header(HttpHeaders.Authorization, "Bearer valid-token")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("ok", response.body<String>())
        }
    }

    @Test
    fun `requireMcpOAuthBearer rejects missing bearer token before handler`() {
        var handlerCalled = false
        testApplication {
            application {
                routing {
                    get("/mcp") {
                        if (!call.requireMcpOAuthBearer(
                                resourceMetadataUrl = RESOURCE_METADATA_URL,
                                requiredScopes = setOf("tools:call"),
                                validator = McpOAuthBearerTokenValidator { _, _ ->
                                    McpOAuthBearerTokenValidationResult.Valid(scopes = setOf("tools:call"))
                                },
                            )
                        ) {
                            return@get
                        }
                        handlerCalled = true
                        call.respondText("ok")
                    }
                }
            }

            val response = client.get("/mcp")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(false, handlerCalled)
            assertEquals(
                """Bearer resource_metadata="https://mcp.example.com/.well-known/oauth-protected-resource/mcp", """ +
                    """scope="tools:call"""",
                response.headers[HttpHeaders.WWWAuthenticate],
            )
        }
    }

    @Test
    fun `requireMcpOAuthBearer rejects invalid bearer token`() {
        testApplication {
            application {
                routing {
                    get("/mcp") {
                        if (!call.requireMcpOAuthBearer(
                                resourceMetadataUrl = RESOURCE_METADATA_URL,
                                validator = McpOAuthBearerTokenValidator { _, accessToken ->
                                    assertEquals("bad-token", accessToken)
                                    McpOAuthBearerTokenValidationResult.Invalid(
                                        error = "invalid_token",
                                        errorDescription = "expired",
                                    )
                                },
                            )
                        ) {
                            return@get
                        }
                        call.respondText("ok")
                    }
                }
            }

            val response = client.get("/mcp") {
                header(HttpHeaders.Authorization, "Bearer bad-token")
            }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(
                """Bearer resource_metadata="https://mcp.example.com/.well-known/oauth-protected-resource/mcp", """ +
                    """error="invalid_token", error_description="expired"""",
                response.headers[HttpHeaders.WWWAuthenticate],
            )
        }
    }

    @Test
    fun `requireMcpOAuthBearer rejects valid bearer token with insufficient scopes`() {
        testApplication {
            application {
                routing {
                    get("/mcp") {
                        if (!call.requireMcpOAuthBearer(
                                resourceMetadataUrl = RESOURCE_METADATA_URL,
                                requiredScopes = setOf("tools:call", "resources:read"),
                                validator = McpOAuthBearerTokenValidator { _, _ ->
                                    McpOAuthBearerTokenValidationResult.Valid(scopes = setOf("tools:call"))
                                },
                            )
                        ) {
                            return@get
                        }
                        call.respondText("ok")
                    }
                }
            }

            val response = client.get("/mcp") {
                header(HttpHeaders.Authorization, "Bearer valid-token")
            }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals(
                """Bearer resource_metadata="https://mcp.example.com/.well-known/oauth-protected-resource/mcp", """ +
                    """scope="tools:call resources:read", error="insufficient_scope"""",
                response.headers[HttpHeaders.WWWAuthenticate],
            )
        }
    }

    @Test
    fun `validateMcpOAuthJwtClaims accepts matching audience and returns scopes`() {
        val claims = jwtClaims(
            audience = listOf("https://other.example.com", "https://mcp.example.com/mcp"),
            scope = "tools:call resources:read",
            scp = listOf("prompts:read"),
        )

        val result = validateMcpOAuthJwtClaims(
            claims = claims,
            resource = "https://mcp.example.com/mcp",
            currentEpochSeconds = 1_000,
            issuer = "https://auth.example.com",
            clockSkewSeconds = 0,
        )

        assertEquals(
            McpOAuthBearerTokenValidationResult.Valid(
                scopes = setOf("tools:call", "resources:read", "prompts:read"),
            ),
            result,
        )
    }

    @Test
    fun `validateMcpOAuthJwtClaims accepts string audience`() {
        val claims = jwtClaims(audience = "https://mcp.example.com/mcp")

        val result = validateMcpOAuthJwtClaims(
            claims = claims,
            resource = "https://mcp.example.com/mcp",
            currentEpochSeconds = 1_000,
            issuer = "https://auth.example.com",
            clockSkewSeconds = 0,
        )

        assertEquals(McpOAuthBearerTokenValidationResult.Valid(), result)
    }

    @Test
    fun `validateMcpOAuthJwtClaims rejects wrong issuer`() {
        val result = validateMcpOAuthJwtClaims(
            claims = jwtClaims(),
            resource = "https://mcp.example.com/mcp",
            currentEpochSeconds = 1_000,
            issuer = "https://other-auth.example.com",
        )

        assertEquals(
            McpOAuthBearerTokenValidationResult.Invalid(
                error = "invalid_token",
                errorDescription = "token issuer does not match",
            ),
            result,
        )
    }

    @Test
    fun `validateMcpOAuthJwtClaims rejects missing or wrong audience`() {
        val missingAudience = validateMcpOAuthJwtClaims(
            claims = buildJsonObject {
                put("iss", JsonPrimitive("https://auth.example.com"))
                put("exp", JsonPrimitive(1_100))
            },
            resource = "https://mcp.example.com/mcp",
            currentEpochSeconds = 1_000,
            issuer = "https://auth.example.com",
        )
        val wrongAudience = validateMcpOAuthJwtClaims(
            claims = jwtClaims(audience = "https://other.example.com"),
            resource = "https://mcp.example.com/mcp",
            currentEpochSeconds = 1_000,
            issuer = "https://auth.example.com",
        )

        val expected = McpOAuthBearerTokenValidationResult.Invalid(
            error = "invalid_token",
            errorDescription = "token audience does not include MCP resource",
        )
        assertEquals(expected, missingAudience)
        assertEquals(expected, wrongAudience)
    }

    @Test
    fun `validateMcpOAuthJwtClaims rejects expired and not-yet-valid tokens`() {
        val expired = validateMcpOAuthJwtClaims(
            claims = jwtClaims(expiresAt = 999),
            resource = "https://mcp.example.com/mcp",
            currentEpochSeconds = 1_000,
            clockSkewSeconds = 0,
        )
        val notYetValid = validateMcpOAuthJwtClaims(
            claims = jwtClaims(notBefore = 1_061),
            resource = "https://mcp.example.com/mcp",
            currentEpochSeconds = 1_000,
            clockSkewSeconds = 60,
        )

        assertEquals(
            McpOAuthBearerTokenValidationResult.Invalid(
                error = "invalid_token",
                errorDescription = "token is expired",
            ),
            expired,
        )
        assertEquals(
            McpOAuthBearerTokenValidationResult.Invalid(
                error = "invalid_token",
                errorDescription = "token is not yet valid",
            ),
            notYetValid,
        )
    }

    private fun jwtClaims(
        audience: Any = "https://mcp.example.com/mcp",
        expiresAt: Long = 1_100,
        notBefore: Long? = null,
        scope: String? = null,
        scp: List<String> = emptyList(),
    ) = buildJsonObject {
        put("iss", JsonPrimitive("https://auth.example.com"))
        when (audience) {
            is String -> put("aud", JsonPrimitive(audience))

            is List<*> -> putJsonArray("aud") {
                audience.forEach { add(JsonPrimitive(it as String)) }
            }
        }
        put("exp", JsonPrimitive(expiresAt))
        notBefore?.let { put("nbf", JsonPrimitive(it)) }
        scope?.let { put("scope", JsonPrimitive(it)) }
        if (scp.isNotEmpty()) {
            putJsonArray("scp") {
                scp.forEach { add(JsonPrimitive(it)) }
            }
        }
    }
}
