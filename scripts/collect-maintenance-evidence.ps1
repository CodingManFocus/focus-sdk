param(
    [string]$Repo = "CodingManFocus/focus-sdk",
    [int]$Limit = 200,
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
    $triageAt = ""
    $triageBusinessDays = ""
    if ($null -ne $firstTriage) {
        $triageAtDate = ([datetime]$firstTriage.created_at).ToUniversalTime()
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

    $issueRows += [pscustomobject]@{
        Number = $issue.number
        Title = $issue.title
        State = $issue.state
        CreatedAt = Format-Utc $createdAt
        FirstTriageAt = $triageAt
        TriageBusinessDays = $triageBusinessDays
        TriageStatus = $triageStatus
        P0LabelAt = $p0At
        P0CloseDays = $p0CloseDays
        P0Status = $p0Status
        Url = $issue.url
    }
}

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
$lines.Add("## Required Labels")
$lines.Add("")
if ($missingLabels.Count -eq 0) {
    $lines.Add("All required Type, Status, and Priority labels are present.")
} else {
    $lines.Add("Missing labels: ``$($missingLabels -join '`, `')``")
}
$lines.Add("")
$lines.Add("## Issue SLA Summary")
$lines.Add("")
$lines.Add("- Issues inspected: $($issues.Count)")
$lines.Add("- Triage failures: $(($issueRows | Where-Object { $_.TriageStatus -eq 'FAIL' -or $_.TriageStatus -eq 'No triage label' }).Count)")
$lines.Add("- P0 failures: $(($issueRows | Where-Object { $_.P0Status -eq 'FAIL' -or $_.P0Status -eq 'OPEN' }).Count)")
$lines.Add("")
if ($issues.Count -eq 0) {
    $lines.Add("No issues were present in this snapshot. This is not historical SLA proof; it only records that no issue records were available to measure.")
} else {
    $lines.Add("| Issue | State | Created | First triage label | Triage business days | Triage status | P0 label | P0 close days | P0 status |")
    $lines.Add("| --- | --- | --- | --- | ---: | --- | --- | ---: | --- |")
    foreach ($row in $issueRows) {
        $issueLink = "[#$($row.Number)]($($row.Url))"
        $lines.Add("| $issueLink | $($row.State) | $($row.CreatedAt) | $($row.FirstTriageAt) | $($row.TriageBusinessDays) | $($row.TriageStatus) | $($row.P0LabelAt) | $($row.P0CloseDays) | $($row.P0Status) |")
    }
}
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
