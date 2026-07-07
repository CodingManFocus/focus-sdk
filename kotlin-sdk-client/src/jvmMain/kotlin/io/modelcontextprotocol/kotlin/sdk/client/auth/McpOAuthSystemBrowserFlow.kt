package io.modelcontextprotocol.kotlin.sdk.client.auth

import io.ktor.client.HttpClient
import java.security.SecureRandom
import java.time.Instant

/**
 * Inputs for completing an MCP OAuth authorization-code flow with the JVM system browser.
 *
 * The helper starts a loopback callback receiver, discovers OAuth metadata, builds PKCE and state
 * values, opens the system browser, validates the redirect, and exchanges the authorization code
 * for tokens. Applications still own secure persistence of the returned tokens.
 *
 * @property serverUrl Protected MCP server URL.
 * @property clientCredentials OAuth client credentials or public client id.
 * @property wwwAuthenticate Optional `WWW-Authenticate` header from a `401 Unauthorized` response.
 * @property resourceMetadataUrl Explicit Protected Resource Metadata URL. When absent, the
 * `resource_metadata` challenge parameter is used if present, then well-known discovery is used.
 * @property scope Optional requested scope used only when the challenge does not provide an
 * authoritative scope.
 * @property clientAssertionProvider Optional provider for `private_key_jwt` token endpoint auth.
 * @property callbackHost Loopback host used for the temporary redirect receiver.
 * @property callbackPort Loopback port used for the temporary redirect receiver. Use `0` for an
 * ephemeral port selected by the operating system.
 * @property callbackPath Absolute callback path without query or fragment.
 * @property timeoutMillis Maximum time to wait for the browser redirect.
 */
public data class McpOAuthSystemBrowserAuthorizationCodeFlowRequest(
    public val serverUrl: String,
    public val clientCredentials: McpOAuthClientCredentials,
    public val wwwAuthenticate: String? = null,
    public val resourceMetadataUrl: String? = null,
    public val scope: String? = null,
    public val clientAssertionProvider: McpOAuthClientAssertionProvider? = null,
    public val callbackHost: String = "127.0.0.1",
    public val callbackPort: Int = 0,
    public val callbackPath: String = "/callback",
    public val timeoutMillis: Long = 120_000,
) {
    init {
        require(timeoutMillis > 0) {
            "timeoutMillis must be positive"
        }
    }
}

/**
 * Result of completing an MCP OAuth authorization-code flow with the JVM system browser.
 *
 * @property preparedFlow Metadata, PKCE, redirect URI, token endpoint, and resource selected for
 * the authorization-code flow.
 * @property callback Validated redirect callback received from the authorization server.
 * @property tokens OAuth tokens returned by the authorization server.
 * @property receivedAtEpochSeconds Time at which the token response was received.
 */
public data class McpOAuthSystemBrowserAuthorizationCodeFlowResult(
    public val preparedFlow: McpOAuthPreparedAuthorizationCodeFlow,
    public val callback: McpOAuthAuthorizationCallback,
    public val tokens: McpOAuthTokenResponse,
    public val receivedAtEpochSeconds: Long,
) {
    /**
     * Returns a token store initialized with this flow's token response and receive time.
     */
    public fun tokenStore(): McpOAuthTokenStore = McpOAuthTokenStore(tokens, receivedAtEpochSeconds)
}

/**
 * Completes an MCP OAuth authorization-code flow using a JVM loopback redirect receiver and the
 * system browser.
 *
 * The system browser helper only opens HTTPS authorization URLs. If the host cannot open a browser,
 * this function throws [McpOAuthException] so applications can present their own UI fallback.
 */
public suspend fun authorizeMcpOAuthWithSystemBrowser(
    httpClient: HttpClient,
    request: McpOAuthSystemBrowserAuthorizationCodeFlowRequest,
): McpOAuthSystemBrowserAuthorizationCodeFlowResult = authorizeMcpOAuthWithSystemBrowser(
    httpClient = httpClient,
    request = request,
    randomBytes = ::mcpOAuthSystemBrowserRandomBytes,
    currentEpochSeconds = { Instant.now().epochSecond },
    openAuthorizationUrl = ::openMcpOAuthAuthorizationUrlInBrowser,
)

internal suspend fun authorizeMcpOAuthWithSystemBrowser(
    httpClient: HttpClient,
    request: McpOAuthSystemBrowserAuthorizationCodeFlowRequest,
    randomBytes: (Int) -> ByteArray,
    currentEpochSeconds: () -> Long,
    openAuthorizationUrl: (String) -> Boolean,
): McpOAuthSystemBrowserAuthorizationCodeFlowResult {
    val receiver = startMcpOAuthLoopbackCallbackReceiver(
        host = request.callbackHost,
        port = request.callbackPort,
        path = request.callbackPath,
    )

    try {
        val pkce = mcpPkceS256(randomBytes(32))
        val state = mcpPkceCodeVerifier(randomBytes(32))
        val preparedFlow = prepareMcpOAuthAuthorizationCodeFlow(
            httpClient = httpClient,
            request = McpOAuthAuthorizationCodeFlowRequest(
                serverUrl = request.serverUrl,
                clientCredentials = request.clientCredentials,
                redirectUri = receiver.redirectUri,
                pkce = pkce,
                state = state,
                wwwAuthenticate = request.wwwAuthenticate,
                resourceMetadataUrl = request.resourceMetadataUrl,
                scope = request.scope,
                clientAssertionProvider = request.clientAssertionProvider,
            ),
        )

        if (!openAuthorizationUrl(preparedFlow.authorizationUrl)) {
            throw McpOAuthException("System browser is unavailable for OAuth authorization")
        }

        val callback = receiver.awaitCallback(
            preparedFlow = preparedFlow,
            timeoutMillis = request.timeoutMillis,
        )
        val tokens = exchangeMcpOAuthAuthorizationCode(
            httpClient = httpClient,
            preparedFlow = preparedFlow,
            code = callback.code,
        )
        return McpOAuthSystemBrowserAuthorizationCodeFlowResult(
            preparedFlow = preparedFlow,
            callback = callback,
            tokens = tokens,
            receivedAtEpochSeconds = currentEpochSeconds(),
        )
    } finally {
        receiver.close()
    }
}

private val mcpOAuthSystemBrowserSecureRandom: SecureRandom = SecureRandom()

private fun mcpOAuthSystemBrowserRandomBytes(size: Int): ByteArray =
    ByteArray(size).also(mcpOAuthSystemBrowserSecureRandom::nextBytes)
