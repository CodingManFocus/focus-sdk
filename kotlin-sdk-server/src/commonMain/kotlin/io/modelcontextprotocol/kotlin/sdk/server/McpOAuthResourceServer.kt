package io.modelcontextprotocol.kotlin.sdk.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * OAuth Protected Resource Metadata advertised by a protected MCP server.
 *
 * This metadata lets MCP clients discover the authorization server that issues
 * access tokens for this MCP server.
 */
public data class McpOAuthProtectedResourceMetadata(
    public val resource: String,
    public val authorizationServers: List<String>,
    public val scopesSupported: List<String>? = null,
    public val extraFields: JsonObject = JsonObject(emptyMap()),
) {
    init {
        require(authorizationServers.isNotEmpty()) {
            "authorizationServers must contain at least one authorization server"
        }
    }
}

/**
 * Result of validating an MCP OAuth bearer token.
 */
public sealed class McpOAuthBearerTokenValidationResult {
    /**
     * The token is valid for this MCP server.
     *
     * @property scopes scopes granted by the token.
     */
    public data class Valid(public val scopes: Set<String> = emptySet()) : McpOAuthBearerTokenValidationResult()

    /**
     * The token is missing, expired, malformed, or not valid for this MCP server.
     */
    public data class Invalid
    constructor(
        public val error: String? = null,
        public val errorDescription: String? = null,
    ) : McpOAuthBearerTokenValidationResult()
}

/**
 * Validates an access token and returns the token scopes when it is valid.
 */
public fun interface McpOAuthBearerTokenValidator {
    public suspend fun validate(call: ApplicationCall, accessToken: String): McpOAuthBearerTokenValidationResult
}

/**
 * Validates common JWT access-token claims for an MCP resource server.
 *
 * This helper does not verify a JWT signature, select a JWKS key, or validate
 * the token issuer's trust relationship. Call it only after cryptographic token
 * verification has succeeded. The helper validates issuer, audience, expiry,
 * and not-before style time claims, then returns scopes from `scope` and `scp`
 * claims for [requireMcpOAuthBearer] to enforce.
 *
 * @param claims verified JWT claims.
 * @param resource canonical MCP resource URI expected in the `aud` claim.
 * @param currentEpochSeconds current Unix time in seconds.
 * @param issuer expected issuer, or `null` to skip issuer matching.
 * @param clockSkewSeconds permitted clock skew in seconds for time claims.
 */
public fun validateMcpOAuthJwtClaims(
    claims: JsonObject,
    resource: String,
    currentEpochSeconds: Long,
    issuer: String? = null,
    clockSkewSeconds: Long = 60,
): McpOAuthBearerTokenValidationResult {
    require(clockSkewSeconds >= 0) {
        "clockSkewSeconds must be non-negative"
    }

    if (issuer != null && claims.stringClaim("iss") != issuer) {
        return McpOAuthBearerTokenValidationResult.Invalid(
            error = "invalid_token",
            errorDescription = "token issuer does not match",
        )
    }

    val audiences = claims.audienceClaim()
    if (audiences == null || resource !in audiences) {
        return McpOAuthBearerTokenValidationResult.Invalid(
            error = "invalid_token",
            errorDescription = "token audience does not include MCP resource",
        )
    }

    val expiresAt = claims.numericDateClaim("exp")
        ?: return McpOAuthBearerTokenValidationResult.Invalid(
            error = "invalid_token",
            errorDescription = "token expiry is missing",
        )
    if (currentEpochSeconds - clockSkewSeconds >= expiresAt) {
        return McpOAuthBearerTokenValidationResult.Invalid(
            error = "invalid_token",
            errorDescription = "token is expired",
        )
    }

    val notBefore = claims.numericDateClaim("nbf")
    if (notBefore != null && currentEpochSeconds + clockSkewSeconds < notBefore) {
        return McpOAuthBearerTokenValidationResult.Invalid(
            error = "invalid_token",
            errorDescription = "token is not yet valid",
        )
    }

    val issuedAt = claims.numericDateClaim("iat")
    if (issuedAt != null && currentEpochSeconds + clockSkewSeconds < issuedAt) {
        return McpOAuthBearerTokenValidationResult.Invalid(
            error = "invalid_token",
            errorDescription = "token issued-at time is in the future",
        )
    }

    return McpOAuthBearerTokenValidationResult.Valid(scopes = claims.oauthScopes())
}

