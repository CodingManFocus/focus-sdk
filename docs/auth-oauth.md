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
- Client ID Metadata Document JSON generation when an authorization server
  advertises `client_id_metadata_document_supported`.
- Dynamic client registration requests when an authorization server advertises
  a `registration_endpoint`.
- PKCE S256 code verifier/challenge generation.
- Authorization-code URL construction.
- Reusable authorization-code flow preparation and completion helpers that
  combine discovery, challenge scope handling, PKCE validation, `resource`
  propagation, and token endpoint authentication method selection.
- JVM localhost loopback callback receiver for browser authorization redirects.
- Authorization-code token exchange.
- Refresh-token exchange.
- Token store JSON snapshot/restore helpers for application-managed secure storage.
- Bearer-token request decoration.
- Streamable HTTP transport/client bootstrap helpers backed by the token store.
- One automatic refresh/retry on `401 Unauthorized` for Ktor HTTP clients.
- Client credentials token exchange and a Ktor bearer provider for
  machine-to-machine clients.
- `private_key_jwt` client credentials requests when the application supplies a
  signed JWT assertion.

The SDK does not yet provide browser launching or an OS/service token vault. JVM
clients can receive localhost browser callbacks and create RS256
`private_key_jwt` assertions with the SDK; other platforms or algorithms can
still provide assertions through `McpOAuthClientAssertionProvider`.

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
import io.modelcontextprotocol.kotlin.sdk.client.auth.McpOAuthAuthorizationCodeFlowRequest
import io.modelcontextprotocol.kotlin.sdk.client.auth.McpOAuthClientCredentials
import io.modelcontextprotocol.kotlin.sdk.client.auth.McpOAuthTokenStore
import io.modelcontextprotocol.kotlin.sdk.client.auth.exchangeMcpOAuthAuthorizationCode
import io.modelcontextprotocol.kotlin.sdk.client.auth.mcpOAuthTokenStoreSnapshotFromJsonString
import io.modelcontextprotocol.kotlin.sdk.client.auth.mcpOAuthStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.client.auth.mcpPkceS256
import io.modelcontextprotocol.kotlin.sdk.client.auth.prepareMcpOAuthAuthorizationCodeFlow
import io.modelcontextprotocol.kotlin.sdk.client.auth.startMcpOAuthLoopbackCallbackReceiver
import io.modelcontextprotocol.kotlin.sdk.client.auth.toMcpOAuthTokenStoreSnapshotJsonString
import io.modelcontextprotocol.kotlin.sdk.client.Client
import java.security.SecureRandom

suspend fun connectAfterAuthorization(
    serverUrl: String,
    wwwAuthenticate: String?,
    clientId: String,
    clientSecret: String?,
    openAuthorizationUrl: suspend (authorizationUrl: String) -> Unit,
    saveTokens: (String) -> Unit,
): Client {
    val discoveryClient = HttpClient()
    val tokenClient = HttpClient()

    val randomBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val pkce = mcpPkceS256(randomBytes)
    val state = "replace-with-cryptographically-random-state"
    val callbackReceiver = startMcpOAuthLoopbackCallbackReceiver()

    val tokens = try {
        val preparedFlow = prepareMcpOAuthAuthorizationCodeFlow(
            httpClient = discoveryClient,
            request = McpOAuthAuthorizationCodeFlowRequest(
                serverUrl = serverUrl,
                clientCredentials = McpOAuthClientCredentials(clientId, clientSecret),
                redirectUri = callbackReceiver.redirectUri,
                pkce = pkce,
                state = state,
                wwwAuthenticate = wwwAuthenticate,
            ),
        )

        openAuthorizationUrl(preparedFlow.authorizationUrl)
        val callback = callbackReceiver.awaitCallback(preparedFlow)

        exchangeMcpOAuthAuthorizationCode(
            httpClient = tokenClient,
            preparedFlow = preparedFlow,
            code = callback.code,
        )
    } finally {
        callbackReceiver.close()
    }

    val tokenStore = McpOAuthTokenStore(tokens) { snapshot ->
        saveTokens(snapshot.toMcpOAuthTokenStoreSnapshotJsonString())
    }
    val mcpHttpClient = HttpClient {
        install(SSE)
    }
    return mcpHttpClient.mcpOAuthStreamableHttp(serverUrl, tokenStore)
}

