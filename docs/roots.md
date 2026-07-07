# Roots Guide

This guide covers the Kotlin SDK APIs for MCP `roots/list` and
`notifications/roots/list_changed`.

Official specification:
https://modelcontextprotocol.io/specification/2025-11-25/client/roots

Roots are client-controlled filesystem boundaries. A client tells a server
which files or directories the server may operate on. Servers should treat
roots as authorization boundaries, not as suggestions.

## Client Capability

Declare the `roots` capability before registering roots. Set `listChanged =
true` only when the client will notify servers after the root list changes.

```kotlin
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation

val client = Client(
    clientInfo = Implementation("workspace-client", "1.0.0"),
    options = ClientOptions(
        capabilities = ClientCapabilities(
            roots = ClientCapabilities.Roots(listChanged = true),
        ),
    ),
)
```

## Register Roots

Use `addRoot` for a single user-approved root. Root URIs must be `file://`
URIs in the current MCP specification; the SDK validates this in `Root`.

```kotlin
client.addRoot(
    uri = "file:///Users/alice/projects/focus-sdk",
    name = "focus-sdk",
)
```

Use `addRoots` when you already have `Root` values or need to register several
roots at once.

```kotlin
import io.modelcontextprotocol.kotlin.sdk.types.Root

client.addRoots(
    listOf(
        Root(uri = "file:///Users/alice/repos/frontend", name = "frontend"),
        Root(uri = "file:///Users/alice/repos/backend", name = "backend"),
    ),
)
```

Only register roots that the user has explicitly granted to the connected
server. If access is revoked, remove the root before notifying the server.

## Server Requests

Servers request roots from a client with `listRoots`. Use the `Server` helper
when routing by session id:

```kotlin
val roots = server.listRoots(sessionId).roots

for (root in roots) {
    indexAllowedWorkspace(root.uri)
}
```

Inside lower-level session code, use `ServerSession.listRoots()`:

```kotlin
val roots = session.listRoots().roots
```

Servers should cache roots only as long as the session remains valid and should
re-check the root list before sensitive filesystem work.

## Root List Changes

When a client declares `roots.listChanged = true`, call
`sendRootsListChanged()` after adding or removing roots.

```kotlin
client.removeRoot("file:///Users/alice/repos/frontend")
client.sendRootsListChanged()
```

Send the notification only after the effective root list has changed. Servers
should respond by calling `roots/list` again before using cached roots.

If a server wants to react to the notification explicitly, install a
notification handler on the session:

```kotlin
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.RootsListChangedNotification
import kotlinx.coroutines.CompletableDeferred

session.setNotificationHandler<RootsListChangedNotification>(
    Method.Defined.NotificationsRootsListChanged,
) {
    refreshWorkspaceIndex()
    CompletableDeferred(Unit)
}
```

## Validating Server File Access

Servers should validate every file path against the current root list. A safe
implementation should:

- Parse only `file://` roots.
- Normalize paths before comparing them.
- Resolve symlinks according to the host application's policy.
- Reject traversal outside every approved root.
- Re-check roots after `notifications/roots/list_changed`.
- Treat removed roots as immediately revoked.

Do not infer permission from a path prefix string alone. Normalize the requested
path and the root path first, then compare the resulting filesystem paths using
platform-aware APIs.

## Error Handling

If a client does not declare the `roots` capability, the SDK rejects
`roots/list` handlers and requests through capability enforcement. Servers
should treat that as "no roots available" and either continue without
filesystem access or ask the user to grant roots through the host application.

If a root becomes unavailable, clients should remove it and send
`notifications/roots/list_changed` when `listChanged = true`.

## Security Checklist

- Prompt the user before exposing a root to a server.
- Register the narrowest useful directory, not a home directory by default.
- Never expose secrets-only directories such as credential stores.
- Keep root display names human-readable but not security-critical.
- Revoke roots when a project closes or permissions change.
- Log root grants and revocations without recording sensitive paths when policy
  requires redaction.
- Servers should validate every requested file operation against current roots.
- Servers should handle empty root lists and disappearing roots gracefully.
