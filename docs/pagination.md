# Pagination Guide

This guide covers the Kotlin SDK APIs for MCP cursor-based pagination.

Official specification:
https://modelcontextprotocol.io/specification/2025-11-25/server/utilities/pagination

Pagination applies to list operations that can return large result sets. MCP
uses opaque cursor tokens instead of numbered pages. Servers decide page size;
clients request the next page by echoing the previous response's `nextCursor`.

## Supported Operations

The current MCP specification defines pagination for these list methods:

- `resources/list`
- `resources/templates/list`
- `prompts/list`
- `tools/list`

The Kotlin SDK represents these with `PaginatedRequestParams` on requests and
`nextCursor` on results:

- `ListResourcesRequest` / `ListResourcesResult`
- `ListResourceTemplatesRequest` / `ListResourceTemplatesResult`
- `ListPromptsRequest` / `ListPromptsResult`
- `ListToolsRequest` / `ListToolsResult`

## Client Loop

Clients MUST treat cursors as opaque tokens. Do not parse, modify, or persist a
cursor across sessions. Clients also MUST NOT assume a fixed page size. To fetch
all resources, keep sending the cursor returned by the previous page until
`nextCursor` is absent.

```kotlin
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.ListResourcesRequest
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Resource

suspend fun listAllResources(client: Client): List<Resource> {
    val resources = mutableListOf<Resource>()
    var cursor: String? = null

    do {
        val page = client.listResources(
            ListResourcesRequest(
                params = cursor?.let { PaginatedRequestParams(cursor = it) },
            ),
        )
        resources += page.resources
        cursor = page.nextCursor
    } while (cursor != null)

    return resources
}
```

Use the same loop shape for prompts, tools, and resource templates by swapping
the request/result type and item field.

## Client Calls

For one page, pass no params:

```kotlin
val firstPage = client.listTools()
```

For a later page, pass the cursor exactly as received:

```kotlin
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams

val cursor = firstPage.nextCursor
if (cursor != null) {
    val nextPage = client.listTools(
        ListToolsRequest(
            params = PaginatedRequestParams(cursor = cursor),
        ),
    )
}
```

Only make the follow-up call when `nextCursor` is not null.

## Server Responses

Servers include `nextCursor` only when more results remain. The cursor format is
server-defined and opaque to clients. A simple in-memory resource list can use a
server-owned offset token:

```kotlin
import io.modelcontextprotocol.kotlin.sdk.types.ListResourcesRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListResourcesResult
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import io.modelcontextprotocol.kotlin.sdk.types.Resource

val resources = listOf(
    Resource(uri = "note://1", name = "Note 1"),
    Resource(uri = "note://2", name = "Note 2"),
    Resource(uri = "note://3", name = "Note 3"),
)
val pageSize = 2

session.setRequestHandler<ListResourcesRequest>(Method.Defined.ResourcesList) { request, _ ->
    val start = request.params?.cursor?.toIntOrNull()
        ?: if (request.params?.cursor == null) 0 else throw McpException(
            code = RPCError.ErrorCode.INVALID_PARAMS,
            message = "Invalid pagination cursor",
        )
    val page = resources.drop(start).take(pageSize)
    val next = if (start + page.size < resources.size) {
        (start + page.size).toString()
    } else {
        null
    }

    ListResourcesResult(
        resources = page,
        nextCursor = next,
    )
}
```

The example uses an integer because the server minted the token and owns its
meaning. Clients should still treat that token as an opaque string.

## Result Types

Each paginated result has a different item property and the same `nextCursor`
property:

```kotlin
import io.modelcontextprotocol.kotlin.sdk.types.ListResourceTemplatesRequest

val resources = client.listResources().resources
val templates = client.listResourceTemplates(ListResourceTemplatesRequest()).resourceTemplates
val prompts = client.listPrompts().prompts
val tools = client.listTools().tools
```

When `nextCursor` is null, pagination is complete. Do not expect a fixed page
size; the server may return any number of items, including an empty page, before
ending pagination.

## Invalid Cursors

Servers should handle invalid cursors gracefully. The MCP specification
recommends JSON-RPC `-32602` (`Invalid params`) for invalid cursors. In the
Kotlin SDK, throw `McpException(code = RPCError.ErrorCode.INVALID_PARAMS, ...)`
from the request handler when a cursor cannot be decoded or no longer refers to
a valid page.

## Implementation Checklist

- Treat `nextCursor` as absent, not empty, when no more results remain.
- Do not expose database ids, filesystem paths, or user data directly as cursor
  values.
- Sign, encrypt, or store cursor state server-side when cursors contain
  sensitive or tamper-prone state.
- Keep cursors stable for the lifetime of the session or document shorter
  validity if the backing collection changes quickly.
- Reject stale, malformed, or unauthorized cursors with invalid params.
- Avoid requiring clients to send page size; the server owns page size.
- Continue applying capability checks for the underlying list method.
- Keep cursor handling independent per session when result sets are
  user-specific.
