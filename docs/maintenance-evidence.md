# Maintenance Evidence

This document records repository-state evidence for MCP SDK Tier 1 maintenance
requirements. It complements `docs/maintenance-policy.md`.

Official Tiering reference:
https://modelcontextprotocol.io/community/sdk-tiers

## Current Snapshot

Verified on 2026-07-07 for `CodingManFocus/focus-sdk`.

Repository issue tracking:

During this verification, `gh issue list` initially reported that repository
issues were disabled. Issues were enabled on 2026-07-07 so the repository can
collect triage and P0 resolution evidence going forward.

```bash
gh repo view CodingManFocus/focus-sdk --json hasIssuesEnabled,nameWithOwner,url
```

Result:

```json
{"hasIssuesEnabled":true,"nameWithOwner":"CodingManFocus/focus-sdk","url":"https://github.com/CodingManFocus/focus-sdk"}
```

Issue inventory:

```bash
gh issue list --repo CodingManFocus/focus-sdk --state all --limit 200 --json number,title,state,createdAt,closedAt,labels,url
```

Result:

```json
[]
```

Required Tiering labels were present:

```bash
gh label list --repo CodingManFocus/focus-sdk --limit 100
```

- Type: `bug`, `enhancement`, `question`
- Status: `needs confirmation`, `needs repro`, `ready for work`,
  `good first issue`, `help wanted`
- Priority: `P0`, `P1`, `P2`, `P3`

## Interpretation

- GitHub Issues are enabled, so new issues can be used as the measurement
  surface for the two-business-day triage and seven-day P0 resolution
  commitments.
- The issue inventory was empty at this snapshot, so there were no open or
  historical issue records to evaluate for triage latency or P0 resolution
  latency.
- This snapshot does not prove historical SLA performance. It establishes the
  repository state needed to collect operational evidence going forward.

## Evidence Collection Procedure

Before requesting an official Tier change:

1. Re-run the commands in `Current Snapshot`.
2. Export all issues and preserve the JSON output as release evidence.
3. For every valid issue, verify that the first Type, Status, or Priority label
   was applied within two business days of `createdAt`.
4. For every issue labeled `P0`, verify that the issue was closed within seven
   days of the `P0` label event.
5. If there are no issues or no P0 issues, record that the period had no
   measurable incident for that metric rather than treating it as historical
   proof of SLA performance.

The `P0` label event time may require GitHub timeline data rather than
`gh issue list`, because the issue list output does not include per-label event
timestamps.
