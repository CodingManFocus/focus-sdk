# MCP Tier 1 Readiness Roadmap

This document tracks the work required to move this Kotlin SDK fork toward MCP
Tier 1. It is intentionally evidence-based: every implementation task should be
backed by the official specification, conformance results, or behavior already
proven in a Tier 1 SDK.

## Current Target

- Current stable protocol target: `2025-11-25`.
- Kotlin SDK status in official SDK listing: Tier 3.
- Tier 1 model SDKs in the official listing: TypeScript, Python, C#, and Go.
- Track future release-candidate work separately from the stable Tier 1 target
  until the MCP project publishes a newer stable spec.

Official references:

- SDK tier requirements: https://modelcontextprotocol.io/community/sdk-tiers
- SDK listing: https://modelcontextprotocol.io/docs/sdk
- Latest stable schema reference: https://modelcontextprotocol.io/specification/2025-11-25/schema
- Latest stable transports: https://modelcontextprotocol.io/specification/2025-11-25/basic/transports
- Tier 1 SDK capability matrix: `docs/tier1-sdk-capability-matrix.md`

## Tier 1 Acceptance Criteria

The SDK can be considered ready to request Tier 1 only when all of these are
true:

- Applicable conformance tests pass at 100%, excluding only tests the official
  criteria exclude: experimental features, skipped/pending tests, disputed tests,
  and legacy tests the SDK does not claim to support.
- All non-experimental protocol features for the target stable spec are
  implemented on the appropriate side of the SDK.
- Optional but Tier 1-relevant capabilities are supported, including sampling
  and elicitation.
- New protocol features have an implementation plan before each new spec
  release, with scope adjusted to feature complexity.
- Documentation includes examples for all supported features.
- A dependency update policy and a published roadmap exist.
- Stable versioning and release notes make compatibility expectations clear.
- Issue triage happens within two business days.
- Critical bugs, including P0 security or core MCP operation failures, are
  resolved within seven days.
- Issue labels or GitHub issue types support the standardized Type, Status, and
  Priority reporting expected by the tiering system.
- The MCP SDK Working Group approves the Tier advancement request after the
  self-assessment, evidence submission, automated conformance validation, and
  GitHub maintenance-stat review.

## Tier 1 SDK Capability Model

The Tier 1 SDKs use different language idioms, but they share these product
properties:

- Client and server APIs are both first-class.
- Local and remote transports are supported, especially stdio and Streamable
  HTTP; SSE remains useful for compatibility.
- Protocol types are generated or maintained with close schema parity.
- Auth/OAuth support is represented as reusable SDK surface, not only example
  code.
- Docs include guided server/client examples plus feature-specific guides.
- Conformance or integration suites are easy to run and visible to maintainers.

Observed model SDK signals:

- TypeScript publishes split client/server packages, Streamable HTTP, stdio,
  auth helpers, framework middleware, examples, troubleshooting, and API docs.
- Python documents server and client APIs, stdio, Streamable HTTP, SSE, and a
  transport-free in-memory client flow.
- C# splits core, main, and ASP.NET Core packages and documents cross-application
  access support.
- Go publishes `mcp`, `jsonrpc`, `auth`, and `oauthex` packages with a version
  compatibility table and feature documentation.

## Kotlin SDK Readiness Snapshot

Strong signals already present:

- `LATEST_PROTOCOL_VERSION` is `2025-11-25`.
- Protocol support includes JSON-RPC, lifecycle, ping, progress, cancellation,
  pagination, metadata, tools, resources, prompts, completion, logging, roots,
  sampling, elicitation, and task extension types.
- Newer schema concepts exist in core types, including icons, titles, audio
  content, resource links, tool output schemas, sampling tools, URL-mode
  elicitation, and JSON Schema 2020-12 fields.
- Transport surface includes stdio, Streamable HTTP, SSE, WebSocket, and
  in-memory channel transport for tests.
- Streamable HTTP has session handling, protocol-version headers, resumability,
  DNS rebinding hooks, request-size limits, and SSE reconnection tests.
- `conformance-test` includes server, client, and client-auth entry points with
  an empty expected-failure baseline.
- `docs/dependency-update-policy.md` defines dependency update cadence,
  validation expectations, and compatibility rules.
- `docs/maintenance-policy.md` defines the issue triage SLA, P0 resolution
  SLA, and Type/Status/Priority label taxonomy, and records the fork label
  verification command.
- `docs/compatibility-and-release-policy.md` defines supported MCP protocol
  versions, API compatibility expectations, release note contents, validation
  gates, and the `1.0.0` graduation requirement for Tier 1.
- `docs/tier1-sdk-capability-matrix.md` records the TypeScript, Python, C#,
  and Go SDK signals being used as the Tier 1 model and maps them to Kotlin
  implementation status.

Known gaps and risks:

- The latest recorded conformance run is tracked in
  `docs/conformance-status.md`; it must be refreshed after protocol, transport,
  auth, or conformance-runner changes.
- The current artifact version is `0.14.0`; the official Tier 1 stable release
  criterion still requires a `1.0.0` or later release without a pre-release
  identifier.
