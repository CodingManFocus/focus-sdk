package io.modelcontextprotocol.kotlin.sdk.client.auth

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.EmptyJsonObject
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Capability extension identifier for OAuth client credentials. */
public const val MCP_OAUTH_CLIENT_CREDENTIALS_EXTENSION: String = "io.modelcontextprotocol/oauth-client-credentials"

/** OAuth JWT bearer client assertion type for `private_key_jwt` token endpoint authentication. */
public const val MCP_OAUTH_JWT_BEARER_CLIENT_ASSERTION_TYPE: String =
    "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"

/**
 * OAuth 2.0 Protected Resource Metadata for an MCP server.
 *
 * @property resource Canonical resource URI advertised by the protected MCP server.
 * @property authorizationServers Authorization server issuer URLs advertised by the resource server.
 * @property scopesSupported Scopes advertised by the protected resource metadata document.
 * @property raw Complete metadata document for fields not modeled by this SDK version.
 */
public data class OAuthProtectedResourceMetadata(
    public val resource: String? = null,
    public val authorizationServers: List<String> = emptyList(),
    public val scopesSupported: List<String>? = null,
    public val raw: JsonObject,
)

/**
 * OAuth Authorization Server Metadata discovered for an MCP authorization flow.
 *
 * @property issuer Authorization server issuer URL.
 * @property authorizationEndpoint OAuth authorization endpoint.
 * @property tokenEndpoint OAuth token endpoint.
 * @property registrationEndpoint Dynamic client registration endpoint, if supported.
 * @property tokenEndpointAuthMethodsSupported Supported token endpoint authentication methods.
 * @property codeChallengeMethodsSupported Supported PKCE challenge methods.
 * @property clientIdMetadataDocumentSupported Whether Client ID Metadata Documents are advertised.
 * @property raw Complete metadata document for fields not modeled by this SDK version.
 */
public data class OAuthAuthorizationServerMetadata(
    public val issuer: String? = null,
    public val authorizationEndpoint: String? = null,
    public val tokenEndpoint: String? = null,
    public val registrationEndpoint: String? = null,
    public val tokenEndpointAuthMethodsSupported: List<String>? = null,
    public val codeChallengeMethodsSupported: List<String>? = null,
    public val clientIdMetadataDocumentSupported: Boolean? = null,
    public val raw: JsonObject,
)

/**
 * Result of discovering MCP protected resource metadata and its authorization server metadata.
 */
public data class McpOAuthDiscoveryResult(
    public val resourceMetadata: OAuthProtectedResourceMetadata,
    public val authorizationServerMetadata: OAuthAuthorizationServerMetadata,
)

/**
 * Error raised when MCP OAuth discovery or parsing fails.
 */
public class McpOAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A parsed `WWW-Authenticate` challenge.
 *
 * @property scheme Authentication scheme, for example `Bearer`.
 * @property parameters Challenge parameters keyed by lower-case parameter name.
 */
public data class WwwAuthenticateChallenge(public val scheme: String, public val parameters: Map<String, String>) {
    /**
     * Returns a challenge parameter by name, using case-insensitive lookup.
     */
    public operator fun get(name: String): String? = parameters[name.lowercase()]
}

/**
 * PKCE material for an MCP OAuth authorization-code request.
 *
 * @property codeVerifier Secret verifier retained by the client until token exchange.
 * @property codeChallenge Public S256 challenge sent on the authorization request.
 * @property codeChallengeMethod PKCE challenge method. MCP uses `S256`.
 */
public data class McpOAuthPkce(
    public val codeVerifier: String,
    public val codeChallenge: String,
    public val codeChallengeMethod: String = "S256",
)

/**
 * Parameters for building an MCP OAuth authorization request URL.
 *
 * MCP clients must include the `resource` parameter when requesting tokens for
 * a protected MCP server.
 */
public data class McpOAuthAuthorizationRequest(
    public val authorizationEndpoint: String,
    public val clientId: String,
    public val redirectUri: String,
    public val codeChallenge: String,
    public val resource: String,
    public val scope: String? = null,
    public val state: String? = null,
)

/**
 * Parsed authorization-code redirect callback.
 *
 * @property code Authorization code returned by the authorization server.
 * @property state Optional state value returned by the authorization server.
 * @property rawParameters Complete query parameter map for fields not modeled by this SDK version.
 */
public data class McpOAuthAuthorizationCallback(
    public val code: String,
    public val state: String? = null,
    public val rawParameters: Map<String, List<String>> = emptyMap(),
)

/**
 * Inputs for preparing an MCP OAuth authorization-code flow.
 *
 * The SDK performs metadata discovery, PKCE validation, authorization URL construction,
 * token endpoint selection, and MCP-required `resource` handling. Applications still own
 * generating cryptographically secure PKCE/state values, opening the browser, receiving the
 * redirect, and verifying returned state before token exchange.
 *
 * @property serverUrl Protected MCP server URL.
 * @property clientCredentials OAuth client credentials or public client id.
 * @property redirectUri Redirect URI registered for the client.
 * @property pkce PKCE verifier/challenge material to use for this flow.
 * @property state Optional state value to send and later verify.
 * @property wwwAuthenticate Optional `WWW-Authenticate` header from a `401 Unauthorized` response.
 * @property resourceMetadataUrl Explicit Protected Resource Metadata URL. When absent, the
 * `resource_metadata` challenge parameter is used if present, then well-known discovery is used.
 * @property scope Optional requested scope used only when the challenge does not provide an
 * authoritative scope.
 * @property clientAssertionProvider Optional provider for `private_key_jwt` token endpoint auth.
 */
public data class McpOAuthAuthorizationCodeFlowRequest(
    public val serverUrl: String,
    public val clientCredentials: McpOAuthClientCredentials,
    public val redirectUri: String,
    public val pkce: McpOAuthPkce,
    public val state: String? = null,
    public val wwwAuthenticate: String? = null,
    public val resourceMetadataUrl: String? = null,
    public val scope: String? = null,
    public val clientAssertionProvider: McpOAuthClientAssertionProvider? = null,
)

/**
 * Prepared MCP OAuth authorization-code flow.
 *
 * Keep this value until the redirect callback is validated and the authorization code is exchanged.
 * It contains the PKCE verifier and selected token endpoint authentication method needed to complete
 * the flow.
 */
public data class McpOAuthPreparedAuthorizationCodeFlow(
    public val discovery: McpOAuthDiscoveryResult,
    public val resource: String,
    public val authorizationUrl: String,
    public val redirectUri: String,
    public val pkce: McpOAuthPkce,
    public val clientCredentials: McpOAuthClientCredentials,
    public val tokenEndpoint: String,
    public val tokenEndpointAuthMethod: McpOAuthTokenEndpointAuthMethod,
    public val state: String? = null,
    public val scope: String? = null,
    public val clientAssertionProvider: McpOAuthClientAssertionProvider? = null,
) {
    /**
     * Builds the token exchange request for an authorization code returned by the redirect.
     */
    public fun tokenRequest(code: String): McpOAuthAuthorizationCodeTokenRequest =
        McpOAuthAuthorizationCodeTokenRequest(
            tokenEndpoint = tokenEndpoint,
            code = code,
            redirectUri = redirectUri,
            codeVerifier = pkce.codeVerifier,
            resource = resource,
            clientCredentials = clientCredentials,
            tokenEndpointAuthMethod = tokenEndpointAuthMethod,
        )
}

/**
 * OAuth token endpoint client authentication methods supported by MCP auth helpers.
 */
public enum class McpOAuthTokenEndpointAuthMethod(public val wireValue: String) {
    /**
     * Send client credentials in the `Authorization: Basic ...` header.
     */
    ClientSecretBasic("client_secret_basic"),

    /**
     * Send client credentials in the form body.
     */
    ClientSecretPost("client_secret_post"),

    /**
     * Authenticate with a signed JWT client assertion in the form body.
     */
    PrivateKeyJwt("private_key_jwt"),

    /**
     * Public client; send `client_id` in the form body without a client secret.
     */
    None("none"),
}

/**
 * Client credentials used when exchanging an MCP OAuth authorization code.
 */
public data class McpOAuthClientCredentials(public val clientId: String, public val clientSecret: String? = null)

/**
 * Supplies a fresh signed JWT client assertion for `private_key_jwt` token endpoint authentication.
 *
 * The SDK adds the assertion to token requests but does not generate or sign JWTs.
 * Applications should create short-lived assertions with an OAuth/JWT library.
 */
public fun interface McpOAuthClientAssertionProvider {
    /**
     * Returns a signed JWT client assertion for the next token endpoint request.
     *
     * @return compact serialized JWT client assertion.
     */
    public suspend fun assertion(): String
}

/**
 * Client metadata hosted at an HTTPS `client_id` URL for OAuth Client ID Metadata Documents.
 */
