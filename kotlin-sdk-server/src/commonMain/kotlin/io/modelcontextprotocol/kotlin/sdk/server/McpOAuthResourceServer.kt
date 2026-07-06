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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
