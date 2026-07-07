package io.modelcontextprotocol.kotlin.sdk.client.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpOAuthTest {

    @Test
    fun `should build PKCE S256 challenge from RFC vector`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            mcpPkceCodeChallengeS256(verifier),
        )
    }

    @Test
    fun `should build PKCE pair from random bytes`() {
        val randomBytes = ByteArray(32) { it.toByte() }

        val pkce = mcpPkceS256(randomBytes)

        assertEquals("S256", pkce.codeChallengeMethod)
        assertEquals(43, pkce.codeVerifier.length)
        val hasOnlyBase64UrlCharacters = pkce.codeVerifier.all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_'
        }
        assertTrue(hasOnlyBase64UrlCharacters)
        assertEquals(mcpPkceCodeChallengeS256(pkce.codeVerifier), pkce.codeChallenge)
    }

    @Test
    fun `should reject non ascii PKCE verifier characters`() {
        assertFailsWith<IllegalArgumentException> {
            mcpPkceCodeChallengeS256("e".repeat(42) + "\u00e9")
        }
    }

    @Test
    fun `should require PKCE S256 support in authorization server metadata`() {
        val metadata = OAuthAuthorizationServerMetadata(
            codeChallengeMethodsSupported = listOf("plain"),
            raw = buildJsonObject {
                putJsonArray("code_challenge_methods_supported") {
                    add(JsonPrimitive("plain"))
                }
            },
        )

        assertFailsWith<McpOAuthException> {
            requireMcpPkceS256Support(metadata)
        }
        requireMcpPkceS256Support(
            metadata.copy(
                codeChallengeMethodsSupported = listOf("S256"),
                raw = buildJsonObject {
                    putJsonArray("code_challenge_methods_supported") {
                        add(JsonPrimitive("S256"))
                    }
                },
            ),
        )
    }

    @Test
    fun `should build authorization URL with MCP resource parameter`() {
        val url = buildMcpOAuthAuthorizationUrl(
            McpOAuthAuthorizationRequest(
                authorizationEndpoint = "https://auth.example.com/authorize?prompt=consent",
                clientId = "client id",
                redirectUri = "http://127.0.0.1/callback",
                codeChallenge = "challenge",
                scope = "files:read files:write",
                resource = "https://mcp.example.com/mcp",
                state = "state value",
            ),
        )

        val parsed = Url(url)
        assertEquals("consent", parsed.parameters["prompt"])
        assertEquals("code", parsed.parameters["response_type"])
        assertEquals("client id", parsed.parameters["client_id"])
        assertEquals("http://127.0.0.1/callback", parsed.parameters["redirect_uri"])
        assertEquals("challenge", parsed.parameters["code_challenge"])
        assertEquals("S256", parsed.parameters["code_challenge_method"])
        assertEquals("files:read files:write", parsed.parameters["scope"])
        assertEquals("https://mcp.example.com/mcp", parsed.parameters["resource"])
        assertEquals("state value", parsed.parameters["state"])
    }

    @Test
    fun `should parse authorization callback and verify state`() {
        val callback = parseMcpOAuthAuthorizationCallback(
            callbackUrl = "http://127.0.0.1/callback?code=code-123&state=state-123&iss=https%3A%2F%2Fauth.example.com",
            expectedState = "state-123",
        )

        assertEquals("code-123", callback.code)
        assertEquals("state-123", callback.state)
        assertEquals(listOf("https://auth.example.com"), callback.rawParameters["iss"])
    }

    @Test
    fun `should parse authorization callback using prepared flow state`() {
        val preparedFlow = McpOAuthPreparedAuthorizationCodeFlow(
            discovery = McpOAuthDiscoveryResult(
                resourceMetadata = OAuthProtectedResourceMetadata(raw = buildJsonObject {}),
                authorizationServerMetadata = OAuthAuthorizationServerMetadata(raw = buildJsonObject {}),
            ),
            resource = "https://mcp.example.com/mcp",
            authorizationUrl = "https://auth.example.com/authorize",
            redirectUri = "http://127.0.0.1/callback",
            pkce = McpOAuthPkce(codeVerifier = "verifier", codeChallenge = "challenge"),
            clientCredentials = McpOAuthClientCredentials("client"),
            tokenEndpoint = "https://auth.example.com/token",
            tokenEndpointAuthMethod = McpOAuthTokenEndpointAuthMethod.None,
            state = "state-123",
        )

        val callback = parseMcpOAuthAuthorizationCallback(
            callbackUrl = "http://127.0.0.1/callback?code=code-123&state=state-123",
            preparedFlow = preparedFlow,
        )

        assertEquals("code-123", callback.code)
    }

    @Test
    fun `should reject authorization callback state mismatch`() {
        assertFailsWith<McpOAuthException> {
            parseMcpOAuthAuthorizationCallback(
                callbackUrl = "http://127.0.0.1/callback?code=code-123&state=wrong-state",
                expectedState = "state-123",
            )
        }
    }

    @Test
    fun `should reject authorization callback oauth error`() {
        val error = assertFailsWith<McpOAuthException> {
            parseMcpOAuthAuthorizationCallback(
                callbackUrl = "http://127.0.0.1/callback?error=access_denied&error_description=User%20cancelled",
                expectedState = "state-123",
            )
        }

        assertTrue(error.message.orEmpty().contains("access_denied"))
        assertTrue(error.message.orEmpty().contains("User cancelled"))
    }

    @Test
    fun `should reject authorization callback without code`() {
        assertFailsWith<McpOAuthException> {
            parseMcpOAuthAuthorizationCallback(
                callbackUrl = "http://127.0.0.1/callback?state=state-123",
                expectedState = "state-123",
            )
        }
    }

    @Test
    fun `should select token endpoint auth method from metadata`() {
        val metadata = OAuthAuthorizationServerMetadata(
            tokenEndpointAuthMethodsSupported = listOf("unsupported", "none", "client_secret_basic"),
            raw = buildJsonObject {},
        )

        assertEquals(
            McpOAuthTokenEndpointAuthMethod.ClientSecretBasic,
            selectMcpOAuthTokenEndpointAuthMethod(metadata, clientSecret = "secret"),
        )
        assertEquals(
            McpOAuthTokenEndpointAuthMethod.None,
            selectMcpOAuthTokenEndpointAuthMethod(metadata, clientSecret = null),
        )
        assertEquals(
            McpOAuthTokenEndpointAuthMethod.PrivateKeyJwt,
            selectMcpOAuthTokenEndpointAuthMethod(
                OAuthAuthorizationServerMetadata(
                    tokenEndpointAuthMethodsSupported = listOf("private_key_jwt"),
                    raw = buildJsonObject {},
                ),
                clientSecret = null,
                clientAssertionProvider = McpOAuthClientAssertionProvider { "signed-jwt" },
            ),
        )
        assertEquals(
            McpOAuthTokenEndpointAuthMethod.None,
            selectMcpOAuthTokenEndpointAuthMethod(
                OAuthAuthorizationServerMetadata(
                    tokenEndpointAuthMethodsSupported = listOf("none"),
                    raw = buildJsonObject {},
                ),
                clientSecret = "secret",
            ),
        )
        assertEquals(
            McpOAuthTokenEndpointAuthMethod.ClientSecretBasic,
            selectMcpOAuthTokenEndpointAuthMethod(
                OAuthAuthorizationServerMetadata(raw = buildJsonObject {}),
                clientSecret = "secret",
            ),
        )
        assertEquals(
            McpOAuthTokenEndpointAuthMethod.ClientSecretBasic,
            tokenRequest().tokenEndpointAuthMethod,
        )
        assertEquals(
            McpOAuthTokenEndpointAuthMethod.ClientSecretBasic,
            refreshRequest().tokenEndpointAuthMethod,
        )
        assertFailsWith<McpOAuthException> {
            selectMcpOAuthTokenEndpointAuthMethod(
                OAuthAuthorizationServerMetadata(
                    tokenEndpointAuthMethodsSupported = listOf("private_key_jwt"),
                    raw = buildJsonObject {},
                ),
                clientSecret = "secret",
            )
        }
    }

    @Test
    fun `should declare oauth client credentials extension capability`() {
        val capabilities = ClientCapabilities().withMcpOAuthClientCredentialsExtension()

        assertEquals(
            setOf(MCP_OAUTH_CLIENT_CREDENTIALS_EXTENSION),
            capabilities.extensions?.keys,
        )
        assertEquals(buildJsonObject {}, capabilities.extensions?.get(MCP_OAUTH_CLIENT_CREDENTIALS_EXTENSION))
    }

    @Test
    fun `should build client id metadata document json`() {
        val json = buildMcpOAuthClientIdMetadataDocumentJson(
            McpOAuthClientIdMetadataDocument(
                clientId = "https://app.example.com/oauth/client-metadata.json",
                clientName = "Example MCP Client",
                redirectUris = listOf("http://127.0.0.1/callback"),
                clientUri = "https://app.example.com",
                logoUri = "https://app.example.com/logo.png",
                jwksUri = "https://app.example.com/jwks.json",
                extraFields = buildJsonObject {
                    put("contacts", JsonPrimitive("ops@example.com"))
                    put("client_name", JsonPrimitive("overridden"))
                },
            ),
        )

        assertEquals("https://app.example.com/oauth/client-metadata.json", json["client_id"]?.jsonPrimitive?.content)
        assertEquals("Example MCP Client", json["client_name"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("http://127.0.0.1/callback"),
            json["redirect_uris"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
        assertEquals("none", json["token_endpoint_auth_method"]?.jsonPrimitive?.content)
        assertEquals(listOf("authorization_code"), json["grant_types"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals(listOf("code"), json["response_types"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals("https://app.example.com", json["client_uri"]?.jsonPrimitive?.content)
        assertEquals("https://app.example.com/logo.png", json["logo_uri"]?.jsonPrimitive?.content)
        assertEquals("https://app.example.com/jwks.json", json["jwks_uri"]?.jsonPrimitive?.content)
        assertEquals("ops@example.com", json["contacts"]?.jsonPrimitive?.content)
    }

    @Test
    fun `should detect client id metadata document support`() {
        assertEquals(
            true,
            supportsMcpOAuthClientIdMetadataDocuments(
                OAuthAuthorizationServerMetadata(
                    clientIdMetadataDocumentSupported = true,
                    raw = buildJsonObject {},
                ),
            ),
        )
        assertEquals(
            false,
            supportsMcpOAuthClientIdMetadataDocuments(
                OAuthAuthorizationServerMetadata(
                    clientIdMetadataDocumentSupported = false,
                    raw = buildJsonObject {},
                ),
            ),
        )
        assertEquals(
            false,
            supportsMcpOAuthClientIdMetadataDocuments(OAuthAuthorizationServerMetadata(raw = buildJsonObject {})),
        )
    }

    @Test
    fun `should reject invalid client id metadata document`() {
        assertFailsWith<IllegalArgumentException> {
            McpOAuthClientIdMetadataDocument(
                clientId = "http://app.example.com/oauth/client-metadata.json",
                clientName = "Example MCP Client",
                redirectUris = listOf("http://127.0.0.1/callback"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            McpOAuthClientIdMetadataDocument(
                clientId = "https://app.example.com",
                clientName = "Example MCP Client",
                redirectUris = listOf("http://127.0.0.1/callback"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            McpOAuthClientIdMetadataDocument(
                clientId = "https://app.example.com/oauth/client-metadata.json",
                clientName = "",
                redirectUris = listOf("http://127.0.0.1/callback"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            McpOAuthClientIdMetadataDocument(
                clientId = "https://app.example.com/oauth/client-metadata.json",
                clientName = "Example MCP Client",
                redirectUris = emptyList(),
            )
        }
    }

    @Test
    fun `should register dynamic oauth client`() = runTest {
        var capturedBody: String? = null
        var capturedContentType: String? = null
        val client = HttpClient(
            MockEngine { request ->
                val body = request.body as TextContent
                capturedBody = body.text
                capturedContentType = body.contentType.toString()
                respondJson(
                    """
                    {
                      "client_id": "registered-client",
                      "client_secret": "registered-secret",
                      "client_secret_expires_at": 0,
                      "registration_access_token": "registration-token",
                      "registration_client_uri": "https://auth.example.com/register/registered-client"
                    }
                    """.trimIndent(),
                    status = HttpStatusCode.Created,
                )
            },
        )

        val response = registerMcpOAuthClient(
            httpClient = client,
            registrationEndpoint = "https://auth.example.com/register",
            request = McpOAuthDynamicClientRegistrationRequest(
                clientName = "Example MCP Client",
                redirectUris = listOf("http://127.0.0.1/callback"),
                scope = "tools:call",
                clientUri = "https://app.example.com",
                jwksUri = "https://app.example.com/jwks.json",
            ),
        )
        val json = McpJson.parseToJsonElement(capturedBody!!).jsonObject

        assertEquals("application/json", capturedContentType)
        assertEquals("Example MCP Client", json["client_name"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("http://127.0.0.1/callback"),
            json["redirect_uris"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
        assertEquals("none", json["token_endpoint_auth_method"]?.jsonPrimitive?.content)
        assertEquals(listOf("authorization_code"), json["grant_types"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals(listOf("code"), json["response_types"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals("tools:call", json["scope"]?.jsonPrimitive?.content)
        assertEquals("https://app.example.com", json["client_uri"]?.jsonPrimitive?.content)
        assertEquals("https://app.example.com/jwks.json", json["jwks_uri"]?.jsonPrimitive?.content)
        assertEquals("registered-client", response.clientId)
        assertEquals("registered-secret", response.clientSecret)
        assertEquals(0L, response.clientSecretExpiresAt)
        assertEquals("registration-token", response.registrationAccessToken)
        assertEquals("https://auth.example.com/register/registered-client", response.registrationClientUri)
    }

    @Test
    fun `should fail dynamic oauth client registration on oauth error response`() = runTest {
        val client = HttpClient(
            MockEngine {
                respondJson(
                    """{"error":"invalid_client_metadata","error_description":"bad redirect uri"}""",
                    status = HttpStatusCode.BadRequest,
                )
            },
        )

        assertFailsWith<McpOAuthException> {
            registerMcpOAuthClient(
                client,
                "https://auth.example.com/register",
                McpOAuthDynamicClientRegistrationRequest(
                    clientName = "Example MCP Client",
                    redirectUris = listOf("http://127.0.0.1/callback"),
                ),
            )
        }
    }

    @Test
    fun `should reject invalid dynamic oauth client registration request`() {
        assertFailsWith<IllegalArgumentException> {
            McpOAuthDynamicClientRegistrationRequest(
                clientName = "",
                redirectUris = listOf("http://127.0.0.1/callback"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            McpOAuthDynamicClientRegistrationRequest(
                clientName = "Example MCP Client",
                redirectUris = emptyList(),
            )
        }
    }

    @Test
    fun `should exchange client credentials using client secret basic`() = runTest {
        var capturedForm: FormDataContent? = null
        var capturedAuthorization: String? = null
        val client = HttpClient(
            MockEngine { request ->
                capturedForm = request.body as FormDataContent
                capturedAuthorization = request.headers[HttpHeaders.Authorization]
                respondJson(
                    """
                    {
                      "access_token": "machine-token",
                      "token_type": "Bearer",
                      "expires_in": 300,
                      "scope": "tools:call"
                    }
                    """.trimIndent(),
                )
            },
        )

        val response = exchangeMcpOAuthClientCredentials(
            client,
            clientCredentialsRequest(
                authMethod = McpOAuthTokenEndpointAuthMethod.ClientSecretBasic,
                scope = "tools:call",
            ),
        )

        assertEquals("Basic Y2xpZW50OnNlY3JldA==", capturedAuthorization)
        assertEquals("client_credentials", capturedForm?.formData?.get("grant_type"))
        assertEquals("https://mcp.example.com/mcp", capturedForm?.formData?.get("resource"))
        assertEquals("tools:call", capturedForm?.formData?.get("scope"))
        assertNull(capturedForm?.formData?.get("client_id"))
        assertNull(capturedForm?.formData?.get("client_secret"))
        assertEquals("machine-token", response.accessToken)
        assertEquals("Bearer", response.tokenType)
        assertEquals(300, response.expiresIn)
        assertEquals("tools:call", response.scope)
    }

    @Test
    fun `should exchange client credentials using client secret post`() = runTest {
        var capturedForm: FormDataContent? = null
        val client = HttpClient(
            MockEngine { request ->
                capturedForm = request.body as FormDataContent
                respondJson("""{"access_token":"machine-token"}""")
            },
        )

        exchangeMcpOAuthClientCredentials(
            client,
            clientCredentialsRequest(McpOAuthTokenEndpointAuthMethod.ClientSecretPost),
        )

        assertEquals("client_credentials", capturedForm?.formData?.get("grant_type"))
        assertEquals("client", capturedForm?.formData?.get("client_id"))
        assertEquals("secret", capturedForm?.formData?.get("client_secret"))
    }

    @Test
    fun `should exchange client credentials using private key jwt assertion`() = runTest {
        var capturedForm: FormDataContent? = null
        var capturedAuthorization: String? = null
        val client = HttpClient(
            MockEngine { request ->
                capturedForm = request.body as FormDataContent
                capturedAuthorization = request.headers[HttpHeaders.Authorization]
                respondJson("""{"access_token":"machine-token"}""")
            },
        )

        exchangeMcpOAuthClientCredentials(
            client,
            clientCredentialsRequest(
                authMethod = McpOAuthTokenEndpointAuthMethod.PrivateKeyJwt,
                clientSecret = null,
            ),
            McpOAuthClientAssertionProvider { "signed-jwt" },
        )

        assertNull(capturedAuthorization)
        assertEquals("client_credentials", capturedForm?.formData?.get("grant_type"))
        assertEquals("https://mcp.example.com/mcp", capturedForm?.formData?.get("resource"))
        assertEquals("client", capturedForm?.formData?.get("client_id"))
        assertEquals(MCP_OAUTH_JWT_BEARER_CLIENT_ASSERTION_TYPE, capturedForm?.formData?.get("client_assertion_type"))
        assertEquals("signed-jwt", capturedForm?.formData?.get("client_assertion"))
        assertNull(capturedForm?.formData?.get("client_secret"))
    }

    @Test
    fun `should require assertion provider for private key jwt token exchange`() = runTest {
        val client = HttpClient(MockEngine { respondJson("""{"access_token":"machine-token"}""") })

        assertFailsWith<McpOAuthException> {
            exchangeMcpOAuthClientCredentials(
                client,
                clientCredentialsRequest(
                    authMethod = McpOAuthTokenEndpointAuthMethod.PrivateKeyJwt,
                    clientSecret = null,
                ),
            )
        }
    }

    @Test
    fun `should fail client credentials exchange on oauth error response`() = runTest {
        val client = HttpClient(
            MockEngine {
                respondJson(
                    """{"error":"invalid_client","error_description":"bad credentials"}""",
                    status = HttpStatusCode.Unauthorized,
                )
            },
        )

        assertFailsWith<McpOAuthException> {
            exchangeMcpOAuthClientCredentials(
                client,
                clientCredentialsRequest(McpOAuthTokenEndpointAuthMethod.ClientSecretBasic),
            )
        }
    }

    @Test
    fun `should install client credentials provider and retry unauthorized request`() = runTest {
        val tokenRequests = mutableListOf<String?>()
        val tokenClient = HttpClient(
            MockEngine { request ->
                tokenRequests += (request.body as FormDataContent).formData["grant_type"]
                respondJson("""{"access_token":"machine-token-${tokenRequests.size}"}""")
            },
        )
        val seenAuthorizationHeaders = mutableListOf<String?>()
        val mcpClient = HttpClient(
            MockEngine { request ->
                seenAuthorizationHeaders += request.headers[HttpHeaders.Authorization]
                when (request.headers[HttpHeaders.Authorization]) {
                    "Bearer machine-token-1" -> respond("", status = HttpStatusCode.Unauthorized)
                    "Bearer machine-token-2" -> respond("ok")
                    else -> error("Unexpected Authorization header: ${request.headers[HttpHeaders.Authorization]}")
                }
            },
        )
        val provider = McpOAuthClientCredentialsProvider(
            tokenClient,
            clientCredentialsRequest(McpOAuthTokenEndpointAuthMethod.ClientSecretBasic),
        )
        mcpClient.installMcpOAuthClientCredentials(provider)

        mcpClient.get("https://mcp.example.com/mcp")

        assertEquals(listOf<String?>("client_credentials", "client_credentials"), tokenRequests)
        assertEquals(listOf<String?>("Bearer machine-token-1", "Bearer machine-token-2"), seenAuthorizationHeaders)
        assertEquals("machine-token-2", provider.currentAccessToken)
    }

    @Test
    fun `should refresh access token using client secret basic`() = runTest {
        var capturedForm: FormDataContent? = null
        var capturedAuthorization: String? = null
        val client = HttpClient(
            MockEngine { request ->
                capturedForm = request.body as FormDataContent
                capturedAuthorization = request.headers[HttpHeaders.Authorization]
                respondJson(
                    """
                    {
                      "access_token": "access-refreshed",
                      "token_type": "Bearer",
                      "expires_in": 3600,
                      "refresh_token": "refresh-next",
                      "scope": "files:read"
                    }
                    """.trimIndent(),
                )
            },
        )

        val response = refreshMcpOAuthAccessToken(
            client,
            refreshRequest(
                authMethod = McpOAuthTokenEndpointAuthMethod.ClientSecretBasic,
                scope = "files:read",
            ),
        )

        assertEquals("Basic Y2xpZW50OnNlY3JldA==", capturedAuthorization)
        assertEquals("refresh_token", capturedForm?.formData?.get("grant_type"))
        assertEquals("refresh-123", capturedForm?.formData?.get("refresh_token"))
        assertEquals("https://mcp.example.com/mcp", capturedForm?.formData?.get("resource"))
        assertEquals("files:read", capturedForm?.formData?.get("scope"))
        assertNull(capturedForm?.formData?.get("client_id"))
        assertNull(capturedForm?.formData?.get("client_secret"))
        assertEquals("access-refreshed", response.accessToken)
        assertEquals("Bearer", response.tokenType)
        assertEquals(3600, response.expiresIn)
        assertEquals("refresh-next", response.refreshToken)
        assertEquals("files:read", response.scope)
    }

    @Test
    fun `should refresh access token using public client auth`() = runTest {
        var capturedForm: FormDataContent? = null
        val client = HttpClient(
            MockEngine { request ->
                capturedForm = request.body as FormDataContent
                respondJson("""{"access_token":"access-refreshed"}""")
            },
        )

        refreshMcpOAuthAccessToken(
            client,
            refreshRequest(
                authMethod = McpOAuthTokenEndpointAuthMethod.None,
                clientSecret = null,
            ),
        )

        assertEquals("refresh_token", capturedForm?.formData?.get("grant_type"))
        assertEquals("client", capturedForm?.formData?.get("client_id"))
        assertNull(capturedForm?.formData?.get("client_secret"))
    }

    @Test
    fun `should fail refresh token exchange on oauth error response`() = runTest {
        val client = HttpClient(
            MockEngine {
                respondJson(
                    """{"error":"invalid_grant","error_description":"expired refresh token"}""",
                    status = HttpStatusCode.BadRequest,
                )
            },
        )

        assertFailsWith<McpOAuthException> {
            refreshMcpOAuthAccessToken(
                client,
                refreshRequest(McpOAuthTokenEndpointAuthMethod.ClientSecretPost),
            )
        }
    }

    @Test
    fun `should fail client secret post refresh without client secret`() = runTest {
        val client = HttpClient(MockEngine { error("Token request should not be sent") })

        assertFailsWith<McpOAuthException> {
            refreshMcpOAuthAccessToken(
                client,
                refreshRequest(
                    authMethod = McpOAuthTokenEndpointAuthMethod.ClientSecretPost,
                    clientSecret = null,
                ),
            )
        }
    }

    @Test
    fun `should exchange authorization code using client secret basic`() = runTest {
        var capturedForm: FormDataContent? = null
        var capturedAuthorization: String? = null
        val client = HttpClient(
            MockEngine { request ->
                capturedForm = request.body as FormDataContent
                capturedAuthorization = request.headers[HttpHeaders.Authorization]
                respondJson(
                    """
                    {
                      "access_token": "access-123",
                      "token_type": "Bearer",
                      "expires_in": 3600,
                      "refresh_token": "refresh-123",
                      "scope": "files:read"
                    }
                    """.trimIndent(),
                )
            },
        )

        val response = exchangeMcpOAuthAuthorizationCode(
            client,
            tokenRequest(McpOAuthTokenEndpointAuthMethod.ClientSecretBasic),
        )

        assertEquals("Basic Y2xpZW50OnNlY3JldA==", capturedAuthorization)
        assertEquals("authorization_code", capturedForm?.formData?.get("grant_type"))
        assertEquals("code-123", capturedForm?.formData?.get("code"))
        assertEquals("http://127.0.0.1/callback", capturedForm?.formData?.get("redirect_uri"))
        assertEquals("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk", capturedForm?.formData?.get("code_verifier"))
        assertEquals("https://mcp.example.com/mcp", capturedForm?.formData?.get("resource"))
        assertNull(capturedForm?.formData?.get("client_id"))
        assertNull(capturedForm?.formData?.get("client_secret"))
        assertEquals("access-123", response.accessToken)
        assertEquals("Bearer", response.tokenType)
        assertEquals(3600, response.expiresIn)
        assertEquals("refresh-123", response.refreshToken)
        assertEquals("files:read", response.scope)
    }

    @Test
    fun `should form encode client secret basic credentials`() = runTest {
        var capturedAuthorization: String? = null
        val client = HttpClient(
            MockEngine { request ->
                capturedAuthorization = request.headers[HttpHeaders.Authorization]
                respondJson("""{"access_token":"access-123"}""")
            },
        )

        exchangeMcpOAuthAuthorizationCode(
            client,
            tokenRequest(
                authMethod = McpOAuthTokenEndpointAuthMethod.ClientSecretBasic,
                clientId = "client id",
                clientSecret = "sec:ret",
            ),
        )

        assertEquals("Basic Y2xpZW50K2lkOnNlYyUzQXJldA==", capturedAuthorization)
    }

    @Test
    fun `should exchange authorization code using client secret post`() = runTest {
        var capturedForm: FormDataContent? = null
        val client = HttpClient(
            MockEngine { request ->
                capturedForm = request.body as FormDataContent
                respondJson("""{"access_token":"access-123"}""")
            },
        )

        exchangeMcpOAuthAuthorizationCode(
            client,
            tokenRequest(McpOAuthTokenEndpointAuthMethod.ClientSecretPost),
        )

        assertEquals("client", capturedForm?.formData?.get("client_id"))
        assertEquals("secret", capturedForm?.formData?.get("client_secret"))
    }

    @Test
    fun `should exchange authorization code using public client auth`() = runTest {
        var capturedForm: FormDataContent? = null
        val client = HttpClient(
            MockEngine { request ->
                capturedForm = request.body as FormDataContent
                respondJson("""{"access_token":"access-123"}""")
            },
        )

        exchangeMcpOAuthAuthorizationCode(
            client,
            tokenRequest(
                authMethod = McpOAuthTokenEndpointAuthMethod.None,
                clientSecret = null,
            ),
        )

        assertEquals("client", capturedForm?.formData?.get("client_id"))
        assertNull(capturedForm?.formData?.get("client_secret"))
    }

    @Test
    fun `should fail token exchange on oauth error response`() = runTest {
        val client = HttpClient(
            MockEngine {
                respondJson(
                    """{"error":"invalid_grant","error_description":"expired code"}""",
                    status = HttpStatusCode.BadRequest,
                )
            },
        )

        assertFailsWith<McpOAuthException> {
            exchangeMcpOAuthAuthorizationCode(
                client,
                tokenRequest(McpOAuthTokenEndpointAuthMethod.ClientSecretPost),
            )
        }
    }

    @Test
    fun `should fail client secret post without client secret`() = runTest {
        val client = HttpClient(MockEngine { error("Token request should not be sent") })

        assertFailsWith<McpOAuthException> {
            exchangeMcpOAuthAuthorizationCode(
                client,
                tokenRequest(
                    authMethod = McpOAuthTokenEndpointAuthMethod.ClientSecretPost,
                    clientSecret = null,
                ),
            )
        }
    }

    @Test
    fun `should build protected resource metadata URLs in priority order`() {
        val urls = mcpProtectedResourceMetadataUrls("https://mcp.example.com/public/mcp")

        assertEquals(
            listOf(
                "https://mcp.example.com/.well-known/oauth-protected-resource/public/mcp",
                "https://mcp.example.com/.well-known/oauth-protected-resource",
            ),
            urls,
        )
    }

    @Test
    fun `should build authorization server metadata URLs in priority order`() {
        val urls = mcpAuthorizationServerMetadataUrls("https://auth.example.com/tenant1")

        assertEquals(
            listOf(
                "https://auth.example.com/.well-known/oauth-authorization-server/tenant1",
                "https://auth.example.com/.well-known/openid-configuration/tenant1",
                "https://auth.example.com/tenant1/.well-known/openid-configuration",
            ),
            urls,
        )
    }

    @Test
    fun `should discover resource metadata using root fallback`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                requestedUrls += request.url.toString()
                when (request.url.toString()) {
                    "https://mcp.example.com/.well-known/oauth-protected-resource/public/mcp" ->
                        respond("", status = HttpStatusCode.NotFound)

                    "https://mcp.example.com/.well-known/oauth-protected-resource" -> respondJson(
                        """
                        {
                          "resource": "https://mcp.example.com",
                          "authorization_servers": ["https://auth.example.com"],
                          "scopes_supported": ["files:read", "files:write"]
                        }
                        """.trimIndent(),
                    )

                    else -> error("Unexpected URL: ${request.url}")
                }
            },
        )

        val metadata = discoverMcpProtectedResourceMetadata(client, "https://mcp.example.com/public/mcp")

        assertEquals(
            listOf(
                "https://mcp.example.com/.well-known/oauth-protected-resource/public/mcp",
                "https://mcp.example.com/.well-known/oauth-protected-resource",
            ),
            requestedUrls,
        )
        assertEquals("https://mcp.example.com", metadata.resource)
        assertEquals(listOf("https://auth.example.com"), metadata.authorizationServers)
        assertEquals(listOf("files:read", "files:write"), metadata.scopesSupported)
    }

    @Test
    fun `should discover oauth metadata with protected resource metadata override`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                requestedUrls += request.url.toString()
                when (request.url.toString()) {
                    "https://mcp.example.com/custom-resource-metadata" -> respondJson(
                        """
                        {
                          "resource": "https://mcp.example.com/mcp",
                          "authorization_servers": ["https://auth.example.com/tenant"],
                          "scopes_supported": ["mcp:read"]
                        }
                        """.trimIndent(),
                    )

                    "https://auth.example.com/.well-known/oauth-authorization-server/tenant" ->
                        respond("", status = HttpStatusCode.NotFound)

                    "https://auth.example.com/.well-known/openid-configuration/tenant" -> respondJson(
                        """
                        {
                          "issuer": "https://auth.example.com/tenant",
                          "authorization_endpoint": "https://auth.example.com/authorize",
                          "token_endpoint": "https://auth.example.com/token",
                          "token_endpoint_auth_methods_supported": ["client_secret_post"],
                          "code_challenge_methods_supported": ["S256"],
                          "client_id_metadata_document_supported": true
                        }
                        """.trimIndent(),
                    )

                    else -> error("Unexpected URL: ${request.url}")
                }
            },
        )

        val result = discoverMcpOAuthMetadata(
            httpClient = client,
            serverUrl = "https://mcp.example.com/mcp",
            resourceMetadataUrl = "https://mcp.example.com/custom-resource-metadata",
        )

        assertEquals(
            listOf(
                "https://mcp.example.com/custom-resource-metadata",
                "https://auth.example.com/.well-known/oauth-authorization-server/tenant",
                "https://auth.example.com/.well-known/openid-configuration/tenant",
            ),
            requestedUrls,
        )
        assertEquals("https://mcp.example.com/mcp", result.resourceMetadata.resource)
        assertEquals("https://auth.example.com/token", result.authorizationServerMetadata.tokenEndpoint)
        assertEquals(listOf("S256"), result.authorizationServerMetadata.codeChallengeMethodsSupported)
        assertEquals(true, result.authorizationServerMetadata.clientIdMetadataDocumentSupported)
    }

    @Test
    fun `should prepare and complete authorization code flow from challenge metadata`() = runTest {
        val requestedUrls = mutableListOf<String>()
        var capturedForm: FormDataContent? = null
        val challenge = """Bearer resource_metadata="https://mcp.example.com/custom-resource-metadata",""" +
            """ scope="files:read""""
        val pkce = McpOAuthPkce(
            codeVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk",
            codeChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
        )
        val client = HttpClient(
            MockEngine { request ->
                requestedUrls += request.url.toString()
                when (request.url.toString()) {
                    "https://mcp.example.com/custom-resource-metadata" -> respondJson(
                        """
                        {
                          "resource": "https://mcp.example.com/mcp",
                          "authorization_servers": ["https://auth.example.com/tenant"],
                          "scopes_supported": ["files:read", "files:write"]
                        }
                        """.trimIndent(),
                    )

                    "https://auth.example.com/.well-known/oauth-authorization-server/tenant" -> respondJson(
                        """
                        {
                          "issuer": "https://auth.example.com/tenant",
                          "authorization_endpoint": "https://auth.example.com/authorize",
                          "token_endpoint": "https://auth.example.com/token",
                          "token_endpoint_auth_methods_supported": ["private_key_jwt", "none"],
                          "code_challenge_methods_supported": ["S256"]
                        }
                        """.trimIndent(),
                    )

                    "https://auth.example.com/token" -> {
                        capturedForm = request.body as FormDataContent
                        respondJson("""{"access_token":"access-123","token_type":"Bearer"}""")
                    }

                    else -> error("Unexpected URL: ${request.url}")
                }
            },
        )

        val prepared = prepareMcpOAuthAuthorizationCodeFlow(
            httpClient = client,
            request = McpOAuthAuthorizationCodeFlowRequest(
                serverUrl = "https://mcp.example.com/mcp",
                clientCredentials = McpOAuthClientCredentials(
                    clientId = "https://client.example.com/metadata.json",
                ),
                redirectUri = "http://127.0.0.1/callback",
                pkce = pkce,
                state = "state-123",
                wwwAuthenticate = challenge,
                clientAssertionProvider = McpOAuthClientAssertionProvider { "jwt-assertion" },
            ),
        )
        val authorizationUrl = Url(prepared.authorizationUrl)

        assertEquals(
            listOf(
                "https://mcp.example.com/custom-resource-metadata",
                "https://auth.example.com/.well-known/oauth-authorization-server/tenant",
            ),
            requestedUrls,
        )
        assertEquals("https://auth.example.com/authorize", authorizationUrl.toString().substringBefore("?"))
        assertEquals("code", authorizationUrl.parameters["response_type"])
        assertEquals("https://client.example.com/metadata.json", authorizationUrl.parameters["client_id"])
        assertEquals("http://127.0.0.1/callback", authorizationUrl.parameters["redirect_uri"])
        assertEquals(pkce.codeChallenge, authorizationUrl.parameters["code_challenge"])
        assertEquals("S256", authorizationUrl.parameters["code_challenge_method"])
        assertEquals("https://mcp.example.com/mcp", authorizationUrl.parameters["resource"])
        assertEquals("files:read", authorizationUrl.parameters["scope"])
        assertEquals("state-123", authorizationUrl.parameters["state"])
        assertEquals(McpOAuthTokenEndpointAuthMethod.PrivateKeyJwt, prepared.tokenEndpointAuthMethod)

        val tokens = exchangeMcpOAuthAuthorizationCode(
            httpClient = client,
            preparedFlow = prepared,
            code = "code-123",
        )

        assertEquals("access-123", tokens.accessToken)
        assertEquals("authorization_code", capturedForm?.formData?.get("grant_type"))
        assertEquals("code-123", capturedForm?.formData?.get("code"))
        assertEquals(pkce.codeVerifier, capturedForm?.formData?.get("code_verifier"))
        assertEquals("https://mcp.example.com/mcp", capturedForm?.formData?.get("resource"))
        assertEquals("https://client.example.com/metadata.json", capturedForm?.formData?.get("client_id"))
        assertEquals(MCP_OAUTH_JWT_BEARER_CLIENT_ASSERTION_TYPE, capturedForm?.formData?.get("client_assertion_type"))
        assertEquals("jwt-assertion", capturedForm?.formData?.get("client_assertion"))
    }

    @Test
    fun `should parse bearer challenge parameters`() {
        val header = """Bearer resource_metadata="https://mcp.example.com/.well-known/oauth-protected-resource",""" +
            """ scope="files:read files:write""""

        assertEquals(
            "https://mcp.example.com/.well-known/oauth-protected-resource",
            wwwAuthenticateParameter(header, "resource_metadata"),
        )
        assertEquals("files:read files:write", wwwAuthenticateParameter(header, "scope"))
    }

    @Test
    fun `should parse step up scope only for insufficient scope`() {
        val header = """Bearer error="insufficient_scope", scope="files:write",""" +
            """ resource_metadata="https://mcp.example.com/.well-known/oauth-protected-resource""""

        assertEquals("files:write", mcpOAuthStepUpScope(header))
        assertNull(mcpOAuthStepUpScope("""Bearer error="invalid_token", scope="files:write""""))
    }

    @Test
    fun `should select challenge scope before metadata scopes`() {
        assertEquals(
            "files:read",
            selectMcpOAuthScope("files:read", listOf("files:read", "files:write")),
        )
        assertEquals(
            "files:read files:write",
            selectMcpOAuthScope(null, listOf("files:read", "files:write")),
        )
        assertNull(selectMcpOAuthScope(null, null))
    }

    @Test
    fun `should apply bearer authorization header`() = runTest {
        val client = HttpClient(
            MockEngine { request ->
                assertEquals("Bearer token-123", request.headers[HttpHeaders.Authorization])
                respond("ok")
            },
        )

        client.get("https://mcp.example.com/mcp") {
            mcpBearerAuth("token-123")(this)
        }
    }

    @Test
    fun `should refresh bearer token and retry unauthorized request`() = runTest {
        val seenAuthorizationHeaders = mutableListOf<String?>()
        val tokenStore = McpOAuthTokenStore(
            McpOAuthTokenResponse(
                accessToken = "expired-token",
                refreshToken = "refresh-token",
                raw = buildJsonObject {},
            ),
        )
        val client = HttpClient(
            MockEngine { request ->
                seenAuthorizationHeaders += request.headers[HttpHeaders.Authorization]
                when (request.headers[HttpHeaders.Authorization]) {
                    "Bearer expired-token" -> respond("", status = HttpStatusCode.Unauthorized)
                    "Bearer fresh-token" -> respond("ok")
                    else -> error("Unexpected Authorization header: ${request.headers[HttpHeaders.Authorization]}")
                }
            },
        )
        client.installMcpOAuthBearerAuth(tokenStore) { refreshToken ->
            assertEquals("refresh-token", refreshToken)
            McpOAuthTokenResponse(
                accessToken = "fresh-token",
                raw = buildJsonObject {},
            )
        }

        client.get("https://mcp.example.com/mcp")

        assertEquals(listOf<String?>("Bearer expired-token", "Bearer fresh-token"), seenAuthorizationHeaders)
        assertEquals("fresh-token", tokenStore.accessToken)
        assertEquals("refresh-token", tokenStore.refreshToken)
    }

    @Test
    fun `should snapshot and restore token store`() {
        val initial = McpOAuthTokenResponse(
            accessToken = "access-1",
            tokenType = "Bearer",
            expiresIn = 300,
            refreshToken = "refresh-1",
            scope = "tools:call",
            raw = buildJsonObject {
                put("access_token", JsonPrimitive("access-1"))
            },
        )
        val updates = mutableListOf<McpOAuthTokenStoreSnapshot>()
        val tokenStore = McpOAuthTokenStore(initial, 1_000L, updates::add)

        tokenStore.update(
            McpOAuthTokenResponse(
                accessToken = "access-2",
                tokenType = "Bearer",
                expiresIn = 600,
                scope = "tools:call resources:read",
                raw = buildJsonObject {
                    put("access_token", JsonPrimitive("access-2"))
                },
            ),
            receivedAtEpochSeconds = 1_200L,
        )

        val snapshot = tokenStore.snapshot()
        val restored = McpOAuthTokenStore(snapshot)

        assertEquals(1_300L, initial.expiresAtEpochSeconds(1_000L))
        assertEquals("access-2", snapshot.accessToken)
        assertEquals("refresh-1", snapshot.refreshToken)
        assertEquals("Bearer", snapshot.tokenType)
        assertEquals(600, snapshot.expiresIn)
        assertEquals(1_800L, snapshot.expiresAtEpochSeconds)
        assertEquals("tools:call resources:read", snapshot.scope)
        assertEquals("access-2", restored.accessToken)
        assertEquals("refresh-1", restored.refreshToken)
        assertEquals(1_800L, restored.expiresAtEpochSeconds)
        assertEquals(false, restored.shouldRefresh(currentEpochSeconds = 1_700L, refreshSkewSeconds = 60))
        assertEquals(true, restored.shouldRefresh(currentEpochSeconds = 1_740L, refreshSkewSeconds = 60))
        assertEquals(listOf(snapshot), updates)
    }

    @Test
    fun `should fail when metadata cannot be discovered`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond("", status = HttpStatusCode.NotFound)
            },
        )

        assertFailsWith<McpOAuthException> {
            discoverMcpProtectedResourceMetadata(client, "https://mcp.example.com/mcp")
        }
    }

    @Test
    fun `should fail oauth discovery when protected resource metadata omits authorization servers`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                requestedUrls += request.url.toString()
                when (request.url.toString()) {
                    "https://mcp.example.com/.well-known/oauth-protected-resource/mcp" -> respondJson(
                        """
                        {
                          "resource": "https://mcp.example.com/mcp",
                          "scopes_supported": ["mcp:read"]
                        }
                        """.trimIndent(),
                    )

                    else -> error("Unexpected URL: ${request.url}")
                }
            },
        )

        assertFailsWith<McpOAuthException> {
            discoverMcpOAuthMetadata(client, "https://mcp.example.com/mcp")
        }
        assertEquals(
            listOf("https://mcp.example.com/.well-known/oauth-protected-resource/mcp"),
            requestedUrls,
        )
    }

    private fun tokenRequest(
        authMethod: McpOAuthTokenEndpointAuthMethod? = null,
        clientId: String = "client",
        clientSecret: String? = "secret",
    ): McpOAuthAuthorizationCodeTokenRequest {
        val clientCredentials = McpOAuthClientCredentials(
            clientId = clientId,
            clientSecret = clientSecret,
        )
        return if (authMethod == null) {
            McpOAuthAuthorizationCodeTokenRequest(
                tokenEndpoint = "https://auth.example.com/token",
                code = "code-123",
                redirectUri = "http://127.0.0.1/callback",
                codeVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk",
                resource = "https://mcp.example.com/mcp",
                clientCredentials = clientCredentials,
            )
        } else {
            McpOAuthAuthorizationCodeTokenRequest(
                tokenEndpoint = "https://auth.example.com/token",
                code = "code-123",
                redirectUri = "http://127.0.0.1/callback",
                codeVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk",
                resource = "https://mcp.example.com/mcp",
                clientCredentials = clientCredentials,
                tokenEndpointAuthMethod = authMethod,
            )
        }
    }

    private fun refreshRequest(
        authMethod: McpOAuthTokenEndpointAuthMethod? = null,
        clientId: String = "client",
        clientSecret: String? = "secret",
        scope: String? = null,
    ): McpOAuthRefreshTokenRequest {
        val clientCredentials = McpOAuthClientCredentials(
            clientId = clientId,
            clientSecret = clientSecret,
        )
        return if (authMethod == null) {
            McpOAuthRefreshTokenRequest(
                tokenEndpoint = "https://auth.example.com/token",
                refreshToken = "refresh-123",
                resource = "https://mcp.example.com/mcp",
                scope = scope,
                clientCredentials = clientCredentials,
            )
        } else {
            McpOAuthRefreshTokenRequest(
                tokenEndpoint = "https://auth.example.com/token",
                refreshToken = "refresh-123",
                resource = "https://mcp.example.com/mcp",
                scope = scope,
                clientCredentials = clientCredentials,
                tokenEndpointAuthMethod = authMethod,
            )
        }
    }

    private fun clientCredentialsRequest(
        authMethod: McpOAuthTokenEndpointAuthMethod,
        clientId: String = "client",
        clientSecret: String? = "secret",
        scope: String? = null,
    ): McpOAuthClientCredentialsTokenRequest = McpOAuthClientCredentialsTokenRequest(
        tokenEndpoint = "https://auth.example.com/token",
        resource = "https://mcp.example.com/mcp",
        scope = scope,
        clientCredentials = McpOAuthClientCredentials(
            clientId = clientId,
            clientSecret = clientSecret,
        ),
        tokenEndpointAuthMethod = authMethod,
    )

    private fun MockRequestHandleScope.respondJson(content: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(
            content = content,
            status = status,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
}
