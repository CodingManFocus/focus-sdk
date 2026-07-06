# Auth and OAuth Guide

This guide documents the Kotlin SDK surface for MCP authorization over
HTTP-based transports. It is scoped to the current SDK helpers in
`io.modelcontextprotocol.kotlin.sdk.client.auth`.

Official references:

- MCP authorization specification:
  https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization
- Authorization tutorial:
  https://modelcontextprotocol.io/docs/tutorials/security/authorization
- Security best practices:
  https://modelcontextprotocol.io/docs/tutorials/security/security_best_practices
- OAuth Client Credentials extension:
  https://modelcontextprotocol.io/extensions/auth/oauth-client-credentials

## When Authorization Applies

MCP authorization is optional. When a remote MCP server protects an HTTP-based
transport, clients and servers should follow the MCP authorization
specification. Stdio servers should generally obtain credentials from their
local environment instead of running the HTTP OAuth flow.

The Kotlin SDK currently provides client-side OAuth helpers for:

- `WWW-Authenticate` parsing.
- OAuth Protected Resource Metadata discovery.
- OAuth Authorization Server Metadata and OIDC discovery.
- PKCE S256 code verifier/challenge generation.
- Authorization-code URL construction.
- Authorization-code token exchange.
- Refresh-token exchange.
- Bearer-token request decoration.
- One automatic refresh/retry on `401 Unauthorized` for Ktor HTTP clients.

The SDK does not yet provide a complete browser callback server, persistent
token vault, dynamic client registration client, JWT client assertion builder,
or server-side bearer-token validation middleware.

## Client Authorization Code Flow

Use this flow when an MCP client connects to a protected Streamable HTTP server
on behalf of a user.

1. Attempt the MCP request or initialize/connect.
2. If the server responds with `401 Unauthorized`, read the
   `WWW-Authenticate` header.
3. Discover protected resource metadata. Prefer the `resource_metadata`
   parameter from `WWW-Authenticate`; otherwise use the well-known fallback
   locations.
4. Discover authorization server metadata from the selected
   `authorization_servers` entry.
5. Require PKCE S256 support.
6. Build an authorization URL that includes `resource`, `code_challenge`,
   `code_challenge_method=S256`, and the selected `scope`.
7. Open the authorization URL in a browser, then capture and validate the
   redirect `code` and `state`.
8. Exchange the code for tokens. Include the same `resource` and PKCE verifier.
9. Connect the Streamable HTTP transport with `Authorization: Bearer` on every
   request.

