param(
    [switch]$RunChecks,
    [string]$OutFile = ""
)

$ErrorActionPreference = "Stop"

function Invoke-Capture {
    param(
        [string]$FilePath,
        [string[]]$Arguments
    )

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $FilePath @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = ($output -join [Environment]::NewLine)
    }
}

function Read-Property {
    param(
        [string]$Path,
        [string]$Name
    )

    $line = Get-Content -Path $Path | Where-Object { $_ -match "^$([regex]::Escape($Name))=(.+)$" } | Select-Object -First 1
    if ($null -eq $line) {
        return ""
    }
    return ($line -replace "^$([regex]::Escape($Name))=", "").Trim()
}

function Test-StableReleaseVersion {
    param([string]$Version)

    if ($Version -notmatch "^(\d+)\.(\d+)\.(\d+)$") {
        return $false
    }
    return [int]$Matches[1] -ge 1
}

function Get-RegexValue {
    param(
        [string]$Text,
        [string]$Pattern
    )

    $match = [regex]::Match($Text, $Pattern, [System.Text.RegularExpressions.RegexOptions]::Multiline)
    if (-not $match.Success) {
        return ""
    }
    return $match.Groups[1].Value
}

function Add-CheckResult {
    param(
        [System.Collections.Generic.List[object]]$Checks,
        [string]$Name,
        [bool]$Pass,
        [string]$Evidence
    )

    $Checks.Add([pscustomobject]@{
        Name = $Name
        Status = if ($Pass) { "PASS" } else { "BLOCKED" }
        Evidence = $Evidence
    })
}

$root = (Resolve-Path ".").Path
$gradleProperties = Join-Path $root "gradle.properties"
$commonTypes = Join-Path $root "kotlin-sdk-core/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/types/common.kt"
$conformanceStatus = Join-Path $root "docs/conformance-status.md"
$maintenanceEvidence = Join-Path $root "docs/maintenance-evidence.md"
$compatibilityPolicy = Join-Path $root "docs/compatibility-and-release-policy.md"
$dependencyPolicy = Join-Path $root "docs/dependency-update-policy.md"
$tierRoadmap = Join-Path $root "docs/tier1-readiness.md"
$capabilityMatrix = Join-Path $root "docs/tier1-sdk-capability-matrix.md"

$gitHead = (& git rev-parse --short HEAD).Trim()
$gitStatus = (& git status --short)
$version = Read-Property -Path $gradleProperties -Name "version"
$commonText = Get-Content -Raw -Path $commonTypes
$latestProtocol = Get-RegexValue -Text $commonText -Pattern 'LATEST_PROTOCOL_VERSION:\s*String\s*=\s*"([^"]+)"'
$supportedProtocols = @()
$supportedBlock = [regex]::Match(
    $commonText,
    'SUPPORTED_PROTOCOL_VERSIONS:\s*List<String>\s*=\s*listOf\((.*?)\)',
    [System.Text.RegularExpressions.RegexOptions]::Singleline
)
if ($supportedBlock.Success) {
    $entries = $supportedBlock.Groups[1].Value -split ","
    foreach ($entry in $entries) {
        $trimmed = $entry.Trim()
        if ($trimmed -eq "LATEST_PROTOCOL_VERSION") {
            $supportedProtocols += $latestProtocol
        } elseif ($trimmed -match '"(\d{4}-\d{2}-\d{2})"') {
            $supportedProtocols += $Matches[1]
        }
    }
}
$supportedProtocols = @($supportedProtocols | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique)

$conformanceText = Get-Content -Raw -Path $conformanceStatus
$conformanceRevision = Get-RegexValue -Text $conformanceText -Pattern 'Verified revision:\s*`([^`]+)`'
$conformanceDate = Get-RegexValue -Text $conformanceText -Pattern 'Last verified:\s*(\d{4}-\d{2}-\d{2})'

