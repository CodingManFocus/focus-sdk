# Tools Guide

This guide covers the Kotlin SDK APIs for MCP `tools/list`,
`tools/call`, and `notifications/tools/list_changed`.

Official specification:
https://modelcontextprotocol.io/specification/2025-11-25/server/tools

Tools are model-controlled server features. A server exposes named operations,
and a client or host decides whether and when the model may call them.

## Server Capability

Declare the `tools` capability before registering tools. Set `listChanged =
true` only when the server will notify clients after the tool catalog changes.

```kotlin
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

val server = Server(
    serverInfo = Implementation("tool-server", "1.0.0"),
    options = ServerOptions(
        capabilities = ServerCapabilities(
            tools = ServerCapabilities.Tools(listChanged = true),
        ),
    ),
)
```

## Register A Tool

Use `addTool` with a JSON object input schema. The schema defaults to JSON
Schema 2020-12 when `$schema` is omitted.

```kotlin
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

server.addTool(
    name = "search_notes",
    title = "Search Notes",
    description = "Search project notes by keyword.",
    inputSchema = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("query") {
                put("type", "string")
                put("description", "Search query")
                put("minLength", 1)
            }
        },
        required = listOf("query"),
        additionalProperties = false,
    ),
    toolAnnotations = ToolAnnotations(
        readOnlyHint = true,
        destructiveHint = false,
        idempotentHint = true,
        openWorldHint = false,
    ),
) { request ->
    val query = request.arguments?.get("query")?.jsonPrimitive?.content
        ?: return@addTool CallToolResult.error("Missing required argument: query")

    val matches = searchNotes(query)
    CallToolResult(
        content = listOf(TextContent("Found ${matches.size} matching notes.")),
        structuredContent = buildJsonObject {
            put("count", matches.size)
            put("firstTitle", matches.firstOrNull()?.title ?: "")
        },
    )
}
```

Validate all inputs in the handler even when an `inputSchema` is present. The
schema helps clients and models construct calls; it is not a substitute for
server-side validation and authorization.

## Output Schema And Structured Content

Provide `outputSchema` when the tool returns machine-readable
`structuredContent`. If `outputSchema` is provided, the server must return
structured results that conform to it. The official spec also recommends
including a text block with serialized or summarized JSON for backwards
compatibility.

```kotlin
server.addTool(
    name = "get_weather",
    description = "Get current weather for a city.",
    inputSchema = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("city") {
                put("type", "string")
            }
        },
        required = listOf("city"),
        additionalProperties = false,
    ),
    outputSchema = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("temperatureCelsius") {
                put("type", "number")
            }
            putJsonObject("conditions") {
                put("type", "string")
            }
        },
        required = listOf("temperatureCelsius", "conditions"),
        additionalProperties = false,
    ),
) { request ->
    val city = request.arguments?.get("city")?.jsonPrimitive?.content
        ?: return@addTool CallToolResult.error("Missing required argument: city")
    val weather = lookupWeather(city)
    val structured = buildJsonObject {
        put("temperatureCelsius", weather.temperatureCelsius)
        put("conditions", weather.conditions)
    }

    CallToolResult(
        content = listOf(TextContent(structured.toString())),
        structuredContent = structured,
    )
}
```

Clients should validate `structuredContent` against `outputSchema` before
passing it to application code or an LLM.

## No-Parameter Tools

For a tool that intentionally accepts no arguments, use
`additionalProperties = false`.

```kotlin
server.addTool(
    name = "get_current_time",
    description = "Return the current server time.",
    inputSchema = ToolSchema(additionalProperties = false),
) {
    CallToolResult.success(currentServerTimeIso8601())
}
```

`ToolSchema()` without `additionalProperties = false` still serializes as a
valid object schema, but it permits arbitrary object properties.

## Client Calls

Clients discover tools with `listTools` and invoke them with `callTool`.
Arguments are converted to JSON values by the SDK.

```kotlin
val tools = client.listTools().tools
val search = tools.first { it.name == "search_notes" }

val result = client.callTool(
    name = search.name,
    arguments = mapOf("query" to "release blockers"),
)

if (result.isError == true) {
    showToolError(result.content)
} else {
    useToolResult(result.content, result.structuredContent)
}
```

Treat tool metadata and annotations as hints. Clients should not decide whether
a tool is safe only from `ToolAnnotations`, especially for untrusted servers.

## Tool Catalog Changes

When a server declares `tools.listChanged = true`, `addTool` and `removeTool`
automatically notify active sessions after the registry changes.

```kotlin
server.addTool(
    name = "new_tool",
    description = "A newly available operation.",
    inputSchema = ToolSchema(additionalProperties = false),
) {
    CallToolResult.success("ok")
}
```

Use `sendToolListChanged(sessionId)` only when the catalog changes outside the
registry path or when a specific session needs a targeted refresh:

```kotlin
server.sendToolListChanged(sessionId)
```

Only send manual notifications after the catalog has actually changed.

## Error Handling

Use protocol errors for malformed requests or unsupported operations. Use a
tool result with `isError = true` for execution failures that a model can
correct and retry.

```kotlin
return@addTool CallToolResult.error(
    "Invalid date: departureDate must be in the future.",
)
```

Tool execution errors should be actionable. Include enough detail for the model
or user to adjust the next call, but do not include secrets or internal stack
traces.

## Security Checklist

- Validate every argument on the server.
- Enforce authorization before accessing external systems or user data.
- Rate limit expensive or sensitive tools.
- Sanitize tool output before returning it.
- Prompt users before destructive, external, or high-cost operations.
- Show tool inputs before invocation when data could be exfiltrated.
- Treat `ToolAnnotations` as untrusted hints unless the server is trusted.
- Use timeouts and cancellation for long-running calls.
- Log sensitive tool usage in an audit-friendly way without storing secrets.
