# Streamable HTTP Guide

This guide covers the Kotlin SDK's Streamable HTTP client and server APIs for
the MCP `2025-11-25` transport. The official transport specification is the
source of truth for protocol requirements:
https://modelcontextprotocol.io/specification/2025-11-25/basic/transports

Use Streamable HTTP for new remote MCP clients and servers. Keep the older
HTTP+SSE transport only when you need compatibility with older deployments.

## Server Modes

### Stateful Server

Use `mcpStreamableHttp` when the server should keep an MCP session across
requests. The helper installs MCP JSON content negotiation and Ktor SSE support,
registers `POST`, `GET`, and `DELETE` on one endpoint, and enables DNS
rebinding protection by default.

```kotlin
import io.ktor.server.application.Application
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

fun Application.configureMcp() {
    mcpStreamableHttp(
        path = "/mcp",
        allowedHosts = listOf("mcp.example.com"),
        allowedOrigins = listOf("https://app.example.com"),
    ) {
        Server(
            serverInfo = Implementation("example-server", "1.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true),
                ),
            ),
        )
    }
}
```

In stateful mode the server assigns `MCP-Session-Id` during initialization and
expects the client to send that ID on later `POST`, `GET`, and `DELETE`
requests. The client transport handles this automatically after initialization.
`allowedHosts` entries are hostnames. `allowedOrigins` entries are full origins
with a scheme, such as `https://app.example.com`.

### Stateless Server

Use `mcpStatelessStreamableHttp` when each request can be handled without a
server-side session. It registers only `POST`; `GET` and `DELETE` return
`405 Method Not Allowed`.

```kotlin
import io.ktor.server.application.Application
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

fun Application.configureStatelessMcp() {
    mcpStatelessStreamableHttp(path = "/mcp") {
        Server(
            serverInfo = Implementation("stateless-server", "1.0.0"),
            options = ServerOptions(capabilities = ServerCapabilities()),
        )
    }
}
```

## Client Setup

Install Ktor's SSE plugin and connect with `mcpStreamableHttp` for the common
case:

```kotlin
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp

suspend fun connectClient() {
    val httpClient = HttpClient(CIO) {
        install(SSE)
    }

    val client = httpClient.mcpStreamableHttp("https://mcp.example.com/mcp")
    val tools = client.listTools()

    client.close()
    httpClient.close()
}
```

Use `StreamableHttpClientTransport` directly when you need to tune SSE
reconnection behavior or add headers such as OAuth Bearer tokens:

```kotlin
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ReconnectionOptions
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.auth.mcpBearerAuth
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlin.time.Duration.Companion.seconds

suspend fun connectWithBearer(accessToken: () -> String) {
    val httpClient = HttpClient(CIO) {
        install(SSE)
    }
    val transport = StreamableHttpClientTransport(
        client = httpClient,
        url = "https://mcp.example.com/mcp",
        reconnectionOptions = ReconnectionOptions(
            initialReconnectionDelay = 1.seconds,
            maxReconnectionDelay = 30.seconds,
            reconnectionDelayMultiplier = 1.5,
            maxRetries = 4,
        ),
        requestBuilder = mcpBearerAuth(accessToken),
    )
    val client = Client(Implementation("example-client", "1.0.0"))

    client.connect(transport)
    client.ping()

    client.close()
    httpClient.close()
}
```

## HTTP Requirements

The transport uses a single MCP endpoint for all methods.

- `POST` sends one JSON-RPC request, notification, or response. The client must
  send `Content-Type: application/json` and an `Accept` header that supports
  both `application/json` and `text/event-stream`.
- `GET` opens an optional server-to-client SSE stream. The client must support
  `text/event-stream` in `Accept`.
- `DELETE` terminates a stateful session when the server supports explicit
  termination. The client treats `405 Method Not Allowed` as a valid response
  from servers that do not support this operation.

The Kotlin client stores the session ID returned during initialization and sends
`MCP-Session-Id` on subsequent requests. After protocol negotiation, it also
sends `MCP-Protocol-Version` on subsequent requests.

## JSON and SSE Responses

The server may respond to a `POST` request with either:

- `Content-Type: application/json` for a single JSON-RPC response.
- `Content-Type: text/event-stream` for an SSE stream that can include
  progress, logging, server requests, notifications, and eventually the response
  for the originating request.

The Kotlin client supports both. Inline SSE events from a `POST` response are
bounded by `maxInlineSseEventSize`, which defaults to 16 MiB.

## Resumability

To make streams resumable, provide an `EventStore` to the server transport or
the `mcpStreamableHttp` helper. The server stores outgoing SSE events with IDs.
When a client reconnects with `Last-Event-ID`, the server can replay only the
messages that belong to the interrupted stream.

```kotlin
import io.modelcontextprotocol.kotlin.sdk.server.EventStore
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

fun io.ktor.server.application.Application.configureMcpWithReplay(eventStore: EventStore) {
    mcpStreamableHttp(
        path = "/mcp",
        eventStore = eventStore,
    ) {
        Server(
            serverInfo = Implementation("resumable-server", "1.0.0"),
            options = ServerOptions(capabilities = ServerCapabilities()),
        )
    }
}
```

`EventStore.storeEvent` must return globally unique event IDs for the relevant
session or client. `EventStore.replayEventsAfter` must replay only events that
belong to the same interrupted stream. `EventStore.getStreamIdForEventId` can
be implemented to reject replay attempts that target a different stream.

## Security Checklist

- Bind local development servers to `127.0.0.1` rather than all interfaces.
- Keep `enableDnsRebindingProtection = true` unless equivalent middleware is
  installed. Configure `allowedHosts` and `allowedOrigins` for deployed
  endpoints.
- Require authentication for remote endpoints. See
  [Auth and OAuth Guide](./auth-oauth.md) for Bearer-token helpers.
- Treat `MCP-Session-Id` as session state, not as an authentication credential.
- Set `maxRequestBodySize` on server transports if the default 4 MiB limit is
  too large or too small for your deployment.

## Compatibility

Streamable HTTP replaces the older HTTP+SSE transport from MCP `2024-11-05`.
For new deployments, expose Streamable HTTP at a single endpoint such as `/mcp`.
If you need to support older clients, host the legacy SSE and POST endpoints
alongside the Streamable HTTP endpoint.
