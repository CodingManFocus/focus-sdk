# Tier 1 SDK Capability Matrix

This document compares the Kotlin SDK against the current Tier 1 SDK model.
It is a planning artifact: use it to choose implementation and documentation
slices, not as a claim that Kotlin is already Tier 1.

## Official Baseline

Official MCP references:

- SDK listing: https://modelcontextprotocol.io/docs/sdk
- SDK tier requirements: https://modelcontextprotocol.io/community/sdk-tiers
- TypeScript SDK docs: https://ts.sdk.modelcontextprotocol.io/
- Python SDK docs: https://py.sdk.modelcontextprotocol.io/
- C# SDK docs: https://csharp.sdk.modelcontextprotocol.io/
- Go SDK docs: https://go.sdk.modelcontextprotocol.io/

As of 2026-07-06, the official SDK listing marks TypeScript, Python, C#, and
Go as Tier 1, while Kotlin is still Tier 3.

Tier 1 requires:

- 100% applicable conformance.
- All non-experimental features for the targeted MCP specification.
- Optional capabilities such as sampling and elicitation.
- New protocol feature planning before each new spec release.
- Comprehensive documentation with examples for all features.
- A stable release with clear versioning.
- Published dependency policy and roadmap.
- Issue triage within two business days.
- Critical bug resolution within seven days.
- Standardized issue type, status, and priority labels or GitHub issue types.

Experimental features, including Tasks, and extensions such as MCP Apps are not
required for Tier 1 unless the SDK chooses to claim production support for them.

## Tier 1 SDK Signals

The Tier 1 SDKs use different language idioms, but their documentation exposes
the same broad product shape.

| SDK | Public signals to model |
| --- | --- |
| TypeScript | Full MCP specification claim; high-level server and client APIs; stdio and Streamable HTTP; HTTP+SSE compatibility; sampling, form elicitation, URL elicitation, tasks examples; OAuth examples and reusable providers; API reference and troubleshooting. |
| Python | Full MCP specification claim; server/client docs; stdio, SSE, and Streamable HTTP; protocol features docs; authorization docs; low-level server docs; testing docs; experimental tasks docs. |
| C# | Official NuGet packages; conceptual and API docs; capability negotiation, transports, ping, progress, cancellation, tasks, MRTR, sampling, roots, elicitation, tools, resources, prompts, completions, logging, pagination, stateful/stateless HTTP, HTTP context, filters, and extensions docs. |
| Go | `mcp`, `jsonrpc`, `auth`, and `oauthex` packages; lifecycle, transports, authorization, security, cancellation, ping, progress, roots, sampling, elicitation, prompts, resources, tools, completion, logging, pagination, backwards compatibility, and rough-edge docs. |

## Kotlin SDK Status

Status legend:

- `Ready`: implemented and documented enough to use as Tier evidence.
- `Partial`: implemented but missing integration, examples, docs, or release
  evidence.
- `Gap`: not yet implemented or not yet evidenced.
- `Extension`: outside Tier 1 unless production support is explicitly claimed.

