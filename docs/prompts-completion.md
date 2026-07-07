# Prompts And Completion Guide

This guide covers the Kotlin SDK APIs for MCP `prompts/list`, `prompts/get`,
`notifications/prompts/list_changed`, and `completion/complete`.

Official specifications:

- https://modelcontextprotocol.io/specification/2025-11-25/server/prompts
- https://modelcontextprotocol.io/specification/2025-11-25/server/utilities/completion

Prompts are user-controlled templates. Completion provides argument suggestions
for prompts and resource templates.

## Server Capabilities

Declare `prompts` before registering prompts. Set `listChanged = true` only
when clients should be notified after the prompt catalog changes.

Declare `completions` before registering a completion handler.

```kotlin
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

val server = Server(
    serverInfo = Implementation("prompt-server", "1.0.0"),
    options = ServerOptions(
        capabilities = ServerCapabilities(
            prompts = ServerCapabilities.Prompts(listChanged = true),
            completions = ServerCapabilities.Completions,
        ),
    ),
)
```

## Register Prompts

Use `addPrompt` for reusable prompt templates. Mark required arguments with
`PromptArgument(required = true)` and validate them in the handler before
building the returned messages.

```kotlin
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.Prompt
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

server.addPrompt(
    Prompt(
        name = "review_diff",
        title = "Review Diff",
        description = "Ask the model to review a unified diff.",
        arguments = listOf(
            PromptArgument(
                name = "diff",
                title = "Diff",
                description = "Unified diff to review.",
                required = true,
            ),
            PromptArgument(
                name = "focus",
                title = "Focus",
                description = "Optional review focus, such as security or tests.",
            ),
        ),
    ),
) { request ->
    val diff = request.arguments?.get("diff")
        ?: error("Missing required argument: diff")
    val focus = request.arguments?.get("focus") ?: "correctness, security, and tests"

    GetPromptResult(
        description = "Review a code change and return actionable findings.",
        messages = listOf(
            PromptMessage(
                role = Role.User,
                content = TextContent(
                    "Review this diff for $focus. Return findings first.\n\n$diff",
                ),
            ),
        ),
    )
}
```

Prompt handlers may return multiple `PromptMessage` values. Content may be
text, images, audio, or embedded resources if those content blocks are
appropriate for the prompt.

## Client Prompt Calls

Clients discover prompts with `listPrompts` and fetch a selected prompt with
`getPrompt`.

```kotlin
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequest
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequestParams

val prompts = client.listPrompts().prompts
val reviewPrompt = prompts.first { it.name == "review_diff" }

val prompt = client.getPrompt(
    GetPromptRequest(
        GetPromptRequestParams(
            name = reviewPrompt.name,
            arguments = mapOf(
                "diff" to diffText,
                "focus" to "regressions",
            ),
        ),
    ),
)

renderPromptMessages(prompt.messages)
```

The SDK preserves prompt metadata and pagination cursors in `ListPromptsResult`.
Treat `nextCursor` as opaque when requesting additional pages.

## Prompt Catalog Changes

When a server declares `prompts.listChanged = true`, `addPrompt` and
`removePrompt` automatically notify active sessions after the registry changes.

Use `sendPromptListChanged(sessionId)` only when the catalog changes outside
the registry path or when a specific session needs a targeted refresh:

```kotlin
server.sendPromptListChanged(sessionId)
```

Only send manual list-change notifications after the catalog has actually
changed.

## Completion Handler

Use `setCompletionHandler` to handle `completion/complete` requests for prompt
arguments and resource template arguments. Register the handler before creating
server sessions.

```kotlin
import io.modelcontextprotocol.kotlin.sdk.types.CompleteResult
import io.modelcontextprotocol.kotlin.sdk.types.PromptReference
import io.modelcontextprotocol.kotlin.sdk.types.ResourceTemplateReference

server.setCompletionHandler { request ->
    val values = when (val ref = request.ref) {
        is PromptReference -> completePromptArgument(
            promptName = ref.name,
            argumentName = request.argument.name,
            partial = request.argument.value,
            context = request.context?.arguments.orEmpty(),
        )
        is ResourceTemplateReference -> completeResourceTemplateArgument(
            uriTemplate = ref.uri,
            argumentName = request.argument.name,
            partial = request.argument.value,
            context = request.context?.arguments.orEmpty(),
        )
        else -> emptyList()
    }

    CompleteResult(
        completion = CompleteResult.Completion(
            values = values.take(100),
            total = values.size,
            hasMore = values.size > 100,
        ),
    )
}
```

`CompleteResult.Completion` enforces the MCP limit of at most 100 returned
values. Use `context.arguments` for dependent fields, such as filtering
repository names after an owner has already been selected.

## Client Completion Calls

Clients call `complete` with a prompt or resource-template reference, the
argument to complete, and optional context.

```kotlin
import io.modelcontextprotocol.kotlin.sdk.types.CompleteRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CompleteRequest
import io.modelcontextprotocol.kotlin.sdk.types.PromptReference

val result = client.complete(
    CompleteRequest(
        CompleteRequestParams(
            ref = PromptReference("review_diff"),
            argument = CompleteRequestParams.Argument(
                name = "focus",
                value = "sec",
            ),
            context = CompleteRequestParams.Context(
                arguments = mapOf("diff" to currentDiffPreview),
            ),
        ),
    ),
)

showCompletionOptions(result.completion.values)
```

The type-safe DSL can build the same request:

```kotlin
import io.modelcontextprotocol.kotlin.sdk.ExperimentalMcpApi
import io.modelcontextprotocol.kotlin.sdk.types.PromptReference
import io.modelcontextprotocol.kotlin.sdk.types.buildCompleteRequest

@OptIn(ExperimentalMcpApi::class)
val request = buildCompleteRequest {
    ref(PromptReference("review_diff"))
    argument("focus", "sec")
    context {
        put("diff", currentDiffPreview)
    }
}
```

## Validation And Errors

Prompt argument declarations help clients build a UI, but servers must still
validate every value in `prompts/get` and `completion/complete` handlers.
Return protocol errors for missing required arguments, unsupported prompt names,
unsupported references, or invalid context.

Do not return suggestions that disclose data the user could not otherwise see.
Completion requests can contain partial secrets, file paths, or identifiers, so
avoid logging raw values unless they are redacted.

## Security Checklist

- Treat prompt arguments as untrusted user input.
- Validate required arguments in the prompt handler.
- Escape or delimit user-provided text before embedding it into instructions.
- Keep prompt names stable; use `title` for display text.
- Filter completion results by the caller's authorization.
- Cap completion result size and compute cost.
- Avoid leaking hidden resources through completion suggestions.
- Redact prompt arguments and completion partials in logs.
- Send list-change notifications only after the catalog has changed.
