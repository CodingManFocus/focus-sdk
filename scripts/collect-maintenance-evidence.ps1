param(
    [string]$Repo = "CodingManFocus/focus-sdk",
    [int]$Limit = 200,
    [string]$Since = "",
    [string]$OutFile = ""
)

$ErrorActionPreference = "Stop"

$typeLabels = @("bug", "enhancement", "question")
$statusLabels = @("needs confirmation", "needs repro", "ready for work", "good first issue", "help wanted")
$priorityLabels = @("P0", "P1", "P2", "P3")
$triageLabels = $typeLabels + $statusLabels + $priorityLabels

function Invoke-GhJson {
    param([string[]]$Arguments)

    $output = & gh @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "gh $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
    }
    if ([string]::IsNullOrWhiteSpace($output)) {
        return $null
    }
    return $output | ConvertFrom-Json
}

function Format-Utc {
    param($Value)

    if ($null -eq $Value -or [string]::IsNullOrWhiteSpace([string]$Value)) {
        return ""
    }
    return ([datetime]$Value).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
}

function Get-BusinessDaysBetween {
    param(
        [datetime]$Start,
        [datetime]$End
    )

    if ($End -lt $Start) {
        return 0
    }

    $days = 0
    $cursor = $Start.Date
    $endDate = $End.Date
    while ($cursor -lt $endDate) {
        if ($cursor.DayOfWeek -ne [DayOfWeek]::Saturday -and $cursor.DayOfWeek -ne [DayOfWeek]::Sunday) {
            $days += 1
        }
        $cursor = $cursor.AddDays(1)
    }
    return $days
}

function Get-FirstLabelEvent {
    param(
        [object[]]$Timeline,
        [string[]]$Names
    )

    $events = @(
        $Timeline |
            Where-Object {
                $_.event -eq "labeled" -and
                $null -ne $_.label -and
                $Names -contains $_.label.name
            } |
            Sort-Object { [datetime]$_.created_at }
    )

    if ($events.Count -eq 0) {
        return $null
    }
    return $events[0]
}

$repoInfo = Invoke-GhJson -Arguments @("repo", "view", $Repo, "--json", "hasIssuesEnabled,nameWithOwner,url")
if (-not $repoInfo.hasIssuesEnabled) {
    throw "GitHub Issues are disabled for $Repo"
}

$sinceDate = $null
if (-not [string]::IsNullOrWhiteSpace($Since)) {
    $sinceDate = [datetime]::Parse(
        $Since,
        [System.Globalization.CultureInfo]::InvariantCulture,
        [System.Globalization.DateTimeStyles]::AssumeUniversal -bor [System.Globalization.DateTimeStyles]::AdjustToUniversal
    )
}

$labels = Invoke-GhJson -Arguments @("label", "list", "--repo", $Repo, "--limit", "200", "--json", "name")
$labelNames = @($labels | ForEach-Object { $_.name })
$missingLabels = @($triageLabels | Where-Object { $labelNames -notcontains $_ })

$issues = Invoke-GhJson -Arguments @(
    "issue", "list",
    "--repo", $Repo,
    "--state", "all",
    "--limit", [string]$Limit,
    "--json", "number,title,state,createdAt,closedAt,labels,url"
)
if ($null -eq $issues) {
    $issues = @()
}
$issues = @($issues)

$issueRows = @()
foreach ($issue in $issues) {
    $timeline = Invoke-GhJson -Arguments @(
        "api",
        "repos/$Repo/issues/$($issue.number)/timeline",
        "--paginate",
        "-H", "Accept: application/vnd.github+json"
    )
    if ($null -eq $timeline) {
        $timeline = @()
    }
    $timeline = @($timeline)

    $createdAt = ([datetime]$issue.createdAt).ToUniversalTime()
    $firstTriage = Get-FirstLabelEvent -Timeline $timeline -Names $triageLabels
    $p0Label = Get-FirstLabelEvent -Timeline $timeline -Names @("P0")
    $closedAt = $null
    if (-not [string]::IsNullOrWhiteSpace([string]$issue.closedAt)) {
        $closedAt = ([datetime]$issue.closedAt).ToUniversalTime()
    }

    $triageStatus = "No triage label"
    $triageLabel = ""
    $triageAt = ""
    $triageBusinessDays = ""
    if ($null -ne $firstTriage) {
        $triageAtDate = ([datetime]$firstTriage.created_at).ToUniversalTime()
        $triageLabel = $firstTriage.label.name
        $triageAt = Format-Utc $triageAtDate
        $triageBusinessDays = Get-BusinessDaysBetween -Start $createdAt -End $triageAtDate
        if ($triageBusinessDays -le 2) {
            $triageStatus = "PASS"
        } else {
            $triageStatus = "FAIL"
        }
    }

    $p0Status = "Not P0"
    $p0At = ""
    $p0CloseDays = ""
    $p0AtDate = $null
    if ($null -ne $p0Label) {
        $p0AtDate = ([datetime]$p0Label.created_at).ToUniversalTime()
        $p0At = Format-Utc $p0AtDate
        if ($null -eq $closedAt) {
            $p0Status = "OPEN"
        } else {
            $p0CloseDays = [math]::Round(($closedAt - $p0AtDate).TotalDays, 2)
            if ($p0CloseDays -le 7) {
                $p0Status = "PASS"
            } else {
                $p0Status = "FAIL"
            }
        }
    }

    $triageInWindow = $null -eq $sinceDate -or $createdAt -ge $sinceDate
    $p0InWindow = $false
    if ($null -ne $p0AtDate) {
        $p0InWindow = $null -eq $sinceDate -or $p0AtDate -ge $sinceDate
    }
    if (-not $triageInWindow -and -not $p0InWindow) {
        continue
    }

    $issueRows += [pscustomobject]@{
        Number = $issue.number
        Title = $issue.title
        State = $issue.state
        CreatedAt = Format-Utc $createdAt
        FirstTriageLabel = $triageLabel
        FirstTriageAt = $triageAt
        TriageBusinessDays = $triageBusinessDays
        TriageStatus = $triageStatus
        P0LabelAt = $p0At
        P0CloseDays = $p0CloseDays
        P0Status = $p0Status
        TriageInWindow = $triageInWindow
        P0InWindow = $p0InWindow
        Url = $issue.url
    }
}