| Capability or requirement | Kotlin status | Current evidence | Next action |
| --- | --- | --- | --- |
| Official listing | Gap | Official SDK listing marks Kotlin as Tier 3. | Request tier advancement only after evidence is complete. |
| Target protocol version | Ready | `LATEST_PROTOCOL_VERSION` is `2025-11-25`; supported versions are documented in `docs/compatibility-and-release-policy.md`. | Keep updated when the stable MCP spec changes. |
| Applicable conformance | Ready, keep fresh | `docs/conformance-status.md` records a clean full run for server, client core, and client auth. | Refresh after protocol, transport, auth, or runner changes. |
| Client and server APIs | Ready | `Client` exposes typed operations for ping, prompts, resources, subscriptions, tools, completion, logging level, roots, and elicitation handlers; `Server` exposes registries and handlers for tools, prompts, resources, resource templates, sampling, roots, elicitation, logging, and notifications. | Add feature-specific guide pages beyond README. |
| Protocol primitives | Ready | Core/shared types and `Protocol` cover JSON-RPC framing, lifecycle, ping, cancellation, progress, pagination, metadata, capabilities, and request correlation. | Continue schema parity audits against the stable spec. |
| Tools | Ready, keep fresh | Server `addTool`/`removeTool`; client `listTools`/`callTool`; README examples; `docs/tools.md` covers structured output, output schemas, no-parameter schemas, catalog notifications, error handling, and security guidance. | Keep examples current after tool type or validation changes. |
| Resources | Ready, keep fresh | Server resources/templates/subscription notifications; client list/read/subscribe/unsubscribe; README examples; `docs/resources.md` covers resources, templates, subscriptions, catalog notifications, update notifications, URI validation, and security guidance. | Keep examples current after resource type, notification, or template matching changes. |
| Prompts | Ready, keep fresh | Server prompt registration; client list/get prompt; README examples; `docs/prompts-completion.md` covers prompt arguments, catalog notifications, client prompt calls, validation, and security guidance. | Keep examples current after prompt type or registry changes. |
| Completion | Ready, keep fresh | Server completion capability, high-level `setCompletionHandler`, client `complete`, README example, and `docs/prompts-completion.md` coverage for prompt and resource-template completions. | Keep examples current after completion type or validation changes. |
| Logging | Ready, keep fresh | Server logging notifications and client `setLoggingLevel`; README example; `docs/logging.md` covers capability declaration, client minimum levels, server notifications, client handlers, level filtering, and security guidance. | Keep examples current after logging type or filter changes. |
| Pagination | Ready, keep fresh | Cursor support in list operations; README pagination example; `docs/pagination.md` covers supported list operations, client loops, server cursors, invalid cursor handling, and cursor security guidance. | Keep examples current after pagination type or conformance changes. |
| Roots | Ready, keep fresh | Client root registry and roots/list_changed notification; server `listRoots`; README example; `docs/roots.md` covers root grants, server requests, list-change notifications, `file://` validation, and security guidance. | Keep examples current after root type or capability changes. |
| Sampling | Ready, keep fresh | Server `createMessage`; client sampling handler and sampling-tools validation; README example; `docs/sampling.md` covers client handlers, server requests, tool-enabled sampling, content blocks, and safety guidance. | Keep examples current after sampling type or validation changes. |
| Elicitation | Ready, keep fresh | Client/server surfaces include form and URL-mode elicitation, completion notification, URL elicitation required errors, README example, and `docs/elicitation.md` security guidance. | Keep examples current after elicitation type or security changes. |
| Authorization/OAuth | Partial | `McpOAuth.kt` includes protected-resource discovery, authorization-server discovery, Client ID Metadata Document JSON generation, dynamic client registration, PKCE S256, authorization URL building, authorization callback parsing with state validation, reusable authorization-code flow preparation/completion, authorization-code token exchange, refresh-token exchange, expiry-aware token snapshot/restore helpers, bearer request helpers, token-store-backed Streamable HTTP bootstrap helpers, client credentials token exchange, `private_key_jwt` assertion requests, JVM RS256 assertion signing, and Ktor bearer providers. Server helpers cover Protected Resource Metadata endpoints, Bearer challenge responses, request-level bearer guards, and verified JWT claims validation. `docs/auth-oauth.md` documents the current client flows, non-JVM assertion providers, JWKS publication, and JWT/JWKS verification responsibilities. | Promote the remaining OAuth integration from flow and transport helpers into a complete browser/callback server and token persistence experience where appropriate. |
| Streamable HTTP transport | Ready, keep fresh | Client and server transports support protocol headers, JSON/SSE responses, session handling, resumability hooks, retry handling, DNS rebinding controls, request-size limits, and `docs/streamable-http.md` guidance for deployment and security. | Keep conformance coverage current after transport changes. |
| Stdio transport | Ready | Client/server stdio transports and tests; README examples. | Keep lifecycle/error-handling tests current. |
| SSE compatibility transport | Ready | Client/server SSE transports remain available for older MCP clients; README marks Streamable HTTP as preferred. | Keep compatibility docs clear and avoid positioning SSE as preferred for new work. |
| WebSocket transport | Ready, non-core | Client/server WebSocket transport exists; README documents it as useful behind WebSocket-friendly proxies. | Treat as additional Kotlin value, not a Tier 1 requirement. |
| In-memory testing transport | Ready | `ChannelTransport.createLinkedPair()` is published in `kotlin-sdk-testing`. | Add more examples using in-memory client/server tests. |
| Tasks | Extension | Core task extension types and tests exist; roadmap explicitly excludes Tasks from Tier 1 blocking scope. | Keep documented as experimental/extension work only. |
| Release policy | Partial | `docs/compatibility-and-release-policy.md` documents protocol versions, release notes, API compatibility, validation gates, and `1.0.0` graduation. | Publish a `1.0.0` or later artifact when evidence is complete. |
| Dependency policy | Ready | `docs/dependency-update-policy.md` exists. | Keep cadence and validation gates current. |
| Roadmap | Ready | `docs/tier1-readiness.md` tracks phases, criteria, risks, and Tier request evidence. | Keep this matrix and roadmap synchronized after each slice. |
| Documentation coverage | Ready, keep fresh | README has broad examples; dedicated guides now cover auth/OAuth, elicitation, host validation, logging, pagination, prompts/completion, resources/templates/subscriptions, roots, sampling, Streamable HTTP, and tools. | Keep feature, auth, and security examples current after API or spec changes. |
| Maintenance labels and SLAs | Partial | `docs/maintenance-policy.md` documents the two-business-day triage commitment, seven-day P0 commitment, and required Type/Status/Priority label taxonomy. `CodingManFocus/focus-sdk` labels were verified on 2026-07-06 with `gh label list --repo CodingManFocus/focus-sdk --limit 100`. | Collect operational evidence that triage and P0 resolution metrics are being met before Tier request. |

## Priority Slices

1. Auth/OAuth parity:
   promote the remaining OAuth flow integration from helper APIs and examples
   into a complete reusable client/server experience.
2. Maintenance evidence:
   collect operational evidence that issue triage and P0 resolution metrics
   are being met against the published policy.
3. Release readiness:
   keep `apiCheck`, conformance output, dependency policy, release policy, and
   this matrix current before a `1.0.0` release candidate.