public data class McpOAuthClientIdMetadataDocument(
    public val clientId: String,
    public val clientName: String,
    public val redirectUris: List<String>,
    public val tokenEndpointAuthMethod: McpOAuthTokenEndpointAuthMethod = McpOAuthTokenEndpointAuthMethod.None,
    public val grantTypes: List<String> = listOf("authorization_code"),
    public val responseTypes: List<String> = listOf("code"),
    public val clientUri: String? = null,
    public val logoUri: String? = null,
    public val jwksUri: String? = null,
    public val extraFields: JsonObject = JsonObject(emptyMap()),
) {
    init {
        require(clientName.isNotBlank()) { "clientName must not be blank" }
        require(redirectUris.isNotEmpty()) { "redirectUris must not be empty" }
        redirectUris.forEach(::requireMcpOAuthRedirectUri)
        requireClientIdMetadataDocumentUrl(clientId)
    }
}

/**
 * Client metadata used for OAuth Dynamic Client Registration.
 */
public data class McpOAuthDynamicClientRegistrationRequest(
    public val clientName: String,
    public val redirectUris: List<String>,
    public val tokenEndpointAuthMethod: McpOAuthTokenEndpointAuthMethod = McpOAuthTokenEndpointAuthMethod.None,
    public val grantTypes: List<String> = listOf("authorization_code"),
    public val responseTypes: List<String> = listOf("code"),
    public val scope: String? = null,
    public val clientUri: String? = null,
    public val logoUri: String? = null,
    public val jwksUri: String? = null,
    public val extraFields: JsonObject = JsonObject(emptyMap()),
) {
    init {
        require(clientName.isNotBlank()) { "clientName must not be blank" }
        require(redirectUris.isNotEmpty()) { "redirectUris must not be empty" }
        redirectUris.forEach(::requireMcpOAuthRedirectUri)
    }
}

/**
 * OAuth Dynamic Client Registration response.
 */
public data class McpOAuthDynamicClientRegistrationResponse(
    public val clientId: String,
    public val clientSecret: String? = null,
    public val clientSecretExpiresAt: Long? = null,
    public val registrationAccessToken: String? = null,
    public val registrationClientUri: String? = null,
    public val raw: JsonObject,
)

/**
 * Parameters for exchanging an MCP OAuth authorization code for tokens.
 *
 * MCP clients must include the `resource` parameter in token requests.
 */
public data class McpOAuthAuthorizationCodeTokenRequest(
    public val tokenEndpoint: String,
    public val code: String,
    public val redirectUri: String,
    public val codeVerifier: String,
    public val resource: String,
    public val clientCredentials: McpOAuthClientCredentials,
    public val tokenEndpointAuthMethod: McpOAuthTokenEndpointAuthMethod =
        McpOAuthTokenEndpointAuthMethod.ClientSecretBasic,
)

/**
 * Parameters for refreshing an MCP OAuth access token.
 *
 * MCP clients must include the `resource` parameter in token requests.
 */
public data class McpOAuthRefreshTokenRequest(
    public val tokenEndpoint: String,
    public val refreshToken: String,
    public val resource: String,
    public val scope: String? = null,
    public val clientCredentials: McpOAuthClientCredentials,
    public val tokenEndpointAuthMethod: McpOAuthTokenEndpointAuthMethod =
        McpOAuthTokenEndpointAuthMethod.ClientSecretBasic,
)

/**
 * Parameters for obtaining an MCP OAuth access token with the client credentials grant.
 *
 * This is intended for machine-to-machine MCP clients. User-delegated access should use
 * the authorization-code flow instead.
 */
public data class McpOAuthClientCredentialsTokenRequest(
    public val tokenEndpoint: String,
    public val resource: String,
    public val clientCredentials: McpOAuthClientCredentials,
    public val scope: String? = null,
    public val tokenEndpointAuthMethod: McpOAuthTokenEndpointAuthMethod =
        McpOAuthTokenEndpointAuthMethod.ClientSecretBasic,
)

/**
 * OAuth token response returned by an authorization server.
 */
public data class McpOAuthTokenResponse(
    public val accessToken: String,
    public val tokenType: String? = null,
    public val expiresIn: Int? = null,
    public val refreshToken: String? = null,
    public val scope: String? = null,
    public val raw: JsonObject,
)

/**
 * Returns the absolute expiry time for this response when [receivedAtEpochSeconds] is known.
 */
public fun McpOAuthTokenResponse.expiresAtEpochSeconds(receivedAtEpochSeconds: Long): Long? =
    expiresIn?.let { receivedAtEpochSeconds + it }

/**
 * Persistable OAuth token state that applications can write to an OS or service secret store.
 */
public data class McpOAuthTokenStoreSnapshot(
    public val accessToken: String,
    public val refreshToken: String? = null,
    public val tokenType: String? = null,
    public val expiresIn: Int? = null,
    public val expiresAtEpochSeconds: Long? = null,
    public val scope: String? = null,
    public val raw: JsonObject = JsonObject(emptyMap()),
) {
    /**
     * Converts this snapshot to a token response shape for initializing OAuth helpers.
     *
     * @return token response containing the access token, refresh token, and raw authorization-server fields.
     */
    public fun toTokenResponse(): McpOAuthTokenResponse = McpOAuthTokenResponse(
        accessToken = accessToken,
        tokenType = tokenType,
        expiresIn = expiresIn,
        refreshToken = refreshToken,
        scope = scope,
        raw = raw,
    )

    /**
     * Returns whether the access token should be refreshed before use.
     *
     * @param currentEpochSeconds current Unix time in seconds.
     * @param refreshSkewSeconds number of seconds before expiry to treat the token as refreshable.
     * @return `true` when an expiry is known and falls within the refresh window.
     */
    public fun shouldRefresh(currentEpochSeconds: Long, refreshSkewSeconds: Long = 60): Boolean =
        expiresAtEpochSeconds?.let { currentEpochSeconds + refreshSkewSeconds >= it } ?: false
}

/**
 * Encodes this token snapshot as a JSON object suitable for application-managed secure storage.
 *
 * Secret storage remains application-owned. Store the returned JSON in an OS keychain, service
 * secret manager, or equivalent protected storage rather than in a world-readable config file.
 */
public fun McpOAuthTokenStoreSnapshot.toMcpOAuthTokenStoreSnapshotJsonObject(): JsonObject = buildJsonObject {
    put("access_token", accessToken)
    refreshToken?.let { put("refresh_token", it) }
    tokenType?.let { put("token_type", it) }
    expiresIn?.let { put("expires_in", it) }
    expiresAtEpochSeconds?.let { put("expires_at_epoch_seconds", it) }
    scope?.let { put("scope", it) }
    if (raw.isNotEmpty()) {
        put("raw", raw)
    }
}

/**
 * Encodes this token snapshot as a JSON string suitable for application-managed secure storage.
 */
public fun McpOAuthTokenStoreSnapshot.toMcpOAuthTokenStoreSnapshotJsonString(): String =
    McpJson.encodeToString(toMcpOAuthTokenStoreSnapshotJsonObject())

/**
 * Decodes a token snapshot from a JSON object previously produced by
 * [McpOAuthTokenStoreSnapshot.toMcpOAuthTokenStoreSnapshotJsonObject].
 */
public fun mcpOAuthTokenStoreSnapshotFromJsonObject(jsonObject: JsonObject): McpOAuthTokenStoreSnapshot {
    val accessToken = jsonObject.stringOrNull("access_token")?.takeIf { it.isNotBlank() }
        ?: throw McpOAuthException("OAuth token snapshot does not include access_token")

    return McpOAuthTokenStoreSnapshot(
        accessToken = accessToken,
        refreshToken = jsonObject.stringOrNull("refresh_token"),
        tokenType = jsonObject.stringOrNull("token_type"),
        expiresIn = jsonObject["expires_in"]?.jsonPrimitive?.intOrNull,
        expiresAtEpochSeconds = jsonObject["expires_at_epoch_seconds"]?.jsonPrimitive?.longOrNull,
        scope = jsonObject.stringOrNull("scope"),
        raw = jsonObject["raw"]?.jsonObject ?: JsonObject(emptyMap()),
    )
}

/**
 * Decodes a token snapshot from a JSON string previously produced by
 * [McpOAuthTokenStoreSnapshot.toMcpOAuthTokenStoreSnapshotJsonString].
 */
public fun mcpOAuthTokenStoreSnapshotFromJsonString(jsonString: String): McpOAuthTokenStoreSnapshot =
    mcpOAuthTokenStoreSnapshotFromJsonObject(McpJson.parseToJsonElement(jsonString).jsonObject)

/**
 * Mutable OAuth token state for long-lived MCP HTTP transports.
 *
 * The refresh token is retained when an update response omits a replacement
 * refresh token, matching common OAuth refresh-token rotation behavior.
 */
