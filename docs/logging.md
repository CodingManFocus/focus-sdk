# Logging Guide

This guide covers the Kotlin SDK APIs for MCP `logging/setLevel` and
`notifications/message`.

Official specification:
https://modelcontextprotocol.io/specification/2025-11-25/server/utilities/logging

Logging is a server-to-client utility. Servers send structured log
notifications to clients, and clients may set the minimum severity they want to
receive.

## Server Capability

Servers that emit log message notifications MUST declare the `logging`
capability.

```kotlin
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

val server = Server(
    serverInfo = Implementation("example-server", "1.0.0"),
    options = ServerOptions(
        capabilities = ServerCapabilities(
            logging = ServerCapabilities.Logging,
        ),
    ),
)
```

The Kotlin server registers the `logging/setLevel` handler only when this
capability is present. With strict capability enforcement enabled, the Kotlin
client refuses `setLoggingLevel` if the server did not advertise `logging`.

## Client Minimum Level

Clients call `setLoggingLevel` to request a minimum severity. The protocol uses
RFC 5424 severity names from least to most severe:

`debug`, `info`, `notice`, `warning`, `error`, `critical`, `alert`,
`emergency`.

```kotlin
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel

client.setLoggingLevel(LoggingLevel.Warning)
```

After the request above, the Kotlin server session sends `warning`, `error`,
`critical`, `alert`, and `emergency` messages to that client. It filters out
lower-severity `debug`, `info`, and `notice` messages. If the client has not
set a level, the Kotlin server does not apply this level filter.

## Sending Log Messages

Use `sendLoggingMessage` from a server session or from a `Server` by session id.
The notification payload contains a severity level, optional logger name, and
JSON-serializable data.

```kotlin
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotificationParams
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

session.sendLoggingMessage(
    LoggingMessageNotification(
        LoggingMessageNotificationParams(
            level = LoggingLevel.Info,
            logger = "startup",
            data = buildJsonObject {
                put("message", "Server started")
                put("component", "indexer")
            },
        ),
    ),
)
```

When routing outside session-local code, pass the session id to the server:

```kotlin
server.sendLoggingMessage(
    sessionId,
    LoggingMessageNotification(
        LoggingMessageNotificationParams(
            level = LoggingLevel.Error,
            logger = "worker",
            data = buildJsonObject { put("message", "Job failed") },
        ),
    ),
)
```

Use stable logger names such as `startup`, `resources`, `tools`, or a package
name. Keep `data` structured so clients can filter or search logs without
parsing strings.

## Receiving Log Messages

Clients can install a notification handler for `notifications/message` when
they want to surface server logs in a UI, persist them, or apply custom
filtering.

```kotlin
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.Method
import kotlinx.coroutines.CompletableDeferred

client.setNotificationHandler<LoggingMessageNotification>(
    Method.Defined.NotificationsMessage,
) { notification ->
    renderServerLog(
        level = notification.params.level,
        logger = notification.params.logger,
        data = notification.params.data,
    )
    CompletableDeferred(Unit)
}
```

Clients may display, filter, search, or persist these messages. If logs are
persisted, apply the same retention and access controls used for application
logs.

## Errors and Filtering

Servers should return standard JSON-RPC errors for invalid log level requests or
configuration failures. The Kotlin SDK represents log levels with
`LoggingLevel`, so invalid enum values fail during request decoding before
application handlers see them.

The level set by `logging/setLevel` is scoped to the server session that
received the request. Do not treat it as a global process log level unless the
host application explicitly wants that behavior.

## Security Checklist

- Never send credentials, tokens, API keys, private keys, or session cookies in
  log data.
- Avoid personal identifying information and redact user content unless the user
  explicitly opted into diagnostic logging.
- Do not include internal paths, hostnames, stack traces, or infrastructure
  details when they could help an attacker.
- Rate limit high-volume logs, especially `debug` and repeated failures.
- Validate structured `data` before forwarding logs from tools, resources, or
  upstream services.
- Use consistent logger names so clients can filter noisy subsystems.
- Control who can view persisted server logs in client UIs.
- Prefer short, actionable messages over dumping raw request or response bodies.