fun restoreTokenStore(loadTokens: () -> String?): McpOAuthTokenStore? {
    val saved = loadTokens() ?: return null
    val snapshot = mcpOAuthTokenStoreSnapshotFromJsonString(saved)
    return McpOAuthTokenStore(snapshot)
}
```

Generate `state` with cryptographically secure random bytes. The JVM loopback
callback receiver rejects OAuth error callbacks, missing codes, and state
mismatches before token exchange. The sample still leaves browser launching to
the application because desktop hosts use different browser integration APIs.
If your authorization server requires an exact pre-registered localhost
redirect URI, start the receiver with the registered `port` and `path`.

Persist token snapshots only in protected storage such as an OS keychain, a
credential manager, or a service secret store. The JSON helper defines the SDK's
portable snapshot format; it does not make plain files safe for bearer or
refresh tokens.

## Client Registration

MCP authorization supports multiple client registration approaches. Prefer
pre-registered credentials when available. If the authorization server
advertises Client ID Metadata Document support, clients should prefer that
approach. Dynamic Client Registration is a fallback when the server advertises
a `registration_endpoint` in authorization server metadata.

For the common no-prior-relationship case, host a Client ID Metadata Document at
an HTTPS URL and use that URL as the OAuth `client_id`. The SDK validates the
basic MCP requirements and builds the JSON document; the application still owns
publishing it at that exact URL with appropriate HTTP caching.

```kotlin
import io.ktor.client.HttpClient
import io.modelcontextprotocol.kotlin.sdk.client.auth.McpOAuthClientIdMetadataDocument
import io.modelcontextprotocol.kotlin.sdk.client.auth.McpOAuthTokenEndpointAuthMethod
import io.modelcontextprotocol.kotlin.sdk.client.auth.buildMcpOAuthClientIdMetadataDocumentJson
import io.modelcontextprotocol.kotlin.sdk.client.auth.discoverMcpOAuthMetadata
import io.modelcontextprotocol.kotlin.sdk.client.auth.supportsMcpOAuthClientIdMetadataDocuments

suspend fun clientIdForServer(
    httpClient: HttpClient,
    serverUrl: String,
): String {
    val discovery = discoverMcpOAuthMetadata(httpClient, serverUrl)
    if (supportsMcpOAuthClientIdMetadataDocuments(discovery.authorizationServerMetadata)) {
        val metadata = McpOAuthClientIdMetadataDocument(
            clientId = "https://app.example.com/oauth/client-metadata.json",
            clientName = "Example MCP Client",
            redirectUris = listOf("http://127.0.0.1:3000/callback"),
            tokenEndpointAuthMethod = McpOAuthTokenEndpointAuthMethod.None,
            clientUri = "https://app.example.com",
            logoUri = "https://app.example.com/logo.png",
        )

        val documentJson = buildMcpOAuthClientIdMetadataDocumentJson(metadata)
        // Host documentJson at metadata.clientId before starting OAuth.
        return metadata.clientId
    }

    error("Authorization server does not advertise Client ID Metadata Documents")
}
```

The `client_id` URL must use HTTPS, include a path component, and the document's
`client_id` field must match that URL exactly.

If the client uses `private_key_jwt`, publish a JWKS URL in the metadata
document so the authorization server can validate client assertions. Keep the
private key in platform secret storage; publish only public keys.

```kotlin
val metadata = McpOAuthClientIdMetadataDocument(
    clientId = "https://app.example.com/oauth/client-metadata.json",
    clientName = "Example MCP Client",
    redirectUris = listOf("http://127.0.0.1:3000/callback"),
    tokenEndpointAuthMethod = McpOAuthTokenEndpointAuthMethod.PrivateKeyJwt,
    jwksUri = "https://app.example.com/oauth/jwks.json",
)

val documentJson = buildMcpOAuthClientIdMetadataDocumentJson(metadata)
```

Rotate keys by publishing both the old and new public keys in the JWKS during
the overlap window, setting `kid` on assertions, and removing the old key only
after all short-lived assertions have expired.

Use Dynamic Client Registration only when Client ID Metadata Documents are not
available and the authorization server advertises a `registration_endpoint`:

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
store and use it to replace expired access tokens. `McpOAuthTokenStore` keeps
runtime state in memory and can emit snapshots for application-managed
persistence in OS keychains, credential managers, encrypted files, or service
secret stores.

```kotlin
import io.modelcontextprotocol.kotlin.sdk.client.auth.McpOAuthTokenResponse
import io.modelcontextprotocol.kotlin.sdk.client.auth.McpOAuthTokenStore
import io.modelcontextprotocol.kotlin.sdk.client.auth.McpOAuthTokenStoreSnapshot

fun saveTokenSnapshotToSecretStorage(snapshot: McpOAuthTokenStoreSnapshot) {
    // Write snapshot to platform or service secret storage.
}