public class McpOAuthTokenStore(initialTokens: McpOAuthTokenResponse) {
    private var onUpdate: ((McpOAuthTokenStoreSnapshot) -> Unit)? = null
    private var currentTokens: McpOAuthTokenResponse = initialTokens
    private var tokenExpiresAtEpochSeconds: Long? = null

    public constructor(initialSnapshot: McpOAuthTokenStoreSnapshot) : this(initialSnapshot.toTokenResponse()) {
        tokenExpiresAtEpochSeconds = initialSnapshot.expiresAtEpochSeconds
    }

    public constructor(
        initialTokens: McpOAuthTokenResponse,
        receivedAtEpochSeconds: Long,
    ) : this(initialTokens) {
        tokenExpiresAtEpochSeconds = initialTokens.expiresAtEpochSeconds(receivedAtEpochSeconds)
    }

    public constructor(
        initialTokens: McpOAuthTokenResponse,
        onUpdate: (McpOAuthTokenStoreSnapshot) -> Unit,
    ) : this(initialTokens) {
        this.onUpdate = onUpdate
    }

    public constructor(
        initialSnapshot: McpOAuthTokenStoreSnapshot,
        onUpdate: (McpOAuthTokenStoreSnapshot) -> Unit,
    ) : this(initialSnapshot) {
        this.onUpdate = onUpdate
    }

    public constructor(
        initialTokens: McpOAuthTokenResponse,
        receivedAtEpochSeconds: Long,
        onUpdate: (McpOAuthTokenStoreSnapshot) -> Unit,
    ) : this(initialTokens, receivedAtEpochSeconds) {
        this.onUpdate = onUpdate
    }

    public var accessToken: String = initialTokens.accessToken
        private set

    public var refreshToken: String? = initialTokens.refreshToken
        private set

    public val expiresAtEpochSeconds: Long?
        get() = tokenExpiresAtEpochSeconds

    /**
     * Replaces the current token response without recalculating an absolute expiry time.
     *
     * Existing refresh tokens are retained when [tokens] omits a replacement refresh token.
     *
     * @param tokens new token response.
     */
    public fun update(tokens: McpOAuthTokenResponse) {
        updateTokens(tokens, receivedAtEpochSeconds = null)
    }

    /**
     * Replaces the current token response and records its absolute expiry time.
     *
     * Existing refresh tokens are retained when [tokens] omits a replacement refresh token.
     *
     * @param tokens new token response.
     * @param receivedAtEpochSeconds Unix time in seconds when [tokens] was received.
     */
    public fun update(tokens: McpOAuthTokenResponse, receivedAtEpochSeconds: Long) {
        updateTokens(tokens, receivedAtEpochSeconds)
    }

    /**
     * Returns whether the stored access token should be refreshed before use.
     *
     * @param currentEpochSeconds current Unix time in seconds.
     * @param refreshSkewSeconds number of seconds before expiry to treat the token as refreshable.
     * @return `true` when an expiry is known and falls within the refresh window.
     */
    public fun shouldRefresh(currentEpochSeconds: Long, refreshSkewSeconds: Long = 60): Boolean =
        snapshot().shouldRefresh(currentEpochSeconds, refreshSkewSeconds)

    private fun updateTokens(tokens: McpOAuthTokenResponse, receivedAtEpochSeconds: Long?) {
        currentTokens = tokens.copy(refreshToken = tokens.refreshToken ?: refreshToken)
        accessToken = currentTokens.accessToken
        refreshToken = currentTokens.refreshToken
        tokenExpiresAtEpochSeconds = receivedAtEpochSeconds?.let { currentTokens.expiresAtEpochSeconds(it) }
        onUpdate?.invoke(snapshot())
    }

    /**
     * Returns the current token state as a persistable snapshot.
     *
     * @return snapshot containing current tokens, expiry, scope, and raw authorization-server fields.
     */
    public fun snapshot(): McpOAuthTokenStoreSnapshot = McpOAuthTokenStoreSnapshot(
        accessToken = accessToken,
        refreshToken = refreshToken,
        tokenType = currentTokens.tokenType,
        expiresIn = currentTokens.expiresIn,
        expiresAtEpochSeconds = tokenExpiresAtEpochSeconds,
        scope = currentTokens.scope,
        raw = currentTokens.raw,
    )
}

/**
 * Token provider for MCP OAuth client credentials flows.
 *
 * The provider obtains a token lazily and obtains a fresh token again when forced.
 * It does not persist credentials or tokens; applications should keep client secrets
 * in an appropriate secret store.
 */
public class McpOAuthClientCredentialsProvider(
    private val httpClient: HttpClient,
    private val request: McpOAuthClientCredentialsTokenRequest,
    private val clientAssertionProvider: McpOAuthClientAssertionProvider?,
) {
    public constructor(
        httpClient: HttpClient,
        request: McpOAuthClientCredentialsTokenRequest,
    ) : this(httpClient, request, null)

    private var tokenStore: McpOAuthTokenStore? = null

    /** Current access token, or `null` before the provider obtains one. */
    public val currentAccessToken: String?
        get() = tokenStore?.accessToken

    /** Obtains an access token, reusing the current token unless [forceRefresh] is true. */
    public suspend fun accessToken(forceRefresh: Boolean = false): String {
        val existing = tokenStore
        if (!forceRefresh && existing != null) {
            return existing.accessToken
        }

        val tokens = exchangeMcpOAuthClientCredentials(httpClient, request, clientAssertionProvider)
        tokenStore = McpOAuthTokenStore(tokens)
        return tokens.accessToken
    }
}

/**
 * Returns the canonical origin for [url], excluding the default port.
 */
public fun mcpOAuthOrigin(url: String): String {
    val parsed = Url(url)
    val port = if (parsed.port == parsed.protocol.defaultPort) "" else ":${parsed.port}"
    return "${parsed.protocol.name}://${parsed.host}$port"
}

/**
 * Builds a PKCE code verifier from caller-supplied cryptographically secure random bytes.
 *
 * Pass 32 to 96 bytes to produce a verifier within the RFC 7636 length range.
 */
public fun mcpPkceCodeVerifier(randomBytes: ByteArray): String {
    require(randomBytes.size in 32..96) {
        "PKCE code verifier requires 32 to 96 random bytes"
    }
    return base64UrlNoPadding(randomBytes)
}

/**
 * Builds the PKCE S256 code challenge for [codeVerifier].
 */
public fun mcpPkceCodeChallengeS256(codeVerifier: String): String {
    requireValidPkceVerifier(codeVerifier)
    return base64UrlNoPadding(sha256(codeVerifier.encodeToByteArray()))
}

/**
 * Builds a complete PKCE S256 pair from caller-supplied cryptographically secure random bytes.
 */
public fun mcpPkceS256(randomBytes: ByteArray): McpOAuthPkce {
    val verifier = mcpPkceCodeVerifier(randomBytes)
    return McpOAuthPkce(
        codeVerifier = verifier,
        codeChallenge = mcpPkceCodeChallengeS256(verifier),
    )
}

/**
 * Ensures that authorization server metadata advertises PKCE S256 support.
 */
public fun requireMcpPkceS256Support(metadata: OAuthAuthorizationServerMetadata) {
    val methods = metadata.codeChallengeMethodsSupported
    if (methods == null || methods.none { it == "S256" }) {
        throw McpOAuthException(
            "Authorization server does not support PKCE S256 (code_challenge_methods_supported: $methods)",
        )
    }
}

/**
 * Builds an MCP OAuth authorization URL for an authorization-code request.
 */
public fun buildMcpOAuthAuthorizationUrl(request: McpOAuthAuthorizationRequest): String {
    requireMcpOAuthAuthorizationEndpoint(request.authorizationEndpoint)
    requireMcpOAuthRedirectUri(request.redirectUri)

    val builder = URLBuilder(request.authorizationEndpoint)
    builder.parameters.append("response_type", "code")
    builder.parameters.append("client_id", request.clientId)
    builder.parameters.append("redirect_uri", request.redirectUri)
    builder.parameters.append("code_challenge", request.codeChallenge)
    builder.parameters.append("code_challenge_method", "S256")
    builder.parameters.append("resource", request.resource)
    request.state?.let { builder.parameters.append("state", it) }
    request.scope?.let { builder.parameters.append("scope", it) }
    return builder.buildString()
}

/**
 * Parses and validates an OAuth authorization-code redirect callback URL.
 *
 * If [expectedState] is supplied, the callback must include the same `state` value. OAuth error
 * callbacks are converted to [McpOAuthException]. Applications should call this after receiving
 * the redirect and before exchanging the authorization code for tokens.
 */
