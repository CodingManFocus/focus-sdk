# Maintenance Policy

This document records the maintenance commitments and issue-label taxonomy used
as MCP SDK Tier evidence for this fork.

Current repository-state evidence is tracked in
`docs/maintenance-evidence.md`.

Official Tiering reference:
https://modelcontextprotocol.io/community/sdk-tiers

## Commitments

- Triage new issues within two business days.
- Resolve P0 critical bugs within seven days of applying the `P0` label.
- Keep the dependency update policy and Tier 1 roadmap current.
- Re-run applicable conformance suites after protocol, transport, auth, or
  conformance-runner changes.

Issue triage means deciding whether an issue is valid and applying the first
appropriate Type, Status, or Priority label. It does not require resolving the
issue within the triage window.

## Required Labels

The MCP Tiering system uses consistent issue labels to report triage response
time and critical bug resolution time. This fork uses labels rather than
GitHub's native issue types.

The repository-local label baseline is `.github/labels.yml`.

### Type

Apply one Type label to every valid issue:

| Label | Meaning |
| --- | --- |
| `bug` | Something is not working. |
| `enhancement` | Request for a new feature. |
| `question` | Further information requested. |

### Status

Apply one Status label whenever it clarifies the next action:

| Label | Meaning |
| --- | --- |
| `needs confirmation` | Unclear if still relevant. |
| `needs repro` | Insufficient information to reproduce. |
| `ready for work` | Has enough information to start. |
| `good first issue` | Good for newcomers. |
| `help wanted` | Contributions welcome from those familiar with the codebase. |

### Priority

Apply a Priority label only when the issue is actionable:

| Label | Meaning |
| --- | --- |
| `P0` | Critical: core functionality failures or high-severity security. |
| `P1` | Significant bug affecting many users. |
| `P2` | Moderate issues or valuable feature requests. |
| `P3` | Nice-to-haves or rare edge cases. |

`P0` is reserved for security vulnerabilities with CVSS score 7.0 or higher,
or core MCP failures that prevent connection establishment, message exchange,
or use of core primitives such as tools, resources, or prompts.

## Triage Flow

1. Confirm whether the issue belongs in this Kotlin SDK fork.
2. Apply exactly one Type label for valid issues.
3. Apply a Status label that reflects the current blocker or readiness.
4. Apply a Priority label when the issue is actionable.
5. If the issue is `P0`, record the P0 start time in the issue discussion and
   prioritize a fix or documented mitigation within seven days.
6. If the issue is invalid, duplicate, unsupported, or intentionally not
   planned, apply the closest existing repository label and explain why.

## Fork Label Evidence

Verified on 2026-07-07 with:

```bash
gh label list --repo CodingManFocus/focus-sdk --limit 100
```

Required Tiering labels present in `CodingManFocus/focus-sdk`:

- Type: `bug`, `enhancement`, `question`
- Status: `needs confirmation`, `needs repro`, `ready for work`,
  `good first issue`, `help wanted`
- Priority: `P0`, `P1`, `P2`, `P3`

The labels are repository state, so they should be rechecked before requesting
an official Tier change.

GitHub Issues are enabled on `CodingManFocus/focus-sdk`, which is required for
collecting triage and P0 resolution evidence. See
`docs/maintenance-evidence.md` for the latest snapshot and collection
procedure.