fun tokenStoreFromSecretStorage(
    saved: McpOAuthTokenStoreSnapshot?,
    initialTokens: McpOAuthTokenResponse,
    receivedAtEpochSeconds: Long,
): McpOAuthTokenStore =
    if (saved != null) {
        McpOAuthTokenStore(saved) { snapshot ->
            saveTokenSnapshotToSecretStorage(snapshot)
        }
    } else {
        McpOAuthTokenStore(initialTokens, receivedAtEpochSeconds) { snapshot ->
            saveTokenSnapshotToSecretStorage(snapshot)
        }
    }
```

Persist snapshots only in a secure store. Treat access tokens, refresh tokens,
registration access tokens, and client secrets as credentials. For public
clients, expect refresh-token rotation and persist the updated snapshot after
each refresh. Pass the epoch seconds when a token response was received if you
want snapshots to include `expiresAtEpochSeconds`; restored stores can then use
`shouldRefresh(currentEpochSeconds)` before sending requests.

The helper below installs a Ktor `HttpSend` interceptor that:

- Adds the current bearer token to each request.
- On one `401 Unauthorized`, calls the supplied refresh callback if a refresh
  token exists.
- Updates the in-memory `McpOAuthTokenStore`; if the store was created with an
  update callback, the refreshed snapshot can be persisted by the application.
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

Use `mcpOAuthStreamableHttpTransport` or `mcpOAuthStreamableHttp` after an
authorization-code flow to bootstrap a Streamable HTTP MCP transport from a
token store. The helper reads the access token for each outgoing request, so
refreshes or application-managed token updates are reflected without rebuilding
the transport.

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
challenges, guarding Ktor route handlers, and validating common JWT access-token
claims after cryptographic verification. Token signature verification, JWKS key
selection, issuer trust policy, and token introspection remain application or
reverse-proxy responsibilities.

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
import io.modelcontextprotocol.kotlin.sdk.server.validateMcpOAuthJwtClaims

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
    // First verify the JWT signature and select the right JWKS key here.
    val verifiedClaims: kotlinx.serialization.json.JsonObject =
        verifyJwtAndReturnClaims(token)

    return validateMcpOAuthJwtClaims(
        claims = verifiedClaims,
        resource = "https://mcp.example.com/mcp",
        currentEpochSeconds = currentEpochSeconds(),
        issuer = "https://auth.example.com",
    )
}
```

`validateMcpOAuthJwtClaims` checks the verified JWT's `iss`, `aud`, `exp`,
`nbf`, and `iat` claims with configurable clock skew and returns scopes from
`scope` and `scp` claims. It intentionally does not parse compact JWTs, verify
signatures, fetch JWKS documents, or decide whether an issuer is trusted.

For JWT access tokens, the application or reverse proxy should fetch the
authorization server JWKS, choose a key by `kid` and `alg`, verify the compact
JWT signature, then pass the verified claims to `validateMcpOAuthJwtClaims`:

```kotlin
import kotlinx.serialization.json.JsonObject

suspend fun validateAccessTokenWithJwks(
    token: String,
    issuer: String,
    jwksUri: String,
    resource: String,
    currentEpochSeconds: Long,
): McpOAuthBearerTokenValidationResult {
    val jwks = fetchTrustedJwks(jwksUri)
    val verifiedClaims: JsonObject = jwtVerifier.verifyAndReturnClaims(
        token = token,
        jwks = jwks,
        expectedIssuer = issuer,
        allowedAlgorithms = setOf("RS256"),
    )

    return validateMcpOAuthJwtClaims(
        claims = verifiedClaims,
        resource = resource,
        currentEpochSeconds = currentEpochSeconds,
        issuer = issuer,
    )
}
```

Cache JWKS according to HTTP cache headers, refresh on unknown `kid`, pin the
trusted issuer to the authorization server chosen during discovery, reject
unexpected algorithms, and do not accept tokens whose `aud` does not include
the canonical MCP resource URI.

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
non-JVM targets, encrypted keys, PKCS#1 keys, HSM-backed keys, or algorithms
beyond RS256, provide your own `McpOAuthClientAssertionProvider` backed by an
OAuth/JWT library available on that platform:

```kotlin
import io.modelcontextprotocol.kotlin.sdk.client.auth.McpOAuthClientAssertionProvider

val assertionProvider = McpOAuthClientAssertionProvider {
    platformJwtSigner.signClientAssertion(
        issuer = "my-service",
        subject = "my-service",
        audience = "https://auth.example.com/token",
        keyId = "current-signing-key",
        expiresInSeconds = 300,
    )
}
```

The assertion provider must return a compact signed JWT each time it is called.
Use the authorization server token endpoint as `aud`, keep assertions
short-lived, include a unique `jti`, and align the `kid` with the public key in
the metadata document's `jwks_uri`.

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