public fun parseMcpOAuthAuthorizationCallback(
    callbackUrl: String,
    expectedState: String? = null,
): McpOAuthAuthorizationCallback {
    val url = Url(callbackUrl)
    val parameters = url.parameters
    val error = parameters["error"]
    if (error != null) {
        val errorDescription = parameters["error_description"]
        val errorUri = parameters["error_uri"]
        val errorDetail = listOfNotNull(error, errorDescription, errorUri).joinToString(": ")
        throw McpOAuthException("Authorization callback failed: $errorDetail")
    }

    val code = parameters["code"]?.takeIf { it.isNotBlank() }
        ?: throw McpOAuthException("Authorization callback does not include code")
    val state = parameters["state"]
    if (expectedState != null && state != expectedState) {
        throw McpOAuthException("Authorization callback state mismatch")
    }

    return McpOAuthAuthorizationCallback(
        code = code,
        state = state,
        rawParameters = parameters.names().associateWith { name -> parameters.getAll(name).orEmpty() },
    )
}

/**
 * Parses an OAuth authorization-code redirect callback URL and validates it against a prepared flow.
 */
public fun parseMcpOAuthAuthorizationCallback(
    callbackUrl: String,
    preparedFlow: McpOAuthPreparedAuthorizationCodeFlow,
): McpOAuthAuthorizationCallback = parseMcpOAuthAuthorizationCallback(
    callbackUrl = callbackUrl,
    expectedState = preparedFlow.state,
)

/**
 * Discovers metadata and prepares an MCP OAuth authorization-code flow.
 *
 * The returned [McpOAuthPreparedAuthorizationCodeFlow.authorizationUrl] is ready to open in a
 * browser. After the application validates the redirect state and obtains an authorization code,
 * call [exchangeMcpOAuthAuthorizationCode] with the prepared flow.
 */
public suspend fun prepareMcpOAuthAuthorizationCodeFlow(
    httpClient: HttpClient,
    request: McpOAuthAuthorizationCodeFlowRequest,
): McpOAuthPreparedAuthorizationCodeFlow {
    val discovery = discoverMcpOAuthMetadata(
        httpClient = httpClient,
        serverUrl = request.serverUrl,
        resourceMetadataUrl = request.resourceMetadataUrl
            ?: wwwAuthenticateParameter(request.wwwAuthenticate, "resource_metadata"),
    )
    val authorizationServer = discovery.authorizationServerMetadata
    requireMcpPkceS256Support(authorizationServer)

    val authorizationEndpoint = authorizationServer.authorizationEndpoint
        ?: throw McpOAuthException("Authorization server metadata does not include authorization_endpoint")
    val tokenEndpoint = authorizationServer.tokenEndpoint
        ?: throw McpOAuthException("Authorization server metadata does not include token_endpoint")
    val resource = discovery.resourceMetadata.resource ?: request.serverUrl
    val scope = wwwAuthenticateParameter(request.wwwAuthenticate, "scope")
        ?: request.scope
        ?: discovery.resourceMetadata.scopesSupported?.takeIf { it.isNotEmpty() }?.joinToString(" ")
    val tokenEndpointAuthMethod = selectMcpOAuthTokenEndpointAuthMethod(
        metadata = authorizationServer,
        clientSecret = request.clientCredentials.clientSecret,
        clientAssertionProvider = request.clientAssertionProvider,
    )

    val authorizationUrl = buildMcpOAuthAuthorizationUrl(
        McpOAuthAuthorizationRequest(
            authorizationEndpoint = authorizationEndpoint,
            clientId = request.clientCredentials.clientId,
            redirectUri = request.redirectUri,
            codeChallenge = request.pkce.codeChallenge,
            resource = resource,
            scope = scope,
            state = request.state,
        ),
    )

    return McpOAuthPreparedAuthorizationCodeFlow(
        discovery = discovery,
        resource = resource,
        authorizationUrl = authorizationUrl,
        redirectUri = request.redirectUri,
        pkce = request.pkce,
        clientCredentials = request.clientCredentials,
        tokenEndpoint = tokenEndpoint,
        tokenEndpointAuthMethod = tokenEndpointAuthMethod,
        state = request.state,
        scope = scope,
        clientAssertionProvider = request.clientAssertionProvider,
    )
}

/**
 * Selects a token endpoint authentication method from authorization server metadata.
 */
public fun selectMcpOAuthTokenEndpointAuthMethod(
    metadata: OAuthAuthorizationServerMetadata,
    clientSecret: String?,
): McpOAuthTokenEndpointAuthMethod = selectMcpOAuthTokenEndpointAuthMethod(
    metadata = metadata,
    clientSecret = clientSecret,
    clientAssertionProvider = null,
)

/**
 * Selects a token endpoint authentication method from authorization server metadata.
 */
public fun selectMcpOAuthTokenEndpointAuthMethod(
    metadata: OAuthAuthorizationServerMetadata,
    clientSecret: String?,
    clientAssertionProvider: McpOAuthClientAssertionProvider?,
): McpOAuthTokenEndpointAuthMethod {
    val methods = metadata.tokenEndpointAuthMethodsSupported
        ?.mapNotNull { tokenEndpointAuthMethodFromWireValueOrNull(it) }
        ?: listOf(McpOAuthTokenEndpointAuthMethod.ClientSecretBasic)
    val usableMethods = methods.filter {
        when (it) {
            McpOAuthTokenEndpointAuthMethod.ClientSecretBasic,
            McpOAuthTokenEndpointAuthMethod.ClientSecretPost,
            -> clientSecret != null

            McpOAuthTokenEndpointAuthMethod.PrivateKeyJwt -> clientAssertionProvider != null

            McpOAuthTokenEndpointAuthMethod.None -> clientSecret == null && clientAssertionProvider == null
        }
    }.ifEmpty {
        if (clientSecret != null && clientAssertionProvider == null) {
            methods.filter { it == McpOAuthTokenEndpointAuthMethod.None }
        } else {
            emptyList()
        }
    }
    return usableMethods.firstOrNull()
        ?: throw McpOAuthException(
            "No supported token endpoint auth method for advertised methods " +
                "${metadata.tokenEndpointAuthMethodsSupported}",
        )
}

/**
 * Returns true when authorization server metadata advertises Client ID Metadata Document support.
 */
public fun supportsMcpOAuthClientIdMetadataDocuments(metadata: OAuthAuthorizationServerMetadata): Boolean =
    metadata.clientIdMetadataDocumentSupported == true

/**
 * Builds the JSON document to host at a Client ID Metadata Document URL.
 */
public fun buildMcpOAuthClientIdMetadataDocumentJson(document: McpOAuthClientIdMetadataDocument): JsonObject =
    document.toJsonObject()

/**
 * Registers an OAuth client using a discovered Dynamic Client Registration endpoint.
 */
public suspend fun registerMcpOAuthClient(
    httpClient: HttpClient,
    registrationEndpoint: String,
    request: McpOAuthDynamicClientRegistrationRequest,
): McpOAuthDynamicClientRegistrationResponse {
    val response = httpClient.post(registrationEndpoint) {
        setBody(TextContent(request.toJsonObject().toString(), ContentType.Application.Json))
    }
    val body = response.bodyAsText()
    val json = try {
        McpJson.parseToJsonElement(body).jsonObject
    } catch (e: Exception) {
        throw McpOAuthException("Failed to parse client registration response from $registrationEndpoint", e)
    }

    val responseError = json.stringOrNull("error")
    if (!response.status.isSuccess() || responseError != null) {
        val errorDescription = json.stringOrNull("error_description")
        val errorDetail = listOfNotNull(responseError, errorDescription).joinToString(": ")
            .ifEmpty { body }
        throw McpOAuthException("Client registration failed (${response.status}): $errorDetail")
    }

    return json.toMcpOAuthDynamicClientRegistrationResponse()
}

/**
 * Exchanges an authorization code for OAuth tokens using MCP-required token request parameters.
 */
public suspend fun exchangeMcpOAuthAuthorizationCode(
    httpClient: HttpClient,
    request: McpOAuthAuthorizationCodeTokenRequest,
): McpOAuthTokenResponse = exchangeMcpOAuthAuthorizationCode(
    httpClient = httpClient,
    request = request,
    clientAssertionProvider = null,
)

/**
 * Exchanges an authorization code for OAuth tokens using an optional JWT client assertion.
 */
public suspend fun exchangeMcpOAuthAuthorizationCode(
    httpClient: HttpClient,
    request: McpOAuthAuthorizationCodeTokenRequest,
    clientAssertionProvider: McpOAuthClientAssertionProvider?,
): McpOAuthTokenResponse {
    requireValidPkceVerifier(request.codeVerifier)
    val response = submitMcpOAuthTokenRequest(
        httpClient = httpClient,
        tokenEndpoint = request.tokenEndpoint,
        formParameters = request.tokenFormParameters(),
        clientCredentials = request.clientCredentials,
        tokenEndpointAuthMethod = request.tokenEndpointAuthMethod,
        clientAssertionProvider = clientAssertionProvider,
    )

    val body = response.bodyAsText()
    val json = try {
        McpJson.parseToJsonElement(body).jsonObject
    } catch (e: Exception) {
        throw McpOAuthException("Failed to parse token response from ${request.tokenEndpoint}", e)
    }

    val responseError = json.stringOrNull("error")
    if (!response.status.isSuccess() || responseError != null) {
        val errorDescription = json.stringOrNull("error_description")
        val errorDetail = listOfNotNull(responseError, errorDescription).joinToString(": ")
            .ifEmpty { body }
        throw McpOAuthException("Token exchange failed (${response.status}): $errorDetail")
    }

    return json.toMcpOAuthTokenResponse()
}

