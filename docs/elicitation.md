# Elicitation Guide

This guide covers the Kotlin SDK APIs for MCP `elicitation/create` and
`notifications/elicitation/complete`.

Official specification:
https://modelcontextprotocol.io/specification/2025-11-25/client/elicitation

Elicitation is a server-to-client request. The server asks the client to
collect user input, and the client decides how to present the interaction.
MCP defines two modes:

- Form mode collects non-sensitive structured data in band.
- URL mode asks the user to open an external URL for sensitive or out-of-band
  work.

## Client Capabilities

Clients must advertise the modes they support during initialization. An empty
`ClientCapabilities.Elicitation()` is treated as form-mode support only. Set
`url = EmptyJsonObject` only when the host application can show the target
domain, ask for consent, open a secure browser context, and track completion.

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
            elicitation = ClientCapabilities.Elicitation(
                form = EmptyJsonObject,
                url = EmptyJsonObject,
            ),
        ),
    ),
)
```

## Client Handler

Register one handler for both modes. Form-mode responses may include `content`
only when the action is `Accept`; URL-mode responses omit content because the
interaction happens out of band.

```kotlin
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequestFormParams
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequestURLParams
import io.modelcontextprotocol.kotlin.sdk.types.ElicitResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

client.setElicitationHandler { request ->
    when (val params = request.params) {
        is ElicitRequestFormParams -> {
            val formData = showFormAndValidate(params.message, params.requestedSchema)

            if (formData == null) {
                ElicitResult(action = ElicitResult.Action.Cancel)
            } else {
                ElicitResult(
                    action = ElicitResult.Action.Accept,
                    content = buildJsonObject {
                        put("email", formData.email)
                        put("displayName", formData.displayName)
                    },
                )
            }
        }

        is ElicitRequestURLParams -> {
            val approved = askUserBeforeOpeningUrl(
                message = params.message,
                url = params.url,
            )

            if (approved) {
                openExternalBrowser(params.url)
                ElicitResult(action = ElicitResult.Action.Accept)
            } else {
                ElicitResult(action = ElicitResult.Action.Decline)
            }
        }
    }
}
```

The SDK applies default values from form-mode schemas when the handler accepts
and omits fields that define defaults. Clients should still validate user input
before returning it.

## Form Mode From A Server

Use form mode for ordinary, non-sensitive data such as profile fields or
workflow preferences. The requested schema is intentionally restricted to a flat
object with primitive properties.

```kotlin
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ElicitResult
import io.modelcontextprotocol.kotlin.sdk.types.StringSchema
import io.modelcontextprotocol.kotlin.sdk.types.StringSchemaFormat

server.addTool(
    name = "create_profile",
    description = "Create a profile after asking the user for contact details.",
) {
    val result = createElicitation(
        message = "Please provide contact details for the new profile.",
        requestedSchema = ElicitRequestParams.RequestedSchema(
            properties = mapOf(
                "email" to StringSchema(
                    title = "Email address",
                    format = StringSchemaFormat.Email,
                ),
                "displayName" to StringSchema(
                    title = "Display name",
                    minLength = 1,
                ),
            ),
            required = listOf("email"),
        ),
    )

    when (result.action) {
        ElicitResult.Action.Accept -> CallToolResult.success("Profile input accepted.")
        ElicitResult.Action.Decline -> CallToolResult.error("The user declined to provide profile data.")
        ElicitResult.Action.Cancel -> CallToolResult.error("The user dismissed the profile form.")
    }
}
```

Servers must not use form mode for passwords, API keys, access tokens, payment
credentials, or other secrets. Use URL mode instead.

## URL Mode From A Server

Use URL mode when the user must complete work on a trusted external page. The
client's `Accept` response means the user consented to open the URL; it does
not mean the external flow is complete.

```kotlin
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ElicitResult
import io.modelcontextprotocol.kotlin.sdk.types.ElicitationCompleteNotification
import io.modelcontextprotocol.kotlin.sdk.types.ElicitationCompleteNotificationParams
import io.modelcontextprotocol.kotlin.sdk.server.Server

server.addTool(
    name = "connect_account",
    description = "Ask the user to connect an external account.",
) {
    val elicitationId = newOpaqueElicitationId(sessionId)
    val result = createElicitation(
        message = "Connect your Example Co account to continue.",
        elicitationId = elicitationId,
        url = "https://example.com/connect?elicitationId=$elicitationId",
    )

    when (result.action) {
        ElicitResult.Action.Accept -> CallToolResult.success("Open the connection page and retry when complete.")
        ElicitResult.Action.Decline -> CallToolResult.error("The user declined account connection.")
        ElicitResult.Action.Cancel -> CallToolResult.error("The user dismissed account connection.")
    }
}

suspend fun notifyConnectionComplete(server: Server, sessionId: String, elicitationId: String) {
    server.sendElicitationComplete(
        sessionId = sessionId,
        notification = ElicitationCompleteNotification(
            ElicitationCompleteNotificationParams(elicitationId = elicitationId),
        ),
    )
}
```

Generate a new opaque `elicitationId` for each URL-mode request. Do not reuse
the session ID as the elicitation ID.

Clients can observe completion notifications:

```kotlin
client.setElicitationCompleteHandler { notification ->
    markExternalFlowComplete(notification.params.elicitationId)
}
```

Clients must ignore unknown or already-completed `elicitationId` values.

## URL Elicitation Required Errors

When a request cannot proceed until one or more URL-mode elicitations are
completed, servers can throw `UrlElicitationRequiredException`. Clients receive
it as a typed `McpException` subclass with the required URL elicitations.

```kotlin
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequestURLParams
import io.modelcontextprotocol.kotlin.sdk.types.UrlElicitationRequiredException

throw UrlElicitationRequiredException(
    elicitations = listOf(
        ElicitRequestURLParams(
            message = "Authorization is required to access Example Co files.",
            elicitationId = "example-auth-123",
            url = "https://example.com/oauth/start?elicitationId=example-auth-123",
        ),
    ),
)
```

## Security Checklist

- Show which MCP server is requesting information.
- Always offer decline and cancel paths.
- Let users review and edit form-mode responses before sending.
- Never request secrets through form mode.
- For URL mode, display the destination host and require consent before
  navigation.
- Prefer HTTPS URLs for URL mode; reject or heavily warn on other schemes.
- Bind stored elicitation state to the authenticated user, not only to
  `MCP-Session-Id`.
- Protect server-side state that records completed external interactions.