```kotlin
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.auth.McpOAuthAuthorizationCodeTokenRequest
import io.modelcontextprotocol.kotlin.sdk.client.auth.McpOAuthAuthorizationRequest
import io.modelcontextprotocol.kotlin.sdk.client.auth.McpOAuthClientCredentials
import io.modelcontextprotocol.kotlin.sdk.client.auth.McpOAuthTokenStore
import io.modelcontextprotocol.kotlin.sdk.client.auth.buildMcpOAuthAuthorizationUrl
import io.modelcontextprotocol.kotlin.sdk.client.auth.discoverMcpOAuthMetadata
import io.modelcontextprotocol.kotlin.sdk.client.auth.exchangeMcpOAuthAuthorizationCode
import io.modelcontextprotocol.kotlin.sdk.client.auth.mcpBearerAuth
import io.modelcontextprotocol.kotlin.sdk.client.auth.mcpPkceS256
import io.modelcontextprotocol.kotlin.sdk.client.auth.requireMcpPkceS256Support
import io.modelcontextprotocol.kotlin.sdk.client.auth.selectMcpOAuthScope
import io.modelcontextprotocol.kotlin.sdk.client.auth.selectMcpOAuthTokenEndpointAuthMethod
import io.modelcontextprotocol.kotlin.sdk.client.auth.wwwAuthenticateParameter
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.client.Client
import java.security.SecureRandom

suspend fun connectAfterAuthorization(
    serverUrl: String,
    wwwAuthenticate: String?,
    clientId: String,
    clientSecret: String?,
    redirectUri: String,
    receiveAuthorizationCode: suspend (authorizationUrl: String, state: String) -> String,
): Client {
    val discoveryClient = HttpClient()
    val tokenClient = HttpClient()

    val discovery = discoverMcpOAuthMetadata(
        httpClient = discoveryClient,
        serverUrl = serverUrl,
        resourceMetadataUrl = wwwAuthenticateParameter(wwwAuthenticate, "resource_metadata"),
    )
    val resource = discovery.resourceMetadata.resource ?: serverUrl
    val authorizationServer = discovery.authorizationServerMetadata

    requireMcpPkceS256Support(authorizationServer)

    val randomBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val pkce = mcpPkceS256(randomBytes)
    val state = "replace-with-cryptographically-random-state"
    val scope = selectMcpOAuthScope(
        wwwAuthenticateScope = wwwAuthenticateParameter(wwwAuthenticate, "scope"),
        scopesSupported = discovery.resourceMetadata.scopesSupported,
    )

    val authorizationEndpoint = authorizationServer.authorizationEndpoint
        ?: error("Authorization server metadata did not include authorization_endpoint")
    val tokenEndpoint = authorizationServer.tokenEndpoint
        ?: error("Authorization server metadata did not include token_endpoint")

    val authorizationUrl = buildMcpOAuthAuthorizationUrl(
        McpOAuthAuthorizationRequest(
            authorizationEndpoint = authorizationEndpoint,
            clientId = clientId,
            redirectUri = redirectUri,
            codeChallenge = pkce.codeChallenge,
            resource = resource,
            scope = scope,
            state = state,
        ),
    )

    val authorizationCode = receiveAuthorizationCode(authorizationUrl, state)
    val clientCredentials = McpOAuthClientCredentials(clientId, clientSecret)
    val tokenEndpointAuthMethod = selectMcpOAuthTokenEndpointAuthMethod(
        authorizationServer,
        clientSecret,
    )

    val tokens = exchangeMcpOAuthAuthorizationCode(
        httpClient = tokenClient,
        request = McpOAuthAuthorizationCodeTokenRequest(
            tokenEndpoint = tokenEndpoint,
            code = authorizationCode,
            redirectUri = redirectUri,
            codeVerifier = pkce.codeVerifier,
            resource = resource,
            clientCredentials = clientCredentials,
            tokenEndpointAuthMethod = tokenEndpointAuthMethod,
        ),
    )

    val tokenStore = McpOAuthTokenStore(tokens)
    val mcpHttpClient = HttpClient {
        install(SSE)
    }
    val transport = StreamableHttpClientTransport(
        client = mcpHttpClient,
        url = serverUrl,
        requestBuilder = mcpBearerAuth { tokenStore.accessToken },
    )
    val client = Client(Implementation("oauth-client", "1.0.0"))
    client.connect(transport)
    return client
}
```

Generate `state` with cryptographically secure random bytes and verify the
returned redirect state before exchanging the code. The sample leaves browser
launching and callback handling to the application because desktop, CLI,
server-side, and mobile hosts need different redirect handling.

## Refresh Tokens

If the authorization server issues a refresh token, keep it in a secure token
store and use it to replace expired access tokens. The helper below installs a
Ktor `HttpSend` interceptor that:

- Adds the current bearer token to each request.
- On one `401 Unauthorized`, calls the supplied refresh callback if a refresh
  token exists.
- Updates the in-memory `McpOAuthTokenStore`.
- Retries the original request once with the new access token.