/**
 * Completes a prepared MCP OAuth authorization-code flow after redirect state validation.
 */
public suspend fun exchangeMcpOAuthAuthorizationCode(
    httpClient: HttpClient,
    preparedFlow: McpOAuthPreparedAuthorizationCodeFlow,
    code: String,
): McpOAuthTokenResponse = exchangeMcpOAuthAuthorizationCode(
    httpClient = httpClient,
    request = preparedFlow.tokenRequest(code),
    clientAssertionProvider = preparedFlow.clientAssertionProvider,
)

/**
 * Refreshes an MCP OAuth access token using the refresh-token grant.
 */
public suspend fun refreshMcpOAuthAccessToken(
    httpClient: HttpClient,
    request: McpOAuthRefreshTokenRequest,
): McpOAuthTokenResponse {
    val response = submitMcpOAuthTokenRequest(
        httpClient = httpClient,
        tokenEndpoint = request.tokenEndpoint,
        formParameters = request.tokenFormParameters(),
        clientCredentials = request.clientCredentials,
        tokenEndpointAuthMethod = request.tokenEndpointAuthMethod,
    )

    val body = response.bodyAsText()
    val json = try {
        McpJson.parseToJsonElement(body).jsonObject
    } catch (e: Exception) {
        throw McpOAuthException("Failed to parse token response from ${request.tokenEndpoint}", e)
    }

    val responseError = json.stringOrNull("error")
    if (!response.status.isSuccess() || responseError != null) {
        val errorDescription = json.stringOrNull("error_description")
        val errorDetail = listOfNotNull(responseError, errorDescription).joinToString(": ")
            .ifEmpty { body }
        throw McpOAuthException("Token refresh failed (${response.status}): $errorDetail")
    }

    return json.toMcpOAuthTokenResponse()
}

/**
 * Exchanges MCP OAuth client credentials for an access token.
 */
public suspend fun exchangeMcpOAuthClientCredentials(
    httpClient: HttpClient,
    request: McpOAuthClientCredentialsTokenRequest,
): McpOAuthTokenResponse = exchangeMcpOAuthClientCredentials(
    httpClient = httpClient,
    request = request,
    clientAssertionProvider = null,
)

/**
 * Exchanges MCP OAuth client credentials for an access token using an optional JWT client assertion.
 */
public suspend fun exchangeMcpOAuthClientCredentials(
    httpClient: HttpClient,
    request: McpOAuthClientCredentialsTokenRequest,
    clientAssertionProvider: McpOAuthClientAssertionProvider?,
): McpOAuthTokenResponse {
    val response = submitMcpOAuthTokenRequest(
        httpClient = httpClient,
        tokenEndpoint = request.tokenEndpoint,
        formParameters = request.tokenFormParameters(),
        clientCredentials = request.clientCredentials,
        tokenEndpointAuthMethod = request.tokenEndpointAuthMethod,
        clientAssertionProvider = clientAssertionProvider,
    )

    val body = response.bodyAsText()
    val json = try {
        McpJson.parseToJsonElement(body).jsonObject
    } catch (e: Exception) {
        throw McpOAuthException("Failed to parse token response from ${request.tokenEndpoint}", e)
    }

    val responseError = json.stringOrNull("error")
    if (!response.status.isSuccess() || responseError != null) {
        val errorDescription = json.stringOrNull("error_description")
        val errorDetail = listOfNotNull(responseError, errorDescription).joinToString(": ")
            .ifEmpty { body }
        throw McpOAuthException("Client credentials token exchange failed (${response.status}): $errorDetail")
    }

    return json.toMcpOAuthTokenResponse()
}

/**
 * Returns protected resource metadata discovery URLs for [serverUrl] in MCP-specified priority order.
 */
public fun mcpProtectedResourceMetadataUrls(serverUrl: String): List<String> {
    val parsed = Url(serverUrl)
    val origin = mcpOAuthOrigin(serverUrl)
    val path = parsed.encodedPath.ifBlank { "/" }
    val pathSpecific = "$origin/.well-known/oauth-protected-resource${if (path == "/") "" else path}"
    val root = "$origin/.well-known/oauth-protected-resource"
    return listOf(pathSpecific, root).distinct()
}

/**
 * Returns OAuth Authorization Server Metadata discovery URLs for [authorizationServerUrl]
 * in MCP-specified priority order.
 */
public fun mcpAuthorizationServerMetadataUrls(authorizationServerUrl: String): List<String> {
    val parsed = Url(authorizationServerUrl)
    val origin = mcpOAuthOrigin(authorizationServerUrl)
    val path = parsed.encodedPath.ifBlank { "/" }
    val hasPath = path != "/"
    return buildList {
        add("$origin/.well-known/oauth-authorization-server${if (hasPath) path else ""}")
        add("$origin/.well-known/openid-configuration${if (hasPath) path else ""}")
        if (hasPath) {
            add("$origin$path/.well-known/openid-configuration")
        }
    }.distinct()
}

/**
 * Fetches OAuth 2.0 Protected Resource Metadata for [serverUrl].
 *
 * If [resourceMetadataUrl] was supplied in a `WWW-Authenticate` challenge, it is tried first
 * and no well-known fallback is attempted on failure.
 */
public suspend fun discoverMcpProtectedResourceMetadata(
    httpClient: HttpClient,
    serverUrl: String,
    resourceMetadataUrl: String? = null,
): OAuthProtectedResourceMetadata {
    val urls = resourceMetadataUrl?.let { listOf(it) } ?: mcpProtectedResourceMetadataUrls(serverUrl)
    val raw = fetchFirstJsonObject(httpClient, urls, "protected resource metadata")
    return raw.toOAuthProtectedResourceMetadata()
}

/**
 * Fetches OAuth Authorization Server Metadata for [authorizationServerUrl].
 */
public suspend fun discoverMcpAuthorizationServerMetadata(
    httpClient: HttpClient,
    authorizationServerUrl: String,
): OAuthAuthorizationServerMetadata {
    val raw = fetchFirstJsonObject(
        httpClient = httpClient,
        urls = mcpAuthorizationServerMetadataUrls(authorizationServerUrl),
        description = "authorization server metadata",
    )
    return raw.toOAuthAuthorizationServerMetadata()
}

/**
 * Discovers protected resource metadata and authorization server metadata for [serverUrl].
 */
public suspend fun discoverMcpOAuthMetadata(
    httpClient: HttpClient,
    serverUrl: String,
    resourceMetadataUrl: String? = null,
): McpOAuthDiscoveryResult {
    val resourceMetadata = discoverMcpProtectedResourceMetadata(httpClient, serverUrl, resourceMetadataUrl)
    val authorizationServer = resourceMetadata.authorizationServers.firstOrNull()
        ?: throw McpOAuthException("Protected resource metadata does not advertise authorization_servers")
    val authorizationServerMetadata = discoverMcpAuthorizationServerMetadata(httpClient, authorizationServer)
    return McpOAuthDiscoveryResult(resourceMetadata, authorizationServerMetadata)
}

/**
 * Parses one or more `WWW-Authenticate` challenges.
 */
public fun parseWwwAuthenticate(header: String): List<WwwAuthenticateChallenge> {
    val challenges = mutableListOf<WwwAuthenticateChallenge>()
    var index = 0
    while (index < header.length) {
        while (index < header.length && (header[index].isWhitespace() || header[index] == ',')) index++
        val schemeStart = index
        while (index < header.length && !header[index].isWhitespace() && header[index] != ',') index++
        if (schemeStart == index) break

        val scheme = header.substring(schemeStart, index)
        while (index < header.length && header[index].isWhitespace()) index++

        val paramsStart = index
        var inQuotes = false
        while (index < header.length) {
            val c = header[index]
            if (c == '"') inQuotes = !inQuotes
            if (!inQuotes && c == ',' && looksLikeChallengeStart(header, index + 1)) break
            index++
        }

        val params = parseChallengeParameters(header.substring(paramsStart, index))
        challenges += WwwAuthenticateChallenge(scheme = scheme, parameters = params)
        if (index < header.length && header[index] == ',') index++
    }
    return challenges
}

/**
 * Extracts a parameter from the first matching `WWW-Authenticate` challenge.
 */