$checks = New-Object System.Collections.Generic.List[object]
Add-CheckResult -Checks $checks -Name "Clean working tree" -Pass ([string]::IsNullOrWhiteSpace(($gitStatus -join ""))) -Evidence "git status --short"
Add-CheckResult -Checks $checks -Name "Stable release version" -Pass (Test-StableReleaseVersion $version) -Evidence "gradle.properties version=$version"
Add-CheckResult -Checks $checks -Name "Latest protocol declared" -Pass (-not [string]::IsNullOrWhiteSpace($latestProtocol)) -Evidence "LATEST_PROTOCOL_VERSION=$latestProtocol"
Add-CheckResult -Checks $checks -Name "Supported protocol list declared" -Pass (@($supportedProtocols).Count -gt 0) -Evidence "SUPPORTED_PROTOCOL_VERSIONS=$($supportedProtocols -join ', ')"
Add-CheckResult -Checks $checks -Name "Conformance evidence matches HEAD" -Pass ($conformanceRevision -eq $gitHead) -Evidence "conformance revision=$conformanceRevision, HEAD=$gitHead, date=$conformanceDate"
Add-CheckResult -Checks $checks -Name "Compatibility policy present" -Pass (Test-Path $compatibilityPolicy) -Evidence $compatibilityPolicy
Add-CheckResult -Checks $checks -Name "Dependency policy present" -Pass (Test-Path $dependencyPolicy) -Evidence $dependencyPolicy
Add-CheckResult -Checks $checks -Name "Tier roadmap present" -Pass (Test-Path $tierRoadmap) -Evidence $tierRoadmap
Add-CheckResult -Checks $checks -Name "Capability matrix present" -Pass (Test-Path $capabilityMatrix) -Evidence $capabilityMatrix
Add-CheckResult -Checks $checks -Name "Maintenance evidence present" -Pass (Test-Path $maintenanceEvidence) -Evidence $maintenanceEvidence

$validationResults = @()
if ($RunChecks) {
    $commands = @(
        @("apiCheck"),
        @(":kotlin-sdk-core:jvmTest"),
        @(":kotlin-sdk-client:jvmTest"),
        @(":kotlin-sdk-server:jvmTest")
    )

    foreach ($command in $commands) {
        $result = Invoke-Capture -FilePath ".\gradlew.bat" -Arguments $command
        $validationResults += [pscustomobject]@{
            Command = ".\gradlew.bat $($command -join ' ')"
            ExitCode = $result.ExitCode
        }
    }
}

$timestamp = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("# Release Readiness Evidence Report")
$lines.Add("")
$lines.Add("Generated: $timestamp")
$lines.Add("")
$lines.Add("HEAD: ``$gitHead``")
$lines.Add("")
$lines.Add("Artifact version: ``$version``")
$lines.Add("")
$lines.Add("Latest MCP protocol: ``$latestProtocol``")
$lines.Add("")
$lines.Add("Supported MCP protocols: ``$($supportedProtocols -join '`, `')``")
$lines.Add("")
$lines.Add("## Tier 1 Release Checks")
$lines.Add("")
$lines.Add("| Check | Status | Evidence |")
$lines.Add("| --- | --- | --- |")
foreach ($check in $checks) {
    $lines.Add("| $($check.Name) | $($check.Status) | $($check.Evidence) |")
}
$lines.Add("")
if ($RunChecks) {
    $lines.Add("## Validation Commands")
    $lines.Add("")
    $lines.Add("| Command | Exit code |")
    $lines.Add("| --- | ---: |")
    foreach ($result in $validationResults) {
        $lines.Add("| ``$($result.Command)`` | $($result.ExitCode) |")
    }
    $lines.Add("")
} else {
    $lines.Add("Validation commands were not run. Re-run with ``-RunChecks`` before using this report as release-candidate evidence.")
    $lines.Add("")
}
$lines.Add("A Tier 1 request remains blocked until the artifact version is a published stable ``1.0.0`` or later release and all release validation gates pass.")

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