```kotlin
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.auth.McpOAuthClientCredentials
import io.modelcontextprotocol.kotlin.sdk.client.auth.McpOAuthRefreshTokenRequest
import io.modelcontextprotocol.kotlin.sdk.client.auth.McpOAuthTokenEndpointAuthMethod
import io.modelcontextprotocol.kotlin.sdk.client.auth.McpOAuthTokenStore
import io.modelcontextprotocol.kotlin.sdk.client.auth.installMcpOAuthBearerAuth
import io.modelcontextprotocol.kotlin.sdk.client.auth.refreshMcpOAuthAccessToken

fun authenticatedHttpClient(
    tokenStore: McpOAuthTokenStore,
    tokenEndpoint: String,
    resource: String,
    clientCredentials: McpOAuthClientCredentials,
    tokenEndpointAuthMethod: McpOAuthTokenEndpointAuthMethod,
): HttpClient {
    val tokenClient = HttpClient()
    val mcpHttpClient = HttpClient {
        install(SSE)
    }

    mcpHttpClient.installMcpOAuthBearerAuth(tokenStore) { refreshToken ->
        refreshMcpOAuthAccessToken(
            httpClient = tokenClient,
            request = McpOAuthRefreshTokenRequest(
                tokenEndpoint = tokenEndpoint,
                refreshToken = refreshToken,
                resource = resource,
                clientCredentials = clientCredentials,
                tokenEndpointAuthMethod = tokenEndpointAuthMethod,
            ),
        )
    }

    return mcpHttpClient
}
```

Use a separate `tokenClient` for token endpoint calls so the resource-server
bearer interceptor does not send MCP access tokens to the authorization server.

## Scope Challenges and Step-Up

MCP servers can ask clients to request more scopes. For an initial `401`,
`selectMcpOAuthScope` treats the `scope` value from `WWW-Authenticate` as
authoritative and otherwise falls back to `scopes_supported` from protected
resource metadata.

During runtime, a server can return `403 Forbidden` with
`error="insufficient_scope"` and a new `scope`. Use `mcpOAuthStepUpScope` to
extract the requested step-up scope and repeat authorization with a retry limit.

```kotlin
import io.modelcontextprotocol.kotlin.sdk.client.auth.mcpOAuthStepUpScope

val stepUpScope = mcpOAuthStepUpScope(wwwAuthenticateHeader)
if (stepUpScope != null) {
    // Start a new authorization-code flow using stepUpScope.
    // Retry the original operation only a bounded number of times.
}
```

## Server Responsibilities

The Kotlin SDK currently exposes Streamable HTTP and SSE Ktor routes, but it
does not yet ship a complete resource-server auth middleware. A protected MCP
server should add Ktor or reverse-proxy middleware before the MCP route that:

- Serves OAuth Protected Resource Metadata.
- Returns `401 Unauthorized` with `WWW-Authenticate: Bearer` and
  `resource_metadata` when authorization is missing or invalid.
- Validates every HTTP request, including requests that share an MCP session.
- Rejects expired, malformed, or wrong-audience tokens with `401`.
- Rejects insufficient scopes with `403` and a scope challenge.
- Validates that tokens were issued for this MCP server and does not pass
  unrelated upstream tokens through to downstream APIs.
- Keeps session IDs separate from authentication. An MCP session ID is not an
  auth credential.

For local stdio servers, prefer environment-provided credentials or local
credential providers instead of the HTTP OAuth flow.

## Client Credentials and Extensions

The base SDK helper set focuses on authorization-code and refresh-token flows.
The conformance harness also exercises client credentials scenarios, but the
SDK does not yet expose a high-level client credentials provider comparable to
the Tier 1 TypeScript/Python extension providers.

Until that provider exists, applications can:

- Obtain a client credentials access token with their chosen OAuth library.
- Attach it to Streamable HTTP or SSE requests with `mcpBearerAuth`.
- Refresh or replace the token before expiry.
- Declare extension support in MCP capabilities only when the application
  implements the extension contract.

Track this as a remaining Tier 1 parity task.

## Security Checklist

- Use HTTPS for authorization server endpoints in production.
- Only allow loopback `http://` redirect URIs for local development flows.
- Store refresh tokens and client secrets in OS or service secret storage, not
  source files.
- Validate OAuth `state` values exactly and treat them as single use.
- Keep PKCE verifiers private until token exchange.
- Apply SSRF protections when fetching metadata URLs supplied by a server or
  authorization server.
- Never put access tokens in query strings.
- Send bearer tokens on every HTTP request to the protected MCP server.
- Validate token audience on servers and reject token passthrough.
- Use bounded retry counts for `401` refresh and `403` step-up flows.
