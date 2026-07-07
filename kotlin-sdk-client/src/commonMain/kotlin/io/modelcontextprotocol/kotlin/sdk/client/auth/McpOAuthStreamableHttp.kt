package io.modelcontextprotocol.kotlin.sdk.client.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.modelcontextprotocol.kotlin.sdk.LIB_VERSION
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ReconnectionOptions
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.IMPLEMENTATION_NAME
import io.modelcontextprotocol.kotlin.sdk.types.Implementation

/**
 * Returns a Streamable HTTP transport that sends the current OAuth bearer token on every MCP request.
 *
 * The token is read from [tokenStore] for each outgoing request so refreshes or application-managed
 * updates are reflected without rebuilding the transport. Install [installMcpOAuthBearerAuth] on
 * [HttpClient] first when the same client should refresh once and retry after `401 Unauthorized`.
 *
 * The optional [requestBuilder] is applied before the bearer token, so the OAuth `Authorization`
 * header remains authoritative for the protected MCP resource.
 */
public fun HttpClient.mcpOAuthStreamableHttpTransport(
    url: String,
    tokenStore: McpOAuthTokenStore,
    reconnectionOptions: ReconnectionOptions = ReconnectionOptions(),
    requestBuilder: HttpRequestBuilder.() -> Unit = {},
): StreamableHttpClientTransport = StreamableHttpClientTransport(
    client = this,
    url = url,
    reconnectionOptions = reconnectionOptions,
    requestBuilder = {
        requestBuilder()
        mcpBearerAuth { tokenStore.accessToken }(this)
    },
)

/**
 * Creates and connects an MCP client over Streamable HTTP using OAuth bearer authentication.
 *
 * This helper performs the transport bootstrap after the application has completed an MCP OAuth
 * flow and stored the resulting tokens in [tokenStore]. Token persistence remains application-owned;
 * create [McpOAuthTokenStore] with an update callback to persist refreshed snapshots.
 */
public suspend fun HttpClient.mcpOAuthStreamableHttp(
    url: String,
    tokenStore: McpOAuthTokenStore,
    reconnectionOptions: ReconnectionOptions = ReconnectionOptions(),
    requestBuilder: HttpRequestBuilder.() -> Unit = {},
): Client {
    val transport = mcpOAuthStreamableHttpTransport(
        url = url,
        tokenStore = tokenStore,
        reconnectionOptions = reconnectionOptions,
        requestBuilder = requestBuilder,
    )
    val client = Client(Implementation(name = IMPLEMENTATION_NAME, version = LIB_VERSION))
    client.connect(transport)
    return client
}