$triageRows = @($issueRows | Where-Object { $_.TriageInWindow })
$p0Rows = @($issueRows | Where-Object { $_.P0InWindow })
$triageFailures = @($triageRows | Where-Object { $_.TriageStatus -eq "FAIL" })
$triageMissing = @($triageRows | Where-Object { $_.TriageStatus -eq "No triage label" })
$p0Failures = @($p0Rows | Where-Object { $_.P0Status -eq "FAIL" })
$p0Open = @($p0Rows | Where-Object { $_.P0Status -eq "OPEN" })

$timestamp = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("# Maintenance Evidence Report")
$lines.Add("")
$lines.Add("Generated: $timestamp")
$lines.Add("")
$lines.Add("Repository: [$($repoInfo.nameWithOwner)]($($repoInfo.url))")
$lines.Add("")
$lines.Add("Issues enabled: ``$($repoInfo.hasIssuesEnabled)``")
$lines.Add("")
$lines.Add("Issue limit: $Limit")
$lines.Add("")
if ($null -ne $sinceDate) {
    $lines.Add("Evidence window start: ``$(Format-Utc $sinceDate)``")
    $lines.Add("")
    $lines.Add("Window inclusion: issues created at or after the start, plus issues first labeled ``P0`` at or after the start.")
} else {
    $lines.Add("Evidence window start: all issues returned by ``gh issue list`` within the limit")
}
$lines.Add("")
$lines.Add("## Required Labels")
$lines.Add("")
if ($missingLabels.Count -eq 0) {
    $lines.Add("Status: ``PASS``")
    $lines.Add("")
    $lines.Add("All required Type, Status, and Priority labels are present.")
} else {
    $lines.Add("Status: ``BLOCKED``")
    $lines.Add("")
    $lines.Add("Missing labels: ``$($missingLabels -join '`, `')``")
}
$lines.Add("")
$lines.Add("## Issue SLA Summary")
$lines.Add("")
$lines.Add("- Issues included in report: $($issueRows.Count)")
$lines.Add("- Triage issues measured: $($triageRows.Count)")
$lines.Add("- Triage SLA: $($triageFailures.Count) late, $($triageMissing.Count) missing triage labels")
$lines.Add("- P0 SLA: $($p0Failures.Count) late, $($p0Open.Count) still open, $($p0Rows.Count) P0 issues measured")
$lines.Add("")
if ($issueRows.Count -eq 0) {
    $lines.Add("No issues matched this evidence window. This is not historical SLA proof; it only records that no issue records were available to measure.")
} else {
    $lines.Add("| Issue | State | Created | First triage label | First triage at | Triage business days | Triage status | P0 label | P0 close days | P0 status |")
    $lines.Add("| --- | --- | --- | --- | --- | ---: | --- | --- | ---: | --- |")
    foreach ($row in $issueRows) {
        $issueLink = "[#$($row.Number)]($($row.Url))"
        $lines.Add("| $issueLink | $($row.State) | $($row.CreatedAt) | $($row.FirstTriageLabel) | $($row.FirstTriageAt) | $($row.TriageBusinessDays) | $($row.TriageStatus) | $($row.P0LabelAt) | $($row.P0CloseDays) | $($row.P0Status) |")
    }
}
$lines.Add("")
$lines.Add("Triage is measured from issue creation to the first Type, Status, or Priority label event.")
$lines.Add("")
$lines.Add("P0 resolution is measured from the first ``P0`` label event to issue close.")

$report = $lines -join [Environment]::NewLine
if ([string]::IsNullOrWhiteSpace($OutFile)) {
    $report
} else {
    $directory = Split-Path -Parent $OutFile
    if (-not [string]::IsNullOrWhiteSpace($directory)) {
        New-Item -ItemType Directory -Force -Path $directory | Out-Null
    }
    Set-Content -Path $OutFile -Value $report -Encoding UTF8
}