/**
 * Returns the well-known path for OAuth Protected Resource Metadata for an MCP endpoint path.
 */
public fun mcpOAuthProtectedResourceMetadataPath(mcpEndpointPath: String = "/mcp"): String {
    val normalizedPath = "/" + mcpEndpointPath.trim().trimStart('/')
    return "/.well-known/oauth-protected-resource${normalizedPath.takeUnless { it == "/" }.orEmpty()}"
}

/**
 * Converts OAuth Protected Resource Metadata to the JSON object served from the well-known endpoint.
 */
public fun McpOAuthProtectedResourceMetadata.toJsonObject(): JsonObject = buildJsonObject {
    extraFields.forEach { (key, value) -> put(key, value) }
    put("resource", resource)
    putJsonArray("authorization_servers") {
        authorizationServers.forEach { add(JsonPrimitive(it)) }
    }
    scopesSupported?.let { scopes ->
        putJsonArray("scopes_supported") {
            scopes.forEach { add(JsonPrimitive(it)) }
        }
    }
}

/**
 * Registers an OAuth Protected Resource Metadata endpoint for a protected MCP server.
 */
public fun Application.mcpOAuthProtectedResourceMetadata(
    metadata: McpOAuthProtectedResourceMetadata,
    mcpEndpointPath: String = "/mcp",
) {
    installMcpContentNegotiation()
    routing {
        mcpOAuthProtectedResourceMetadata(metadata, mcpEndpointPath)
    }
}

/**
 * Registers an OAuth Protected Resource Metadata endpoint on this route.
 */
public fun Route.mcpOAuthProtectedResourceMetadata(
    metadata: McpOAuthProtectedResourceMetadata,
    mcpEndpointPath: String = "/mcp",
) {
    get(mcpOAuthProtectedResourceMetadataPath(mcpEndpointPath)) {
        call.respond(metadata.toJsonObject())
    }
}

/**
 * Builds a Bearer `WWW-Authenticate` challenge for a protected MCP server.
 */
public fun mcpOAuthBearerChallenge(
    resourceMetadataUrl: String? = null,
    scope: String? = null,
    error: String? = null,
    errorDescription: String? = null,
): String {
    val parameters = listOfNotNull(
        resourceMetadataUrl?.let { "resource_metadata=${it.wwwAuthenticateQuoted()}" },
        scope?.let { "scope=${it.wwwAuthenticateQuoted()}" },
        error?.let { "error=${it.wwwAuthenticateQuoted()}" },
        errorDescription?.let { "error_description=${it.wwwAuthenticateQuoted()}" },
    )
    return listOf("Bearer", parameters.joinToString(", ")).filter { it.isNotEmpty() }.joinToString(" ")
}

/**
 * Responds with `401 Unauthorized` and a Bearer challenge for missing, invalid, or expired access tokens.
 */
public suspend fun ApplicationCall.respondMcpOAuthUnauthorized(
    resourceMetadataUrl: String,
    scope: String? = null,
    error: String? = null,
    errorDescription: String? = null,
) {
    response.header(
        HttpHeaders.WWWAuthenticate,
        mcpOAuthBearerChallenge(
            resourceMetadataUrl = resourceMetadataUrl,
            scope = scope,
            error = error,
            errorDescription = errorDescription,
        ),
    )
    respond(HttpStatusCode.Unauthorized)
}

