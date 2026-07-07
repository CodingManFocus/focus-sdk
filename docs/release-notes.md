# Release Notes

This file tracks release-note content required for Tier 1 release readiness.
It is a draft until a `1.0.0` or later artifact is published.

## Unreleased: 1.0.0 Release Candidate Draft

Status: not published. The current artifact version is still `0.14.0`, so this
SDK is not release-complete for Tier 1.

Supported MCP protocol versions:

- `2025-11-25`
- `2025-06-18`
- `2025-03-26`
- `2024-11-05`

Conformance evidence:

- Latest full conformance evidence is recorded in `docs/conformance-status.md`.
- The release readiness collector verifies whether recorded conformance covers
  runtime code at the current HEAD.

Public API compatibility:

- Public API compatibility is checked with `./gradlew apiCheck`.
- Intentional public API changes require `./gradlew apiDump` and a note in this
  file describing whether each change is additive, behavioral, deprecated, or
  breaking.

Current Tier 1 evidence links:

- Capability matrix: `docs/tier1-sdk-capability-matrix.md`
- Readiness roadmap: `docs/tier1-readiness.md`
- Compatibility and release policy: `docs/compatibility-and-release-policy.md`
- Dependency update policy: `docs/dependency-update-policy.md`
- Maintenance policy: `docs/maintenance-policy.md`
- Maintenance evidence: `docs/maintenance-evidence.md`

Known release blockers:

- Publish a stable `1.0.0` or later artifact without a pre-release identifier.
- Generate a release readiness report with
  `scripts/collect-release-readiness.ps1 -RunChecks`.
- Preserve current full conformance evidence for all runtime-affecting changes.
- Collect operational issue triage and P0 resolution evidence over time.
- Open the official Tier advancement issue and receive SDK Working Group
  maintainer approval.

Changes to include before publishing:

- Public API additions, deprecations, removals, and behavior changes.
- Transport, auth, serialization, and platform support changes.
- Dependency or Kotlin toolchain changes that affect users.
- Migration guidance for any breaking changes.
- Known limitations, security notes, and follow-up issues.