public fun wwwAuthenticateParameter(header: String?, parameter: String, scheme: String = "Bearer"): String? {
    if (header == null) return null
    return parseWwwAuthenticate(header)
        .firstOrNull { it.scheme.equals(scheme, ignoreCase = true) }
        ?.get(parameter)
}

/**
 * Selects scopes for an MCP OAuth authorization request.
 *
 * The `scope` value from `WWW-Authenticate` is authoritative when present. If absent,
 * all `scopes_supported` values from Protected Resource Metadata are joined with spaces.
 * If neither is present, `null` is returned so callers can omit the `scope` parameter.
 */
public fun selectMcpOAuthScope(wwwAuthenticateScope: String?, scopesSupported: List<String>?): String? =
    wwwAuthenticateScope ?: scopesSupported?.takeIf { it.isNotEmpty() }?.joinToString(" ")

/**
 * Returns the requested step-up scope from an insufficient-scope challenge, if present.
 */
public fun mcpOAuthStepUpScope(wwwAuthenticate: String?): String? {
    val error = wwwAuthenticateParameter(wwwAuthenticate, "error")
    if (error != "insufficient_scope") return null
    return wwwAuthenticateParameter(wwwAuthenticate, "scope")
}

/**
 * Identifies a step-up authorization retry attempt.
 *
 * @property resource MCP resource server URL or resource identifier being accessed.
 * @property operation Stable operation identifier chosen by the application, for example a tool
 * name, resource URI, prompt name, or request type.
 * @property scope Scope string requested by an `insufficient_scope` challenge.
 */
public data class McpOAuthStepUpRetryKey(
    public val resource: String,
    public val operation: String,
    public val scope: String,
)

/**
 * Tracks bounded step-up authorization attempts.
 *
 * MCP clients should avoid repeating step-up authorization for the same resource and operation
 * after it has already failed. This tracker records attempts by resource, operation, and scope so
 * applications can stop retry loops and surface a permanent authorization failure.
 */
public class McpOAuthStepUpRetryTracker(private val maxAttempts: Int = 2) {
    private val attempts = mutableMapOf<McpOAuthStepUpRetryKey, Int>()

    init {
        require(maxAttempts > 0) {
            "maxAttempts must be positive"
        }
    }

    /**
     * Records and allows an attempt when the retry limit has not been reached.
     */
    public fun tryRecordAttempt(resource: String, operation: String, scope: String): Boolean =
        tryRecordAttempt(McpOAuthStepUpRetryKey(resource, operation, scope))

    /**
     * Records and allows an attempt when the retry limit has not been reached.
     */
    public fun tryRecordAttempt(key: McpOAuthStepUpRetryKey): Boolean {
        val currentAttempts = attempts[key] ?: 0
        if (currentAttempts >= maxAttempts) {
            return false
        }
        attempts[key] = currentAttempts + 1
        return true
    }

    /**
     * Returns the number of attempts recorded for [key].
     */
    public fun attemptsFor(key: McpOAuthStepUpRetryKey): Int = attempts[key] ?: 0

    /**
     * Clears attempt history for a specific key, for example after a successful operation.
     */
    public fun reset(key: McpOAuthStepUpRetryKey) {
        attempts.remove(key)
    }

    /**
     * Clears all tracked attempt history.
     */
    public fun resetAll() {
        attempts.clear()
    }
}

/**
 * Returns a request builder that applies a bearer token to every outgoing HTTP request.
 */
public fun mcpBearerAuth(accessToken: String): HttpRequestBuilder.() -> Unit = mcpBearerAuth { accessToken }

/**
 * Applies the current OAuth bearer token to an outgoing HTTP request.
 *
 * Use this overload when access tokens can change over the lifetime of a transport,
 * for example after refreshing an expired token.
 */
public fun mcpBearerAuth(accessTokenProvider: () -> String): HttpRequestBuilder.() -> Unit = {
    headers.remove(HttpHeaders.Authorization)
    headers.append(HttpHeaders.Authorization, "Bearer ${accessTokenProvider()}")
}

/**
 * Installs bearer authentication for an MCP HTTP client and refreshes once on `401 Unauthorized`.
 *
 * This is intended for the Ktor [HttpClient] used by HTTP-based MCP transports. The [refresh]
 * callback should use the stored refresh token to obtain a new access token, usually by calling
 * [refreshMcpOAuthAccessToken] with a client that does not send MCP resource-server bearer tokens
 * to the authorization server. If no refresh token is available, the original 401 response is
 * returned without retrying.
 */
public fun HttpClient.installMcpOAuthBearerAuth(
    tokenStore: McpOAuthTokenStore,
    refresh: suspend (refreshToken: String) -> McpOAuthTokenResponse,
) {
    plugin(HttpSend).intercept { request ->
        mcpBearerAuth { tokenStore.accessToken }(request)
        val response = execute(request)
        if (response.response.status != HttpStatusCode.Unauthorized) {
            return@intercept response
        }

        val refreshToken = tokenStore.refreshToken ?: return@intercept response
        tokenStore.update(refresh(refreshToken))

        mcpBearerAuth { tokenStore.accessToken }(request)
        execute(request)
    }
}

/**
 * Installs bearer authentication backed by an MCP OAuth client credentials provider.
 *
 * The provider obtains a token before the first request. If the MCP server returns
 * `401 Unauthorized`, the provider obtains a fresh token and retries once.
 */
public fun HttpClient.installMcpOAuthClientCredentials(provider: McpOAuthClientCredentialsProvider) {
    plugin(HttpSend).intercept { request ->
        mcpBearerAuth(provider.accessToken())(request)
        val response = execute(request)
        if (response.response.status != HttpStatusCode.Unauthorized) {
            return@intercept response
        }

        mcpBearerAuth(provider.accessToken(forceRefresh = true))(request)
        execute(request)
    }
}

/**
 * Returns the capability map entry used to declare OAuth client credentials support.
 */
public fun mcpOAuthClientCredentialsExtension(): Map<String, JsonObject> =
    mapOf(MCP_OAUTH_CLIENT_CREDENTIALS_EXTENSION to EmptyJsonObject)

/**
 * Returns a copy of these capabilities declaring OAuth client credentials support.
 */
public fun ClientCapabilities.withMcpOAuthClientCredentialsExtension(): ClientCapabilities =
    copy(extensions = extensions.orEmpty() + mcpOAuthClientCredentialsExtension())

private suspend fun fetchFirstJsonObject(httpClient: HttpClient, urls: List<String>, description: String): JsonObject {
    val failures = mutableListOf<String>()
    for (url in urls) {
        val response = httpClient.get(url)
        if (response.status.isSuccess()) {
            return try {
                McpJson.parseToJsonElement(response.bodyAsText()).jsonObject
            } catch (e: Exception) {
                throw McpOAuthException("Failed to parse $description from $url", e)
            }
        }
        failures += "$url (${response.status})"
    }
    throw McpOAuthException("Failed to fetch $description: ${failures.joinToString()}")
}

private fun JsonObject.toOAuthProtectedResourceMetadata(): OAuthProtectedResourceMetadata =
    OAuthProtectedResourceMetadata(
        resource = stringOrNull("resource"),
        authorizationServers = stringListOrNull("authorization_servers").orEmpty(),
        scopesSupported = stringListOrNull("scopes_supported"),
        raw = this,
    )

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

private fun JsonObject.toMcpOAuthTokenResponse(): McpOAuthTokenResponse = McpOAuthTokenResponse(
    accessToken = stringOrNull("access_token")
        ?: throw McpOAuthException("Token response does not include access_token"),
    tokenType = stringOrNull("token_type"),
    expiresIn = this["expires_in"]?.jsonPrimitive?.intOrNull,
    refreshToken = stringOrNull("refresh_token"),
    scope = stringOrNull("scope"),
    raw = this,
)

private fun McpOAuthClientIdMetadataDocument.toJsonObject(): JsonObject = buildJsonObject {
    extraFields.forEach { (key, value) -> put(key, value) }
    put("client_id", clientId)
    put("client_name", clientName)
    putJsonArray("redirect_uris", redirectUris)
    put("token_endpoint_auth_method", tokenEndpointAuthMethod.wireValue)
    putJsonArray("grant_types", grantTypes)
    putJsonArray("response_types", responseTypes)
    clientUri?.let { put("client_uri", it) }
    logoUri?.let { put("logo_uri", it) }
    jwksUri?.let { put("jwks_uri", it) }
}

private fun McpOAuthDynamicClientRegistrationRequest.toJsonObject(): JsonObject = buildJsonObject {
    extraFields.forEach { (key, value) -> put(key, value) }
    put("client_name", clientName)
    putJsonArray("redirect_uris", redirectUris)
    put("token_endpoint_auth_method", tokenEndpointAuthMethod.wireValue)
    putJsonArray("grant_types", grantTypes)
    putJsonArray("response_types", responseTypes)
    scope?.let { put("scope", it) }
    clientUri?.let { put("client_uri", it) }
    logoUri?.let { put("logo_uri", it) }
    jwksUri?.let { put("jwks_uri", it) }
}