/**
 * Responds with `403 Forbidden` and an insufficient-scope Bearer challenge.
 */
public suspend fun ApplicationCall.respondMcpOAuthInsufficientScope(
    resourceMetadataUrl: String,
    scope: String,
    errorDescription: String? = null,
) {
    response.header(
        HttpHeaders.WWWAuthenticate,
        mcpOAuthBearerChallenge(
            resourceMetadataUrl = resourceMetadataUrl,
            scope = scope,
            error = "insufficient_scope",
            errorDescription = errorDescription,
        ),
    )
    respond(HttpStatusCode.Forbidden)
}

/**
 * Validates this request's Bearer token and responds with the appropriate MCP OAuth challenge on failure.
 *
 * Returns `true` when the route handler should continue. The supplied [validator]
 * is responsible for token signature, issuer, audience, and expiry validation.
 */
public suspend fun ApplicationCall.requireMcpOAuthBearer(
    resourceMetadataUrl: String,
    requiredScopes: Set<String> = emptySet(),
    validator: McpOAuthBearerTokenValidator,
): Boolean {
    val accessToken = mcpOAuthBearerToken()
    if (accessToken == null) {
        respondMcpOAuthUnauthorized(
            resourceMetadataUrl = resourceMetadataUrl,
            scope = requiredScopes.takeIf { it.isNotEmpty() }?.joinToString(" "),
        )
        return false
    }

    return when (val result = validator.validate(this, accessToken)) {
        is McpOAuthBearerTokenValidationResult.Valid -> {
            val missingScopes = requiredScopes - result.scopes
            if (missingScopes.isEmpty()) {
                true
            } else {
                respondMcpOAuthInsufficientScope(
                    resourceMetadataUrl = resourceMetadataUrl,
                    scope = requiredScopes.joinToString(" "),
                )
                false
            }
        }

        is McpOAuthBearerTokenValidationResult.Invalid -> {
            respondMcpOAuthUnauthorized(
                resourceMetadataUrl = resourceMetadataUrl,
                scope = requiredScopes.takeIf { it.isNotEmpty() }?.joinToString(" "),
                error = result.error,
                errorDescription = result.errorDescription,
            )
            false
        }
    }
}

private fun String.wwwAuthenticateQuoted(): String = buildString {
    append('"')
    this@wwwAuthenticateQuoted.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            else -> append(char)
        }
    }
    append('"')
}

private fun ApplicationCall.mcpOAuthBearerToken(): String? {
    val authorization = request.headers[HttpHeaders.Authorization]?.trim() ?: return null
    val prefix = "Bearer "
    if (!authorization.startsWith(prefix, ignoreCase = true)) return null
    return authorization.drop(prefix.length).trim().takeIf { it.isNotEmpty() }
}

private fun JsonObject.stringClaim(key: String): String? = this[key].stringValueOrNull()

private fun JsonObject.numericDateClaim(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

private fun JsonObject.audienceClaim(): Set<String>? {
    val element = this["aud"] ?: return null
    return when (element) {
        is JsonPrimitive -> element.stringValueOrNull()?.let { setOf(it) } ?: emptySet()
        is JsonArray -> element.mapNotNull { it.stringValueOrNull() }.toSet()
        else -> emptySet()
    }
}

private fun JsonObject.oauthScopes(): Set<String> = scopeStrings("scope") + scopeStrings("scp")

private fun JsonObject.scopeStrings(key: String): Set<String> {
    val element = this[key] ?: return emptySet()
    return when (element) {
        is JsonPrimitive -> element.stringValueOrNull().scopeSet()
        is JsonArray -> element.mapNotNull { it.stringValueOrNull() }.toSet()
        else -> emptySet()
    }
}

private fun JsonElement?.stringValueOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun String?.scopeSet(): Set<String> = this?.trim()
    ?.split(Regex("\\s+"))
    ?.filter { it.isNotEmpty() }
    ?.toSet()
    ?: emptySet()
