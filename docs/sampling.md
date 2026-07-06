# Sampling Guide

This guide covers the Kotlin SDK APIs for MCP `sampling/createMessage`.

Official specification:
https://modelcontextprotocol.io/specification/2025-11-25/client/sampling

Sampling is a server-to-client request. The server asks the client to invoke an
LLM chosen by the client. The client controls provider selection, user approval,
and how much of the request or response is exposed to the user.

## Client Capabilities

Declare `sampling` only when the host application can execute model requests.
Use sub-capabilities to opt into optional request shapes:

- `ClientCapabilities.Sampling()` supports basic sampling.
- `tools = EmptyJsonObject` permits `tools` and `toolChoice` in sampling
  requests.
- `context = EmptyJsonObject` permits non-`none` `includeContext` requests.

```kotlin
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.EmptyJsonObject
import io.modelcontextprotocol.kotlin.sdk.types.Implementation

val client = Client(
    clientInfo = Implementation("example-client", "1.0.0"),
    options = ClientOptions(
        capabilities = ClientCapabilities(
            sampling = ClientCapabilities.Sampling(
                tools = EmptyJsonObject,
            ),
        ),
    ),
)
```

If a server sends `tools` or `toolChoice` and the client did not advertise
`sampling.tools`, the Kotlin client returns a JSON-RPC invalid params error.
The Kotlin server also refuses to send tool-enabled sampling requests to a
client that did not advertise `sampling.tools`.

## Client Handler

Register a `sampling/createMessage` handler with `setRequestHandler`. The
handler may call any local or remote model provider. Keep a user approval step
before the model call and before returning the model output to the server.

```kotlin
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageRequest
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageResult
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.StopReason
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

client.setRequestHandler<CreateMessageRequest>(Method.Defined.SamplingCreateMessage) { request, _ ->
    requireUserApprovalBeforeSampling(request)

    val providerResponse = callModelProvider(
        messages = request.params.messages,
        maxTokens = request.params.maxTokens,
        systemPrompt = request.params.systemPrompt,
        modelPreferences = request.params.modelPreferences,
    )

    requireUserApprovalBeforeReturning(providerResponse.text)

    CreateMessageResult(
        role = Role.Assistant,
        content = TextContent(providerResponse.text),
        model = providerResponse.model,
        stopReason = StopReason.EndTurn,
    )
}
```

`modelPreferences` are advisory. The client may ignore model hints or map them
to an equivalent provider/model.

## Server Request

From a server handler, call `createMessage` on the current `ClientConnection`.
The server expresses its prompt, maximum token count, and optional model
preferences. The client decides how to satisfy the request.

```kotlin
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageRequest
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ModelHint
import io.modelcontextprotocol.kotlin.sdk.types.ModelPreferences
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.SamplingMessage
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

server.addTool(
    name = "draft_summary",
    description = "Ask the client LLM to draft a short summary.",
) {
    val result = createMessage(
        CreateMessageRequest(
            CreateMessageRequestParams(
                maxTokens = 300,
                messages = listOf(
                    SamplingMessage(
                        role = Role.User,
                        content = TextContent("Summarize the latest project notes in three bullets."),
                    ),
                ),
                modelPreferences = ModelPreferences(
                    hints = listOf(ModelHint("fast")),
                    speedPriority = 0.8,
                ),
            ),
        ),
    )

    val text = result.content.filterIsInstance<TextContent>().firstOrNull()?.text
        ?: "The model returned no text content."
    CallToolResult.success("Draft from ${result.model}: $text")
}
```

Sampling is not a tool call from the client to the server. It is the reverse
direction: the server requests model work from the client.

## Tool-Enabled Sampling

When `sampling.tools` is available, the server may provide tools that the
client-side model can call during sampling. The model can return
`ToolUseContent`; the server then sends a later sampling request whose last
message contains the matching `ToolResultContent`.

```kotlin
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageRequest
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.SamplingMessage
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolChoice
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

val searchTool = Tool(
    name = "search_notes",
    description = "Search project notes.",
    inputSchema = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("query") {
                put("type", "string")
            }
        },
        required = listOf("query"),
    ),
)

val result = createMessage(
    CreateMessageRequest(
        CreateMessageRequestParams(
            maxTokens = 500,
            messages = listOf(
                SamplingMessage(Role.User, TextContent("Find the release blocker.")),
            ),
            tools = listOf(searchTool),
            toolChoice = ToolChoice(ToolChoice.Mode.Auto),
        ),
    ),
)
```

Kotlin validates the sampling message tail before sending tool-result turns:

- A message that contains a `ToolResultContent` must contain only tool results.
- Tool results must correspond to previous `ToolUseContent` IDs.
- Previous tool uses must be answered by the final tool-result message.

These checks prevent malformed tool loops from being sent to clients.

## Content Blocks

Sampling messages and results can include:

- `TextContent`
- `ImageContent`
- `AudioContent`
- `ToolUseContent` when the model asks to call a provided tool
- `ToolResultContent` when the server returns tool results to the model

The Kotlin API represents sampling content as a list. A single item serializes
to the legacy single-object wire shape; multiple items serialize to an array.

## Safety Checklist

- Show users the sampling request before calling the model.
- Show users the sampled result before returning it to the server.
- Treat model preferences as hints, not authority.
- Omit `sampling.tools` unless the host can safely handle tool use.
- Gate tool-enabled sampling behind explicit user and host policy.
- Do not leak hidden prompts, secrets, or unrelated client context into the
  provider call.
- Keep provider credentials in the client host, never in the MCP server.