- Client auth now has initial reusable discovery, bearer-header, PKCE S256,
  authorization request, authorization callback parsing with state validation,
  reusable authorization-code flow preparation/completion, authorization-code
  token exchange, refresh token primitives, client credentials token
  exchange/provider support, `private_key_jwt` assertion request support, JVM
  RS256 assertion signing, Client ID Metadata Document JSON generation, dynamic
  client registration support, JVM loopback callback receiver support, JVM
  system-browser authorization URL launching, a high-level JVM system-browser
  authorization-code flow helper,
  expiry-aware token JSON snapshot/restore helpers, token-store-backed
  Streamable HTTP bootstrap helpers, and dynamic Streamable HTTP bearer-header
  coverage. Server auth now has Protected Resource Metadata, Bearer challenge
  helpers, request-level bearer guard helpers, and verified JWT claims
  validation helpers, plus insufficient-scope parsing and bounded step-up retry
  tracking. The full SDK OAuth flow still needs host-specific token vault
  integration guidance where appropriate. OAuth helper validation now enforces
  HTTPS-or-loopback authorization endpoints for URL construction, keeps
  user-facing JVM browser launching HTTPS-only, and enforces HTTPS-or-loopback
  redirect URIs before authorization URL construction, client metadata
  generation, or dynamic registration requests.
- `docs/auth-oauth.md` now documents the current OAuth helper flow, non-JVM
  JWT assertion provider wiring, JWKS publication, and JWT/JWKS verification
  responsibilities, but the full SDK OAuth flow still needs integration beyond
  helper APIs.
- Dedicated feature guides now cover OAuth, Streamable HTTP, elicitation, host
  validation, logging, pagination, prompts/completion,
  resources/templates/subscriptions, roots, sampling, and tools.
- Maintenance labels and commitments are documented in
  `docs/maintenance-policy.md`, and current repository-state evidence is
  tracked in `docs/maintenance-evidence.md`. GitHub Issues are enabled and the
  required labels are present, but operational history showing that triage and
  P0 resolution metrics are being met still needs to be collected before a Tier
  request. The maintenance collector now reports a Tier Evidence Status, and
  release readiness keeps the current empty issue window BLOCKED rather than
  treating it as historical SLA proof.
- Tasks are experimental/extension work and should not block Tier 1 unless the
  SDK explicitly claims production support for the extension.
- The conformance README must stay synchronized with
  `conformance-test/run-conformance.sh`.

## Work Plan

Immediate priority after the 2026-07-07 Tier 1 audit:

1. Keep full conformance evidence current at HEAD.
2. Prepare `1.0.0` release gates: API compatibility, release notes,
   platform validation, and compatibility evidence.
3. Collect operational evidence that issue triage and P0 resolution metrics are
   being met, and embed that evidence in release readiness reports with
   `scripts/collect-release-readiness.ps1 -RunMaintenanceCheck`. Current
   release readiness correctly keeps this BLOCKED while there are no issue
   records to measure.
4. Limit further OAuth work to spec-required, conformance-relevant, or
   Tier-1-SDK-precedented SDK surface. Treat host-specific token vaults as
   integration guidance, not a Tier 1 blocker.

### Phase 1: Establish Evidence

- Run `./conformance-test/run-conformance.sh list` and save the available
  scenario inventory when the runner version changes.
- Run `./conformance-test/run-conformance.sh all` and refresh
  `docs/conformance-status.md` after protocol, transport, auth, or runner
  changes.
- Keep `conformance-baseline.yml` empty unless a known limitation is explicitly
  approved and tracked.

### Phase 2: Close Protocol and SDK Gaps

- Fix every non-experimental conformance failure for `2025-11-25`.
- Keep auth/OAuth conformance logic available as reusable client/server SDK
  APIs where the official spec expects SDK support. Further OAuth work should be
  limited to spec-required, conformance-relevant, or Tier-1-SDK-precedented
  surface such as metadata discovery, PKCE, `resource` parameters, bearer
  headers, refresh and step-up handling, server challenges and guards, and
  examples.
- Audit protocol type parity against the official schema reference.
- Harden Streamable HTTP against conformance and production edge cases:
  resumability, polling disconnects, standalone GET stream behavior, session
  lifecycle, protocol-version negotiation, and JSON/SSE content negotiation.
- Audit structured concurrency and cancellation paths in transports and session
  registries.

### Phase 3: Documentation and Release Readiness

- Keep security-sensitive host validation examples current as resource, roots,
  and tool host-integration APIs evolve.
- Keep auth/OAuth docs aligned with the reusable SDK helpers and clearly mark
  host-specific token vaults as application integration guidance rather than a
  Tier 1 blocker.
- Keep `docs/tier1-sdk-capability-matrix.md` synchronized with implementation
  and documentation progress.
- Keep Tasks documentation as extension documentation, separate from the Tier 1
  blocking checklist.
- Keep compatibility notes current for supported MCP spec versions.
- Keep public API dumps current with intentional API changes.
- Run before each commit when relevant:
  - `./gradlew :kotlin-sdk-core:jvmTest`
  - `./gradlew :kotlin-sdk-client:jvmTest`
  - `./gradlew :kotlin-sdk-server:jvmTest`
  - `./gradlew apiCheck`
  - targeted conformance suite for changed behavior

### Phase 4: Tier Request

- Have a subagent review the evidence and code changes before each commit.
- Request final Tier 1 readiness review from a subagent only after conformance is
  100% and docs/release evidence is complete.
- Prepare Tier advancement evidence for the MCP SDK Working Group:
  conformance output, feature matrix, docs links, dependency policy, release
  policy, and maintenance commitments.
- Open a Tier advancement issue in the official
  `modelcontextprotocol/modelcontextprotocol` repository with the supporting
  evidence, then track automated conformance validation, GitHub maintenance
  statistics, and SDK Working Group maintainer approval as release blockers.
