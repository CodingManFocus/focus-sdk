# Resources Guide

This guide covers the Kotlin SDK APIs for MCP `resources/list`,
`resources/read`, `resources/templates/list`, `resources/subscribe`,
`resources/unsubscribe`, `notifications/resources/list_changed`, and
`notifications/resources/updated`.

Official specification:
https://modelcontextprotocol.io/specification/2025-11-25/server/resources

Resources are application-driven context. Servers expose readable data by URI,
and clients or hosts decide how to present or include that context.

## Server Capability

Declare the `resources` capability before registering resources or resource
templates. Set `subscribe = true` only when the server supports per-resource
update notifications, and set `listChanged = true` only when clients should be
notified after the resource catalog changes.

```kotlin
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

val server = Server(
    serverInfo = Implementation("resource-server", "1.0.0"),
    options = ServerOptions(
        capabilities = ServerCapabilities(
            resources = ServerCapabilities.Resources(
                subscribe = true,
                listChanged = true,
            ),
        ),
    ),
)
```

## Register A Resource

Use `addResource` for stable, known URIs. The handler runs when a client sends
`resources/read` for the matching URI.

```kotlin
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents

server.addResource(
    uri = "note://release/latest",
    name = "latest-release-note",
    description = "Last deployment summary.",
    mimeType = "text/markdown",
) { request ->
    val text = loadLatestReleaseNote()

    ReadResourceResult(
        contents = listOf(
            TextResourceContents(
                text = text,
                uri = request.uri,
                mimeType = "text/markdown",
            ),
        ),
    )
}
```

Return `TextResourceContents` only for content that is safe to represent as
text. Return `BlobResourceContents` with base64-encoded data for binary
resources.

```kotlin
import io.modelcontextprotocol.kotlin.sdk.types.BlobResourceContents

server.addResource(
    uri = "image://logo",
    name = "logo",
    description = "Application logo.",
    mimeType = "image/png",
) { request ->
    ReadResourceResult(
        contents = listOf(
            BlobResourceContents(
                blob = loadLogoPngBase64(),
                uri = request.uri,
                mimeType = "image/png",
            ),
        ),
    )
}
```

## Resource Templates

Use `addResourceTemplate` for parameterized URIs. Clients discover templates
with `resources/templates/list` and then read concrete URIs with
`resources/read`.

```kotlin
server.addResourceTemplate(
    uriTemplate = "file:///workspace/{path}",
    name = "workspace-file",
    description = "Read a file inside the current workspace.",
    mimeType = "text/plain",
) { request, variables ->
    val path = variables.getValue("path")
    val text = readWorkspaceFile(path)

    ReadResourceResult(
        contents = listOf(
            TextResourceContents(
                text = text,
                uri = request.uri,
                mimeType = "text/plain",
            ),
        ),
    )
}
```

Validate template variables before using them. For file-like resources, resolve
the requested path against an allowed root and reject traversal, symlinks, or
schemes outside your trust boundary.

## Client Discovery And Reads

Clients list resources and templates, then read concrete URIs.

```kotlin
import io.modelcontextprotocol.kotlin.sdk.types.ListResourceTemplatesRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams

val resources = client.listResources().resources
val templates = client.listResourceTemplates(ListResourceTemplatesRequest()).resourceTemplates

val latest = client.readResource(
    ReadResourceRequest(
        ReadResourceRequestParams(uri = "note://release/latest"),
    ),
)

showResourcePicker(resources, templates)
renderResource(latest.contents)
```

`ListResourcesResult` and `ListResourceTemplatesResult` include `nextCursor`
for pagination. Treat cursors as opaque and request the next page with the same
list request type.

## Subscriptions And Update Notifications

Clients may subscribe only when the server declared `resources.subscribe =
true`.

```kotlin
import io.modelcontextprotocol.kotlin.sdk.types.SubscribeRequest
import io.modelcontextprotocol.kotlin.sdk.types.SubscribeRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.UnsubscribeRequest

client.subscribeResource(
    SubscribeRequest(
        SubscribeRequestParams(uri = "note://release/latest"),
    ),
)

client.unsubscribeResource(UnsubscribeRequest(uri = "note://release/latest"))
```

When `subscribe = true`, registry changes to a concrete resource can emit
`notifications/resources/updated` to sessions subscribed to that URI. If the
underlying content changes without a registry mutation, send an update
notification to each affected session you track:

```kotlin
import io.modelcontextprotocol.kotlin.sdk.types.ResourceUpdatedNotification
import io.modelcontextprotocol.kotlin.sdk.types.ResourceUpdatedNotificationParams

server.sendResourceUpdated(
    sessionId = sessionId,
    notification = ResourceUpdatedNotification(
        ResourceUpdatedNotificationParams(uri = "note://release/latest"),
    ),
)
```

Send update notifications only after the resource content has actually changed.
Clients should re-read the URI after receiving the notification.

## Catalog Changes

When a server declares `resources.listChanged = true`, `addResource`,
`removeResource`, `addResourceTemplate`, and `removeResourceTemplate`
automatically notify active sessions after the registry changes.

Use `sendResourceListChanged(sessionId)` only when the catalog changes outside
the registry path or when a specific session needs a targeted refresh:

```kotlin
server.sendResourceListChanged(sessionId)
```

Only send manual list-change notifications after the catalog has actually
changed.

## Error Handling

Use JSON-RPC errors for missing or unreadable resources. The SDK throws
`McpException` with the MCP `RESOURCE_NOT_FOUND` code when no registered
resource or template matches the requested URI.

Handlers should surface authorization failures and invalid URI parameters as
protocol errors rather than returning partial or misleading contents.

## Security Checklist

- Validate every resource URI before reading data.
- Enforce access control before listing or reading sensitive resources.
- Normalize file paths and reject traversal outside approved roots.
- Do not expose local filesystem paths unless the user granted that root.
- Encode binary data as base64 in `BlobResourceContents`.
- Apply response size limits before returning large resources.
- Redact secrets from resource text and metadata.
- Treat custom URI schemes as application contracts and document them.
- Re-check permissions when sending update notifications.

See [Host validation guide](./host-validation.md) for JVM path and URI guard
examples that can be adapted to host applications.
