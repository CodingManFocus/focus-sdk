# Compatibility and Release Policy

This document defines the compatibility expectations for Kotlin SDK releases.
It complements the dependency policy and the Tier 1 readiness roadmap.

## Current Status

- Current artifact version: `1.0.0`.
- Current stable MCP protocol target: `2025-11-25`.
- Supported MCP protocol versions:
  - `2025-11-25`
  - `2025-06-18`
  - `2025-03-26`
  - `2024-11-05`
- Source of truth for protocol versions:
  `SUPPORTED_PROTOCOL_VERSIONS` in
  `kotlin-sdk-core/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/types/common.kt`.

The official MCP Tier 1 criteria require a stable release at version `1.0.0`
or higher without a pre-release identifier. This fork is not release-policy
complete for Tier 1 until the SDK publishes the `1.0.0` or later artifact with
the evidence described below.

Official references:

- SDK tier requirements: https://modelcontextprotocol.io/community/sdk-tiers
- SDK listing: https://modelcontextprotocol.io/docs/sdk

## Versioning

Before `1.0.0`, the SDK may still make breaking public API changes, but each
breaking change must be intentional, documented in release notes, and backed by
an API dump update when public API changes.

Starting at `1.0.0`, release compatibility follows these rules:

- Patch releases must preserve public API and protocol behavior except for
  security fixes, correctness fixes, or changes required by the MCP
  specification.
- Minor releases may add public API, protocol capabilities, transports, or
  platform support while preserving existing public API compatibility.
- Major releases may remove deprecated API, remove supported Kotlin targets, or
  drop older MCP protocol versions after release notes include migration
  guidance.

## MCP Protocol Compatibility

Protocol support is explicit. A protocol version is supported only when it is
listed in `SUPPORTED_PROTOCOL_VERSIONS` and covered by tests appropriate to its
behavior.

Protocol changes require:

- Updating `LATEST_PROTOCOL_VERSION` when the newest stable MCP spec becomes
  the SDK target.
- Updating `SUPPORTED_PROTOCOL_VERSIONS` only after an explicit compatibility
  decision.
- Running the relevant JVM tests and conformance suite.
- Refreshing `docs/conformance-status.md` after protocol, transport, auth, or
  conformance-runner changes.
- Adding migration notes when behavior changes for an existing supported
  protocol version.

Older protocol versions should remain supported when the cost is limited to
negotiation, serialization, or compatibility behavior. Dropping a protocol
version is a breaking compatibility decision and belongs in a major release
unless the MCP project declares that version unsupported or unsafe.

## Public API Compatibility

The SDK uses Kotlin explicit API mode. Public API changes must be visible and
reviewable.

Required checks for public API changes:

- Run `./gradlew apiCheck`.
- Run `./gradlew apiDump` only for intentional public API changes.
- Include release notes that explain whether the change is additive,
  behavioral, deprecated, or breaking.
- Prefer deprecation before removal when the existing API can remain safe.

Public APIs include protocol types, client/server surfaces, transport classes,
auth helpers, builders, and published testing utilities.

## Release Notes

Every release should include:

- SDK version and publication date.
- Supported MCP protocol versions.
- Conformance status, including the runner version or a link to
  `docs/conformance-status.md`.
- Public API additions, deprecations, removals, and behavior changes.
- Transport, auth, serialization, and platform support changes.
- Dependency or Kotlin toolchain changes that affect users.
- Migration guidance for breaking changes.
- Known limitations, security notes, and follow-up issues when relevant.

Tier 1 release candidates should also include links to the Tier 1 evidence:
conformance output, feature documentation, dependency policy, roadmap, and this
release policy. The canonical evidence index is
`docs/tier1-advancement-evidence.md`.

The current release-note draft is maintained in `docs/release-notes.md`.

## Release Validation Gates

For documentation-only changes, run at least `git diff --check`.

Before a Tier 1 release candidate, generate a release readiness report:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/collect-release-readiness.ps1 -RunChecks -RunMaintenanceCheck -MaintenanceSince <yyyy-mm-dd>
```

Use `-MaintenanceSince` for the operational evidence window included in the
Tier request. The release readiness report embeds the maintenance collector
output so issue-label, triage, and P0 evidence are captured with the release
gate results.
The readiness report also records the collector's `Tier Evidence Status`; a
collector that runs successfully can still be `BLOCKED` if the evidence window
has missing labels, missing or late triage, late or open P0 issues, or no issue
records to measure.

When `-RunChecks` is used, each default Gradle validation gate is also recorded
as a PASS/BLOCKED row in the Tier 1 release checks table.
When `-RunChecks` or `-RunMaintenanceCheck` is omitted, the report records a
BLOCKED row for the missing validation or maintenance gate so incomplete reports
cannot be mistaken for Tier advancement evidence.
The readiness report also checks that the feature documentation inventory listed
in `docs/tier1-advancement-evidence.md` is present and contains fenced
examples.

The collector treats documentation, GitHub workflow, release-evidence script,
and test source-set changes after the last recorded conformance run as
non-runtime changes. Any runtime, protocol, build, production source,
transport, auth, or conformance harness change after the recorded revision
requires a fresh full conformance run before requesting Tier advancement.
Uncommitted runtime-impacting changes also keep the conformance coverage check
BLOCKED, even when the last committed `HEAD` is otherwise covered by the
recorded conformance revision.

For code changes, choose the narrowest validation that covers the affected
surface. The default release-readiness gate is:

- `./gradlew ktlintCheck`
- `./gradlew detekt`
- `./gradlew :kotlin-sdk-core:jvmTest`
- `./gradlew :kotlin-sdk-client:jvmTest`
- `./gradlew :kotlin-sdk-server:jvmTest`
- `./gradlew apiCheck`

Additional gates:

- Run the targeted conformance suite for protocol, transport, auth, or
  lifecycle changes.
- Run `./conformance-test/run-conformance.sh all` before updating Tier 1
  evidence or requesting Tier advancement.
- Run platform-specific compile or test tasks when a change affects JS, Wasm,
  Native, or published multiplatform metadata.

## Tier 1 Graduation Checklist

Before requesting Tier 1, the SDK should have:

- A published `1.0.0` or later artifact without a pre-release identifier.
- Current release notes following this policy.
- A clean `apiCheck` result.
- A release readiness report from `scripts/collect-release-readiness.ps1`
  showing the stable artifact version, current conformance evidence, and
  validation gate results.
- A current Tier advancement evidence index in
  `docs/tier1-advancement-evidence.md`.
- Current conformance evidence for the target stable protocol.
- Feature documentation covering supported client, server, transport, auth,
  sampling, elicitation, roots, prompts, resources, tools, completion, logging,
  pagination, cancellation, progress, and lifecycle behavior.
- A dependency update policy, roadmap, and maintenance commitments that match
  the official Tier 1 criteria.