private fun JsonObject.toMcpOAuthDynamicClientRegistrationResponse(): McpOAuthDynamicClientRegistrationResponse =
    McpOAuthDynamicClientRegistrationResponse(
        clientId = stringOrNull("client_id")
            ?: throw McpOAuthException("Client registration response does not include client_id"),
        clientSecret = stringOrNull("client_secret"),
        clientSecretExpiresAt = this["client_secret_expires_at"]?.jsonPrimitive?.longOrNull,
        registrationAccessToken = stringOrNull("registration_access_token"),
        registrationClientUri = stringOrNull("registration_client_uri"),
        raw = this,
    )

private fun tokenEndpointAuthMethodFromWireValueOrNull(value: String): McpOAuthTokenEndpointAuthMethod? =
    McpOAuthTokenEndpointAuthMethod.entries.firstOrNull { it.wireValue == value }

private fun requireClientIdMetadataDocumentUrl(clientId: String) {
    val url = Url(clientId)
    require(url.protocol.name == "https") { "clientId metadata document URL must use https" }
    require(url.encodedPath.isNotBlank() && url.encodedPath != "/") {
        "clientId metadata document URL must include a path component"
    }
}

private fun requireMcpOAuthAuthorizationEndpoint(value: String) {
    val url = Url(value)
    val isHttps = url.protocol.name == "https"
    val isLoopbackHttp = url.protocol.name == "http" && url.host.isMcpOAuthLoopbackHost()
    require((isHttps || isLoopbackHttp) && url.host.isNotBlank()) {
        "OAuth authorization endpoint must use https, or http for localhost, 127.0.0.1, or ::1"
    }
    require(!authorityHasUserInfo(value)) {
        "OAuth authorization endpoint must not include user info"
    }
}

private fun requireMcpOAuthRedirectUri(value: String) {
    val url = Url(value)
    val isHttps = url.protocol.name == "https"
    val isLoopbackHttp = url.protocol.name == "http" && url.host.isMcpOAuthLoopbackHost()
    require((isHttps || isLoopbackHttp) && url.host.isNotBlank()) {
        "OAuth redirect URI must use https, or http for localhost, 127.0.0.1, or ::1"
    }
    require(!authorityHasUserInfo(value)) {
        "OAuth redirect URI must not include user info"
    }
}

private fun String.isMcpOAuthLoopbackHost(): Boolean = when (lowercase()) {
    "localhost", "127.0.0.1", "::1", "[::1]" -> true
    else -> false
}

private fun authorityHasUserInfo(value: String): Boolean {
    val authorityStart = value.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return false
    val authorityEnd = value.indexOfAny(charArrayOf('/', '?', '#'), startIndex = authorityStart)
        .takeIf { it >= 0 }
        ?: value.length
    return '@' in value.substring(authorityStart, authorityEnd)
}

private fun JsonObjectBuilder.putJsonArray(key: String, values: List<String>) {
    put(
        key,
        buildJsonArray {
            values.forEach { add(JsonPrimitive(it)) }
        },
    )
}

private suspend fun submitMcpOAuthTokenRequest(
    httpClient: HttpClient,
    tokenEndpoint: String,
    formParameters: Parameters,
    clientCredentials: McpOAuthClientCredentials,
    tokenEndpointAuthMethod: McpOAuthTokenEndpointAuthMethod,
    clientAssertionProvider: McpOAuthClientAssertionProvider? = null,
): HttpResponse = when (tokenEndpointAuthMethod) {
    McpOAuthTokenEndpointAuthMethod.ClientSecretBasic -> httpClient.submitForm(
        url = tokenEndpoint,
        formParameters = formParameters,
    ) {
        header(
            HttpHeaders.Authorization,
            clientCredentials.basicAuthorizationHeader(McpOAuthTokenEndpointAuthMethod.ClientSecretBasic),
        )
    }

    McpOAuthTokenEndpointAuthMethod.ClientSecretPost -> httpClient.submitForm(
        url = tokenEndpoint,
        formParameters = formParameters.withClientCredentials(clientCredentials, tokenEndpointAuthMethod),
    )

    McpOAuthTokenEndpointAuthMethod.PrivateKeyJwt -> httpClient.submitForm(
        url = tokenEndpoint,
        formParameters = formParameters.withClientAssertion(
            clientCredentials = clientCredentials,
            clientAssertion = clientAssertionProvider?.assertion()
                ?: throw McpOAuthException("private_key_jwt requires a client assertion provider"),
        ),
    )

    McpOAuthTokenEndpointAuthMethod.None -> httpClient.submitForm(
        url = tokenEndpoint,
        formParameters = formParameters.withClientCredentials(clientCredentials, tokenEndpointAuthMethod),
    )
}

private fun McpOAuthAuthorizationCodeTokenRequest.tokenFormParameters(): Parameters = Parameters.build {
    append("grant_type", "authorization_code")
    append("code", code)
    append("redirect_uri", redirectUri)
    append("code_verifier", codeVerifier)
    append("resource", resource)
}

private fun McpOAuthRefreshTokenRequest.tokenFormParameters(): Parameters = Parameters.build {
    append("grant_type", "refresh_token")
    append("refresh_token", refreshToken)
    append("resource", resource)
    scope?.let { append("scope", it) }
}

private fun McpOAuthClientCredentialsTokenRequest.tokenFormParameters(): Parameters = Parameters.build {
    append("grant_type", "client_credentials")
    append("resource", resource)
    scope?.let { append("scope", it) }
}

private fun Parameters.withClientCredentials(
    clientCredentials: McpOAuthClientCredentials,
    tokenEndpointAuthMethod: McpOAuthTokenEndpointAuthMethod,
): Parameters = Parameters.build {
    appendAll(this@withClientCredentials)
    append("client_id", clientCredentials.clientId)
    if (tokenEndpointAuthMethod != McpOAuthTokenEndpointAuthMethod.None) {
        append("client_secret", clientCredentials.requireClientSecret(tokenEndpointAuthMethod))
    }
}

private fun Parameters.withClientAssertion(
    clientCredentials: McpOAuthClientCredentials,
    clientAssertion: String,
): Parameters = Parameters.build {
    appendAll(this@withClientAssertion)
    append("client_id", clientCredentials.clientId)
    append("client_assertion_type", MCP_OAUTH_JWT_BEARER_CLIENT_ASSERTION_TYPE)
    append("client_assertion", clientAssertion)
}

private fun McpOAuthClientCredentials.basicAuthorizationHeader(authMethod: McpOAuthTokenEndpointAuthMethod): String {
    val clientSecret = requireClientSecret(authMethod)
    val userPass = "${formUrlEncode(clientId)}:${formUrlEncode(clientSecret)}"
    return "Basic ${base64Standard(userPass.encodeToByteArray())}"
}

private fun McpOAuthClientCredentials.requireClientSecret(authMethod: McpOAuthTokenEndpointAuthMethod): String =
    clientSecret ?: throw McpOAuthException("${authMethod.wireValue} requires a client secret")

private fun JsonObject.stringOrNull(key: String): String? = this[key]?.jsonPrimitive?.content

