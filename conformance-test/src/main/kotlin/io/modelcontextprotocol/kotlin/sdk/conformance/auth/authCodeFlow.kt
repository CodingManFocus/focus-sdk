package io.modelcontextprotocol.kotlin.sdk.conformance.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.auth.McpOAuthAuthorizationCodeTokenRequest
import io.modelcontextprotocol.kotlin.sdk.client.auth.McpOAuthAuthorizationRequest
import io.modelcontextprotocol.kotlin.sdk.client.auth.McpOAuthClientCredentials
import io.modelcontextprotocol.kotlin.sdk.client.auth.OAuthAuthorizationServerMetadata
import io.modelcontextprotocol.kotlin.sdk.client.auth.buildMcpOAuthAuthorizationUrl
import io.modelcontextprotocol.kotlin.sdk.client.auth.exchangeMcpOAuthAuthorizationCode
import io.modelcontextprotocol.kotlin.sdk.client.auth.mcpBearerAuth
import io.modelcontextprotocol.kotlin.sdk.client.auth.mcpOAuthStepUpScope
import io.modelcontextprotocol.kotlin.sdk.client.auth.mcpPkceS256
import io.modelcontextprotocol.kotlin.sdk.client.auth.requireMcpPkceS256Support
import io.modelcontextprotocol.kotlin.sdk.client.auth.selectMcpOAuthScope
import io.modelcontextprotocol.kotlin.sdk.client.auth.selectMcpOAuthTokenEndpointAuthMethod
import io.modelcontextprotocol.kotlin.sdk.client.auth.wwwAuthenticateParameter
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom
import java.util.UUID

