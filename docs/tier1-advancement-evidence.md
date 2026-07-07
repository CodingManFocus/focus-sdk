# Tier 1 Advancement Evidence Index

This document is the evidence index to attach to a future MCP SDK Tier
advancement request. It is not a claim that this Kotlin SDK fork is already
Tier 1.

Official references:

- SDK tier requirements: https://modelcontextprotocol.io/community/sdk-tiers
- Official SDK listing: https://modelcontextprotocol.io/docs/sdk
- Target stable schema: https://modelcontextprotocol.io/specification/2025-11-25/schema

## Current Request Status

Status: `BLOCKED`

Blocking items:

- The official SDK listing still marks Kotlin as Tier 3.
- The artifact version is `1.0.0`, but the stable artifact has not yet been
  published with final validation evidence.
- Maintenance SLA evidence is not yet sufficient. GitHub Issues and labels are
  configured, but the current issue history does not prove two-business-day
  triage or seven-day P0 resolution.
- A final release readiness report with `-RunChecks -RunMaintenanceCheck` has
  not been produced for a `1.0.0` release candidate.
- The official advancement issue, automated conformance validation, GitHub
  maintenance-stat review, and SDK Working Group approval are still pending.

## Evidence Map

| Tier 1 requirement | Current evidence | Status |
| --- | --- | --- |
| 100% applicable conformance | `docs/conformance-status.md` records a clean full run for server, client core, and client auth at the latest runtime-covered revision. | Ready, keep fresh |
| All non-experimental feature coverage | `docs/tier1-sdk-capability-matrix.md` maps the Kotlin SDK against the official Tier 1 SDK model and supported MCP capabilities. | Ready, keep fresh |
| Sampling and elicitation | `docs/sampling.md`, `docs/elicitation.md`, README examples, and conformance coverage. | Ready, keep fresh |
| Streamable HTTP, stdio, and compatibility transports | `docs/streamable-http.md`, README examples, transport tests, and conformance coverage. | Ready, keep fresh |
| Authorization/OAuth | `docs/auth-oauth.md`, client/server OAuth helpers, client-auth conformance coverage, and the capability matrix. | Partial |
| Comprehensive documentation with examples | README plus feature guides for auth/OAuth, Streamable HTTP, elicitation, host validation, logging, pagination, prompts/completion, resources, roots, sampling, and tools. | Ready, keep fresh |
| Stable release and clear versioning | `gradle.properties`, `docs/compatibility-and-release-policy.md`, and `docs/release-notes.md`. | Blocked on publication evidence |
| Dependency policy | `docs/dependency-update-policy.md`. | Ready |
| Published roadmap | `docs/tier1-readiness.md`. | Ready |
| Issue triage within two business days | `docs/maintenance-policy.md`, `docs/maintenance-evidence.md`, and `scripts/collect-maintenance-evidence.ps1`. | Blocked on operational evidence |
| P0 critical bug resolution within seven days | `docs/maintenance-policy.md`, `docs/maintenance-evidence.md`, and `scripts/collect-maintenance-evidence.ps1`. | Blocked on operational evidence |
| Standardized issue labels or issue types | `docs/maintenance-policy.md` and `docs/maintenance-evidence.md`. | Ready, keep fresh |
| Release readiness gate results | `scripts/collect-release-readiness.ps1`. | Blocked until run with `-RunChecks -RunMaintenanceCheck` for a release candidate |

## Feature Documentation Inventory

The release readiness collector verifies that these documentation entry points
exist and contain fenced examples before a report can support a Tier
advancement request:

| Area | Evidence |
| --- | --- |
| Overview and examples | `README.md` |
| Authorization/OAuth | `docs/auth-oauth.md` |
| Streamable HTTP | `docs/streamable-http.md` |
| Elicitation | `docs/elicitation.md` |
| Host validation and security guidance | `docs/host-validation.md` |
| Logging | `docs/logging.md` |
| Pagination | `docs/pagination.md` |
| Prompts and completion | `docs/prompts-completion.md` |
| Resources, templates, and subscriptions | `docs/resources.md` |
| Roots | `docs/roots.md` |
| Sampling | `docs/sampling.md` |
| Tools | `docs/tools.md` |

## Required Final Evidence Commands

Run these before opening an official Tier advancement issue:

```powershell
.\gradlew.bat ktlintCheck detekt apiCheck :kotlin-sdk-core:jvmTest :kotlin-sdk-client:jvmTest :kotlin-sdk-server:jvmTest
```

```powershell
& 'C:\Program Files\Git\usr\bin\bash.exe' -lc 'cd /e/focus-sdk && ./conformance-test/run-conformance.sh all'
```

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/collect-release-readiness.ps1 -RunChecks -RunMaintenanceCheck -MaintenanceSince <yyyy-mm-dd>
```

The release readiness report must show a clean working tree, a stable
`1.0.0` or later artifact version, current conformance coverage, successful
validation gates, and sufficient maintenance SLA evidence before it can support
a Tier advancement request.

## Advancement Issue Checklist

The future issue in `modelcontextprotocol/modelcontextprotocol` should include:

- Link to the published `1.0.0` or later release.
- Link to the release readiness report generated with both validation and
  maintenance collection enabled.
- Link to `docs/conformance-status.md` and the exact conformance command output.
- Link to `docs/tier1-sdk-capability-matrix.md`.
- Links to the feature documentation and examples listed above.
- Link to the dependency update policy and release policy.
- Link to maintenance policy and measured maintenance evidence.
- Note that Tasks remain experimental/extension scope and are not claimed as a
  Tier 1 blocker.
- Request for automated conformance validation and SDK Working Group review.