private fun JsonObject.booleanOrNull(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

private fun JsonObject.stringListOrNull(key: String): List<String>? =
    this[key]?.jsonArray?.map { it.jsonPrimitive.content }

private fun looksLikeChallengeStart(value: String, startIndex: Int): Boolean {
    var index = startIndex
    while (index < value.length && value[index].isWhitespace()) index++
    if (index >= value.length) return false
    while (index < value.length && !value[index].isWhitespace() && value[index] != ',') {
        if (value[index] == '=') return false
        index++
    }
    while (index < value.length && value[index].isWhitespace()) index++
    return index >= value.length || value[index] != '='
}

private fun parseChallengeParameters(value: String): Map<String, String> {
    val params = mutableMapOf<String, String>()
    var index = 0
    while (index < value.length) {
        while (index < value.length && (value[index].isWhitespace() || value[index] == ',')) index++
        val keyStart = index
        while (index < value.length && value[index] != '=' && value[index] != ',') index++
        if (index >= value.length || value[index] != '=') break
        val key = value.substring(keyStart, index).trim().lowercase()
        index++

        val parsedValue = if (index < value.length && value[index] == '"') {
            index++
            val result = StringBuilder()
            while (index < value.length) {
                val c = value[index++]
                when {
                    c == '\\' && index < value.length -> result.append(value[index++])
                    c == '"' -> break
                    else -> result.append(c)
                }
            }
            result.toString()
        } else {
            val valueStart = index
            while (index < value.length && value[index] != ',') index++
            value.substring(valueStart, index).trim()
        }
        if (key.isNotEmpty()) params[key] = parsedValue
        while (index < value.length && value[index] != ',') index++
        if (index < value.length && value[index] == ',') index++
    }
    return params
}

private fun requireValidPkceVerifier(codeVerifier: String) {
    require(codeVerifier.length in 43..128) {
        "PKCE code verifier must be 43 to 128 characters"
    }
    require(codeVerifier.all { it.isPkceVerifierChar() }) {
        "PKCE code verifier contains characters outside ALPHA / DIGIT / '-' / '.' / '_' / '~'"
    }
}

private fun Char.isPkceVerifierChar(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '-' || this == '.' || this == '_' || this == '~'

private fun base64UrlNoPadding(bytes: ByteArray): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val result = StringBuilder((bytes.size * 4 + 2) / 3)
    var index = 0
    while (index + 2 < bytes.size) {
        val block = ((bytes[index].toInt() and 0xff) shl 16) or
            ((bytes[index + 1].toInt() and 0xff) shl 8) or
            (bytes[index + 2].toInt() and 0xff)
        result.append(alphabet[(block ushr 18) and 0x3f])
        result.append(alphabet[(block ushr 12) and 0x3f])
        result.append(alphabet[(block ushr 6) and 0x3f])
        result.append(alphabet[block and 0x3f])
        index += 3
    }
    val remaining = bytes.size - index
    if (remaining == 1) {
        val block = (bytes[index].toInt() and 0xff) shl 16
        result.append(alphabet[(block ushr 18) and 0x3f])
        result.append(alphabet[(block ushr 12) and 0x3f])
    } else if (remaining == 2) {
        val block = ((bytes[index].toInt() and 0xff) shl 16) or
            ((bytes[index + 1].toInt() and 0xff) shl 8)
        result.append(alphabet[(block ushr 18) and 0x3f])
        result.append(alphabet[(block ushr 12) and 0x3f])
        result.append(alphabet[(block ushr 6) and 0x3f])
    }
    return result.toString()
}

private fun base64Standard(bytes: ByteArray): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val result = StringBuilder(((bytes.size + 2) / 3) * 4)
    var index = 0
    while (index + 2 < bytes.size) {
        val block = ((bytes[index].toInt() and 0xff) shl 16) or
            ((bytes[index + 1].toInt() and 0xff) shl 8) or
            (bytes[index + 2].toInt() and 0xff)
        result.append(alphabet[(block ushr 18) and 0x3f])
        result.append(alphabet[(block ushr 12) and 0x3f])
        result.append(alphabet[(block ushr 6) and 0x3f])
        result.append(alphabet[block and 0x3f])
        index += 3
    }
    val remaining = bytes.size - index
    if (remaining == 1) {
        val block = (bytes[index].toInt() and 0xff) shl 16
        result.append(alphabet[(block ushr 18) and 0x3f])
        result.append(alphabet[(block ushr 12) and 0x3f])
        result.append("==")
    } else if (remaining == 2) {
        val block = ((bytes[index].toInt() and 0xff) shl 16) or
            ((bytes[index + 1].toInt() and 0xff) shl 8)
        result.append(alphabet[(block ushr 18) and 0x3f])
        result.append(alphabet[(block ushr 12) and 0x3f])
        result.append(alphabet[(block ushr 6) and 0x3f])
        result.append('=')
    }
    return result.toString()
}

private fun formUrlEncode(value: String): String {
    val result = StringBuilder(value.length)
    for (byte in value.encodeToByteArray()) {
        val unsigned = byte.toInt() and 0xff
        val char = unsigned.toChar()
        when {
            char.isFormUrlEncodeLiteral() -> result.append(char)

            char == ' ' -> result.append('+')

            else -> {
                result.append('%')
                result.append(HEX[unsigned ushr 4])
                result.append(HEX[unsigned and 0x0f])
            }
        }
    }
    return result.toString()
}

private fun Char.isFormUrlEncodeLiteral(): Boolean = when (this) {
    in 'A'..'Z', in 'a'..'z', in '0'..'9', '-', '.', '_', '*' -> true
    else -> false
}

private fun sha256(input: ByteArray): ByteArray {
    val bitLength = input.size.toLong() * 8
    val paddedLength = (((input.size + 9) + 63) / 64) * 64
    val padded = ByteArray(paddedLength)
    input.copyInto(padded)
    padded[input.size] = 0x80.toByte()
    for (i in 0 until 8) {
        padded[padded.size - 1 - i] = (bitLength ushr (8 * i)).toByte()
    }

    var h0 = 0x6a09e667
    var h1 = 0xbb67ae85.toInt()
    var h2 = 0x3c6ef372
    var h3 = 0xa54ff53a.toInt()
    var h4 = 0x510e527f
    var h5 = 0x9b05688c.toInt()
    var h6 = 0x1f83d9ab
    var h7 = 0x5be0cd19
    val words = IntArray(64)

    for (chunkStart in padded.indices step 64) {
        for (i in 0 until 16) {
            val offset = chunkStart + i * 4
            words[i] = ((padded[offset].toInt() and 0xff) shl 24) or
                ((padded[offset + 1].toInt() and 0xff) shl 16) or
                ((padded[offset + 2].toInt() and 0xff) shl 8) or
                (padded[offset + 3].toInt() and 0xff)
        }
        for (i in 16 until 64) {
            val s0 = words[i - 15].rotateRight(7) xor words[i - 15].rotateRight(18) xor (words[i - 15] ushr 3)
            val s1 = words[i - 2].rotateRight(17) xor words[i - 2].rotateRight(19) xor (words[i - 2] ushr 10)
            words[i] = words[i - 16] + s0 + words[i - 7] + s1
        }

        var a = h0
        var b = h1
        var c = h2
        var d = h3
        var e = h4
        var f = h5
        var g = h6
        var h = h7

        for (i in 0 until 64) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val temp1 = h + s1 + ch + SHA256_K[i] + words[i]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val temp2 = s0 + maj

            h = g
            g = f
            f = e
            e = d + temp1
            d = c
            c = b
            b = a
            a = temp1 + temp2
        }

        h0 += a
        h1 += b
        h2 += c
        h3 += d
        h4 += e
        h5 += f
        h6 += g
        h7 += h
    }

    val digest = ByteArray(32)
    intArrayOf(h0, h1, h2, h3, h4, h5, h6, h7).forEachIndexed { index, value ->
        val offset = index * 4
        digest[offset] = (value ushr 24).toByte()
        digest[offset + 1] = (value ushr 16).toByte()
        digest[offset + 2] = (value ushr 8).toByte()
        digest[offset + 3] = value.toByte()
    }
    return digest
}

private fun Int.rotateRight(bitCount: Int): Int = (this ushr bitCount) or (this shl (32 - bitCount))

private const val HEX = "0123456789ABCDEF"

private val SHA256_K = intArrayOf(
    0x428a2f98,
    0x71374491,
    0xb5c0fbcf.toInt(),
    0xe9b5dba5.toInt(),
    0x3956c25b,
    0x59f111f1,
    0x923f82a4.toInt(),
    0xab1c5ed5.toInt(),
    0xd807aa98.toInt(),
    0x12835b01,
    0x243185be,
    0x550c7dc3,
    0x72be5d74,
    0x80deb1fe.toInt(),
    0x9bdc06a7.toInt(),
    0xc19bf174.toInt(),
    0xe49b69c1.toInt(),
    0xefbe4786.toInt(),
    0x0fc19dc6,
    0x240ca1cc,
    0x2de92c6f,
    0x4a7484aa,
    0x5cb0a9dc,
    0x76f988da,
    0x983e5152.toInt(),
    0xa831c66d.toInt(),
    0xb00327c8.toInt(),
    0xbf597fc7.toInt(),
    0xc6e00bf3.toInt(),
    0xd5a79147.toInt(),
    0x06ca6351,
    0x14292967,
    0x27b70a85,
    0x2e1b2138,
    0x4d2c6dfc,
    0x53380d13,
    0x650a7354,
    0x766a0abb,
    0x81c2c92e.toInt(),
    0x92722c85.toInt(),
    0xa2bfe8a1.toInt(),
    0xa81a664b.toInt(),
    0xc24b8b70.toInt(),
    0xc76c51a3.toInt(),
    0xd192e819.toInt(),
    0xd6990624.toInt(),
    0xf40e3585.toInt(),
    0x106aa070,
    0x19a4c116,
    0x1e376c08,
    0x2748774c,
    0x34b0bcb5,
    0x391c0cb3,
    0x4ed8aa4a,
    0x5b9cca4f,
    0x682e6ff3,
    0x748f82ee,
    0x78a5636f,
    0x84c87814.toInt(),
    0x8cc70208.toInt(),
    0x90befffa.toInt(),
    0xa4506ceb.toInt(),
    0xbef9a3f7.toInt(),
    0xc67178f2.toInt(),
)