internal suspend fun runAuthClient(serverUrl: String) {
    val httpClient = HttpClient(CIO) {
        install(SSE)
        followRedirects = false
    }

    var accessToken: String? = null
    var authAttempts = 0
    // Cache discovery and credentials across retries
    var cachedDiscovery: DiscoveryResult? = null
    var cachedCredentials: ClientCredentials? = null

    httpClient.plugin(HttpSend).intercept { request ->
        // Add existing token if available
        accessToken?.let { mcpBearerAuth(it)(request) }

        val response = execute(request)
        val status = response.response.status

        // Determine if we need to (re-)authorize
        val needsAuth = status == HttpStatusCode.Unauthorized
        val wwwAuth = response.response.headers[HttpHeaders.WWWAuthenticate] ?: ""
        val stepUpScope = if (status == HttpStatusCode.Forbidden) mcpOAuthStepUpScope(wwwAuth) else null
        val needsStepUp = stepUpScope != null

        if ((needsAuth || needsStepUp) && authAttempts < 3) {
            authAttempts++

            // Discover metadata (cache across retries)
            if (cachedDiscovery == null) {
                val resourceMetadataUrl = wwwAuthenticateParameter(wwwAuth, "resource_metadata")
                cachedDiscovery = discoverOAuthMetadata(httpClient, serverUrl, resourceMetadataUrl)
            }
            val discovery: DiscoveryResult = cachedDiscovery

            // Validate PRM resource matches server URL (RFC 8707)
            val discoveredResource = discovery.resourceUrl
            if (discoveredResource != null) {
                val normalizedResource = discoveredResource.trimEnd('/')
                val normalizedServerUrl = serverUrl.trimEnd('/')
                val matches = normalizedServerUrl == normalizedResource ||
                    normalizedServerUrl.startsWith("$normalizedResource/")
                require(matches) {
                    "PRM resource mismatch: resource='$discoveredResource' does not match server URL='$serverUrl'"
                }
            }

            val metadata = discovery.asMetadata.toOAuthAuthorizationServerMetadata()

            val authEndpoint = metadata.authorizationEndpoint
                ?: error("No authorization_endpoint in metadata")
            val tokenEndpoint = metadata.tokenEndpoint
                ?: error("No token_endpoint in metadata")

            // Verify PKCE support
            requireMcpPkceS256Support(metadata)

            // Resolve client credentials (cache across retries)
            if (cachedCredentials == null) {
                cachedCredentials = resolveClientCredentials(httpClient, discovery.asMetadata)
            }
            val creds: ClientCredentials = cachedCredentials

            // Determine scope
            val scope = if (needsStepUp) {
                stepUpScope
            } else {
                val wwwAuthScope = wwwAuthenticateParameter(wwwAuth, "scope")
                selectMcpOAuthScope(wwwAuthScope, discovery.scopesSupported)
            }

            // PKCE
            val pkce = mcpPkceS256(secureRandomBytes(32))

            // CSRF state parameter
            val state = UUID.randomUUID().toString()

            // Build authorization URL
            val resource = discovery.resourceUrl ?: serverUrl.trimEnd('/')
            val authUrl = buildMcpOAuthAuthorizationUrl(
                McpOAuthAuthorizationRequest(
                    authorizationEndpoint = authEndpoint,
                    clientId = creds.clientId,
                    redirectUri = CALLBACK_URL,
                    codeChallenge = pkce.codeChallenge,
                    resource = resource,
                    scope = scope,
                    state = state,
                ),
            )

            // Follow the authorization redirect to get auth code
            val authCode = followAuthorizationRedirect(httpClient, authUrl, CALLBACK_URL, state)

            // Exchange code for tokens
            val tokenResponse = exchangeMcpOAuthAuthorizationCode(
                httpClient,
                McpOAuthAuthorizationCodeTokenRequest(
                    tokenEndpoint = tokenEndpoint,
                    code = authCode,
                    redirectUri = CALLBACK_URL,
                    codeVerifier = pkce.codeVerifier,
                    resource = resource,
                    clientCredentials = McpOAuthClientCredentials(
                        clientId = creds.clientId,
                        clientSecret = creds.clientSecret,
                    ),
                    tokenEndpointAuthMethod = selectMcpOAuthTokenEndpointAuthMethod(
                        metadata = metadata,
                        clientSecret = creds.clientSecret,
                    ),
                ),
            )
            accessToken = tokenResponse.accessToken

            // Retry the original request with the token
            mcpBearerAuth(tokenResponse.accessToken)(request)
            execute(request)
        } else {
            response
        }
    }

    httpClient.use { client ->
        val transport = StreamableHttpClientTransport(client, serverUrl)
        val mcpClient = Client(
            clientInfo = Implementation("test-auth-client", "1.0.0"),
            options = ClientOptions(capabilities = ClientCapabilities()),
        )
        mcpClient.connect(transport)
        mcpClient.listTools()
        mcpClient.callTool(CallToolRequest(CallToolRequestParams(name = "test-tool")))
        mcpClient.close()
    }
}

private fun secureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    SecureRandom().nextBytes(bytes)
    return bytes
}

private fun JsonObject.toOAuthAuthorizationServerMetadata(): OAuthAuthorizationServerMetadata =
    OAuthAuthorizationServerMetadata(
        issuer = stringOrNull("issuer"),
        authorizationEndpoint = stringOrNull("authorization_endpoint"),
        tokenEndpoint = stringOrNull("token_endpoint"),
        registrationEndpoint = stringOrNull("registration_endpoint"),
        tokenEndpointAuthMethodsSupported = stringListOrNull("token_endpoint_auth_methods_supported"),
        codeChallengeMethodsSupported = stringListOrNull("code_challenge_methods_supported"),
        clientIdMetadataDocumentSupported = booleanOrNull("client_id_metadata_document_supported"),
        raw = this,
    )

private fun JsonObject.stringOrNull(key: String): String? = this[key]?.jsonPrimitive?.content

private fun JsonObject.booleanOrNull(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

private fun JsonObject.stringListOrNull(key: String): List<String>? =
    this[key]?.jsonArray?.map { it.jsonPrimitive.content }
