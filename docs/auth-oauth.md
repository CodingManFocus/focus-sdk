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
- Dynamic client registration requests when an authorization server advertises
  a `registration_endpoint`.
- PKCE S256 code verifier/challenge generation.
- Authorization-code URL construction.
- Authorization-code token exchange.
- Refresh-token exchange.
- Bearer-token request decoration.
- One automatic refresh/retry on `401 Unauthorized` for Ktor HTTP clients.
- Client credentials token exchange and a Ktor bearer provider for
  machine-to-machine clients.
- `private_key_jwt` client credentials requests when the application supplies a
  signed JWT assertion.

The SDK does not yet provide a complete browser callback server or persistent
token vault. JVM clients can create RS256 `private_key_jwt` assertions with the
SDK; other platforms or algorithms can still provide assertions through
`McpOAuthClientAssertionProvider`.

On the server side, the SDK provides Protected Resource Metadata, Bearer
challenge response helpers, and a request guard for Ktor. Applications still
need to validate token signatures, issuers, audiences, and expiry in the
supplied validator or in a reverse proxy.

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

## Client Registration

MCP authorization supports multiple client registration approaches. Prefer
pre-registered credentials when available. If the authorization server
advertises Client ID Metadata Document support, clients should prefer that
approach. Dynamic Client Registration is a fallback when the server advertises
a `registration_endpoint` in authorization server metadata.

```kotlin
import io.ktor.client.HttpClient
import io.modelcontextprotocol.kotlin.sdk.client.auth.McpOAuthDynamicClientRegistrationRequest
import io.modelcontextprotocol.kotlin.sdk.client.auth.McpOAuthTokenEndpointAuthMethod
import io.modelcontextprotocol.kotlin.sdk.client.auth.discoverMcpOAuthMetadata
import io.modelcontextprotocol.kotlin.sdk.client.auth.registerMcpOAuthClient

suspend fun registerClientIfNeeded(
    httpClient: HttpClient,
    serverUrl: String,
): String {
    val discovery = discoverMcpOAuthMetadata(httpClient, serverUrl)
    val registrationEndpoint = discovery.authorizationServerMetadata.registrationEndpoint
        ?: error("Authorization server does not advertise dynamic registration")

    val registration = registerMcpOAuthClient(
        httpClient = httpClient,
        registrationEndpoint = registrationEndpoint,
        request = McpOAuthDynamicClientRegistrationRequest(
            clientName = "Example MCP Client",
            redirectUris = listOf("http://127.0.0.1:3000/callback"),
            tokenEndpointAuthMethod = McpOAuthTokenEndpointAuthMethod.None,
            clientUri = "https://app.example.com",
            scope = "tools:call",
        ),
    )

    return registration.clientId
}
```

Store returned `client_secret` values in OS or service secret storage. Dynamic
registration is optional in MCP and should not replace Client ID Metadata
Documents when an authorization server supports them.

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

The Kotlin SDK exposes helpers for the MCP-specific resource-server pieces:
serving OAuth Protected Resource Metadata, returning spec-shaped Bearer
challenges, and guarding Ktor route handlers. Token signature, issuer,
audience, expiry, and claim validation remain application or reverse-proxy
responsibilities.

Register the Protected Resource Metadata endpoint alongside the MCP route:

```kotlin
import io.modelcontextprotocol.kotlin.sdk.server.McpOAuthProtectedResourceMetadata
import io.modelcontextprotocol.kotlin.sdk.server.mcpOAuthProtectedResourceMetadata

mcpOAuthProtectedResourceMetadata(
    metadata = McpOAuthProtectedResourceMetadata(
        resource = "https://mcp.example.com/mcp",
        authorizationServers = listOf("https://auth.example.com"),
        scopesSupported = listOf("tools:call", "resources:read"),
    ),
    mcpEndpointPath = "/mcp",
)
```

In Ktor route handlers, use `requireMcpOAuthBearer` before processing the MCP
request. The validator receives the raw access token and returns the granted
scopes after it has validated the token for this MCP resource:

```kotlin
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.modelcontextprotocol.kotlin.sdk.server.McpOAuthBearerTokenValidationResult
import io.modelcontextprotocol.kotlin.sdk.server.McpOAuthBearerTokenValidator
import io.modelcontextprotocol.kotlin.sdk.server.requireMcpOAuthBearer

val resourceMetadataUrl =
    "https://mcp.example.com/.well-known/oauth-protected-resource/mcp"

get("/mcp") {
    if (!call.requireMcpOAuthBearer(
            resourceMetadataUrl = resourceMetadataUrl,
            requiredScopes = setOf("tools:call"),
            validator = McpOAuthBearerTokenValidator { call, token ->
                validateAccessTokenForMcpResource(call, token)
            },
        )
    ) {
        return@get
    }

    call.respondText("protected MCP request")
}

suspend fun validateAccessTokenForMcpResource(
    call: io.ktor.server.application.ApplicationCall,
    token: String,
): McpOAuthBearerTokenValidationResult {
    // Verify signature, issuer, audience, expiry, and claims here.
    return McpOAuthBearerTokenValidationResult.Valid(scopes = setOf("tools:call"))
}
```

For lower-level Ktor middleware or custom guards, use the response helpers for
authorization failures:

```kotlin
import io.modelcontextprotocol.kotlin.sdk.server.respondMcpOAuthInsufficientScope
import io.modelcontextprotocol.kotlin.sdk.server.respondMcpOAuthUnauthorized

val resourceMetadataUrl =
    "https://mcp.example.com/.well-known/oauth-protected-resource/mcp"

call.respondMcpOAuthUnauthorized(
    resourceMetadataUrl = resourceMetadataUrl,
    scope = "tools:call",
    error = "invalid_token",
)

call.respondMcpOAuthInsufficientScope(
    resourceMetadataUrl = resourceMetadataUrl,
    scope = "tools:call",
)
```

A protected MCP server should add a Ktor guard or reverse-proxy middleware
before the MCP route that:

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

Use client credentials only for machine-to-machine MCP clients without a human
resource owner. User-delegated access should use the authorization-code flow.

Declare the OAuth client credentials extension in client capabilities:

```kotlin
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.auth.withMcpOAuthClientCredentialsExtension
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities

val options = ClientOptions(
    capabilities = ClientCapabilities()
        .withMcpOAuthClientCredentialsExtension(),
)
```

For client-secret deployments, use `McpOAuthClientCredentialsProvider` and
install it on the Ktor client used by Streamable HTTP or SSE:

```kotlin
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.auth.McpOAuthClientCredentials
import io.modelcontextprotocol.kotlin.sdk.client.auth.McpOAuthClientCredentialsProvider
import io.modelcontextprotocol.kotlin.sdk.client.auth.McpOAuthClientCredentialsTokenRequest
import io.modelcontextprotocol.kotlin.sdk.client.auth.McpOAuthTokenEndpointAuthMethod
import io.modelcontextprotocol.kotlin.sdk.client.auth.installMcpOAuthClientCredentials

val tokenClient = HttpClient()
val provider = McpOAuthClientCredentialsProvider(
    httpClient = tokenClient,
    request = McpOAuthClientCredentialsTokenRequest(
        tokenEndpoint = "https://auth.example.com/token",
        resource = "https://mcp.example.com/mcp",
        scope = "tools:call",
        clientCredentials = McpOAuthClientCredentials(
            clientId = "my-service",
            clientSecret = System.getenv("MCP_CLIENT_SECRET"),
        ),
        tokenEndpointAuthMethod = McpOAuthTokenEndpointAuthMethod.ClientSecretBasic,
    ),
)

val mcpHttpClient = HttpClient {
    install(SSE)
}
mcpHttpClient.installMcpOAuthClientCredentials(provider)

val transport = StreamableHttpClientTransport(
    client = mcpHttpClient,
    url = "https://mcp.example.com/mcp",
)
```

For JVM `private_key_jwt` deployments using RS256 and an unencrypted PKCS#8
private key, use `McpOAuthPrivateKeyJwtAssertionProvider`:

```kotlin
import io.ktor.client.HttpClient
import io.modelcontextprotocol.kotlin.sdk.client.auth.McpOAuthClientCredentials
import io.modelcontextprotocol.kotlin.sdk.client.auth.McpOAuthClientCredentialsProvider
import io.modelcontextprotocol.kotlin.sdk.client.auth.McpOAuthClientCredentialsTokenRequest
import io.modelcontextprotocol.kotlin.sdk.client.auth.McpOAuthPrivateKeyJwtAssertionProvider
import io.modelcontextprotocol.kotlin.sdk.client.auth.McpOAuthTokenEndpointAuthMethod
import io.modelcontextprotocol.kotlin.sdk.client.auth.mcpOAuthPkcs8PrivateKeyFromPem

val privateKey = mcpOAuthPkcs8PrivateKeyFromPem(
    System.getenv("MCP_CLIENT_PRIVATE_KEY_PEM"),
)
val jwtAssertionProvider = McpOAuthPrivateKeyJwtAssertionProvider(
    clientId = "my-service",
    tokenEndpoint = "https://auth.example.com/token",
    privateKey = privateKey,
    keyId = "current-signing-key",
)

val jwtRequest = McpOAuthClientCredentialsTokenRequest(
    tokenEndpoint = "https://auth.example.com/token",
    resource = "https://mcp.example.com/mcp",
    scope = "tools:call",
    clientCredentials = McpOAuthClientCredentials(clientId = "my-service"),
    tokenEndpointAuthMethod = McpOAuthTokenEndpointAuthMethod.PrivateKeyJwt,
)

val tokenClient = HttpClient()
val jwtProvider = McpOAuthClientCredentialsProvider(
    httpClient = tokenClient,
    request = jwtRequest,
    clientAssertionProvider = jwtAssertionProvider,
)
```

The SDK sends the OAuth JWT bearer `client_assertion_type` and sends the
provider result as `client_assertion`. The JVM provider signs a short-lived
assertion with `iss`, `sub`, `aud`, `iat`, `exp`, and `jti` claims. For
non-JVM targets, encrypted keys, PKCS#1 keys, or algorithms beyond RS256,
provide your own `McpOAuthClientAssertionProvider` backed by an OAuth/JWT
library.

The provider obtains a token before the first protected MCP request. If the
server returns `401 Unauthorized`, it obtains a fresh client credentials token
and retries the request once.

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
