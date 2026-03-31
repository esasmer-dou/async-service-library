param(
    [string]$BaseUrl = "http://localhost:18080",
    [int]$Warmup = 1,
    [int]$Iterations = 3,
    [string]$OutputDirectory = ".\reports\real-sample",
    [Nullable[int]]$ReadyTimeoutSeconds = $null,
    [string]$Profile = "",
    [string]$ThresholdsPath = "",
    [string[]]$AdditionalThresholdsPaths = @(),
    [string]$ProfileResolutionPath = "",
    [string]$SummaryOutputPath = "",
    [Nullable[double]]$IdleAdminSummaryAvgMaxMs = $null,
    [Nullable[double]]$BacklogAdminSummaryAvgMaxMs = $null,
    [Nullable[int]]$MinimumBacklogQueueDepth = $null,
    [Nullable[int]]$MinimumBacklogFailedEntries = $null,
    [Nullable[int]]$MinimumBacklogHighAttentionItems = $null,
    [switch]$StartSample
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-EnvironmentValue {
    param([string]$Name)

    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value)) {
        return $null
    }

    return $value.Trim()
}

function Get-CombinedPathList {
    param(
        [string[]]$ExplicitValues,
        [string]$EnvironmentName
    )

    $combined = New-Object System.Collections.Generic.List[string]
    foreach ($value in @($ExplicitValues)) {
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            $combined.Add($value.Trim())
        }
    }

    $environmentValue = Get-EnvironmentValue -Name $EnvironmentName
    if (-not [string]::IsNullOrWhiteSpace($environmentValue)) {
        foreach ($value in ($environmentValue -split "[;\r\n]+")) {
            if (-not [string]::IsNullOrWhiteSpace($value)) {
                $combined.Add($value.Trim())
            }
        }
    }

    return @($combined | Select-Object -Unique)
}

$suiteScript = Join-Path $PSScriptRoot "generate-sample-benchmark-suite.ps1"
$assertScript = Join-Path $PSScriptRoot "assert-control-plane-benchmark.ps1"
$renderScript = Join-Path $PSScriptRoot "render-control-plane-benchmark-gate-outputs.ps1"

$resolvedReadyTimeoutSeconds = if ($null -ne $ReadyTimeoutSeconds) {
    [int]$ReadyTimeoutSeconds
} elseif (-not [string]::IsNullOrWhiteSpace((Get-EnvironmentValue -Name "ASL_BENCHMARK_READY_TIMEOUT_SECONDS"))) {
    [int](Get-EnvironmentValue -Name "ASL_BENCHMARK_READY_TIMEOUT_SECONDS")
} else {
    120
}

$resolvedThresholdsPath = if (-not [string]::IsNullOrWhiteSpace($ThresholdsPath)) { $ThresholdsPath } else { (Get-EnvironmentValue -Name "ASL_BENCHMARK_THRESHOLDS_PATH") }
$resolvedAdditionalThresholdsPaths = Get-CombinedPathList -ExplicitValues $AdditionalThresholdsPaths -EnvironmentName "ASL_BENCHMARK_EXTRA_THRESHOLDS_PATHS"
$resolvedProfileResolutionPath = if (-not [string]::IsNullOrWhiteSpace($ProfileResolutionPath)) { $ProfileResolutionPath } else { (Get-EnvironmentValue -Name "ASL_BENCHMARK_PROFILE_RESOLUTION_PATH") }
$resolvedSummaryOutputPath = if (-not [string]::IsNullOrWhiteSpace($SummaryOutputPath)) {
    $SummaryOutputPath
} else {
    Join-Path $OutputDirectory "control-plane-benchmark-gate-summary.json"
}
$resolvedSummaryMarkdownPath = Join-Path $OutputDirectory "control-plane-benchmark-gate-summary.md"
$resolvedReleaseNotePath = Join-Path $OutputDirectory "control-plane-benchmark-gate-release-note.md"
$resolvedTrendReportPath = Join-Path $OutputDirectory "control-plane-benchmark-gate-trend.md"
$historyDirectory = Join-Path $OutputDirectory "history"

$previousSummarySnapshotPath = ""
if (Test-Path $resolvedSummaryOutputPath) {
    $previousSummarySnapshotPath = Join-Path ([System.IO.Path]::GetTempPath()) ("asl-control-plane-benchmark-previous-{0}.json" -f ([guid]::NewGuid().ToString("N")))
    Copy-Item -Path $resolvedSummaryOutputPath -Destination $previousSummarySnapshotPath -Force
}

$suiteArguments = @{
    BaseUrl = $BaseUrl
    Warmup = $Warmup
    Iterations = $Iterations
    OutputDirectory = $OutputDirectory
    ReadyTimeoutSeconds = $resolvedReadyTimeoutSeconds
}
if ($StartSample) {
    $suiteArguments.StartSample = $true
}

& $suiteScript @suiteArguments
if ($LASTEXITCODE -ne 0) {
    throw "Benchmark suite generation failed."
}

& $assertScript `
    -IdleSummaryPath (Join-Path $OutputDirectory "summary-idle.json") `
    -BacklogSummaryPath (Join-Path $OutputDirectory "summary-backlog.json") `
    -IdleReportPath (Join-Path $OutputDirectory "control-plane-benchmark-sample-idle.md") `
    -BacklogReportPath (Join-Path $OutputDirectory "control-plane-benchmark-sample-backlog.md") `
    -Profile $Profile `
    -ThresholdsPath $resolvedThresholdsPath `
    -AdditionalThresholdsPaths $resolvedAdditionalThresholdsPaths `
    -ProfileResolutionPath $resolvedProfileResolutionPath `
    -SummaryOutputPath $resolvedSummaryOutputPath `
    -IdleAdminSummaryAvgMaxMs $IdleAdminSummaryAvgMaxMs `
    -BacklogAdminSummaryAvgMaxMs $BacklogAdminSummaryAvgMaxMs `
    -MinimumBacklogQueueDepth $MinimumBacklogQueueDepth `
    -MinimumBacklogFailedEntries $MinimumBacklogFailedEntries `
    -MinimumBacklogHighAttentionItems $MinimumBacklogHighAttentionItems
if ($LASTEXITCODE -ne 0) {
    throw "Benchmark threshold validation failed."
}

& $renderScript `
    -SummaryPath $resolvedSummaryOutputPath `
    -PreviousSummaryPath $previousSummarySnapshotPath `
    -MarkdownOutputPath $resolvedSummaryMarkdownPath `
    -ReleaseNoteOutputPath $resolvedReleaseNotePath `
    -TrendOutputPath $resolvedTrendReportPath
if ($LASTEXITCODE -ne 0) {
    throw "Benchmark output rendering failed."
}

$summaryArtifact = Get-Content -Path $resolvedSummaryOutputPath -Raw | ConvertFrom-Json
$historyStamp = ([datetime]$summaryArtifact.generatedAtUtc).ToString("yyyyMMdd-HHmmss")
if (-not (Test-Path $historyDirectory)) {
    New-Item -ItemType Directory -Path $historyDirectory -Force | Out-Null
}
$historyBaseName = "control-plane-benchmark-gate-{0}-{1}" -f $historyStamp, $summaryArtifact.profile.selected
Copy-Item -Path $resolvedSummaryOutputPath -Destination (Join-Path $historyDirectory ($historyBaseName + ".json")) -Force
Copy-Item -Path $resolvedSummaryMarkdownPath -Destination (Join-Path $historyDirectory ($historyBaseName + ".summary.md")) -Force
Copy-Item -Path $resolvedReleaseNotePath -Destination (Join-Path $historyDirectory ($historyBaseName + ".release-note.md")) -Force
Copy-Item -Path $resolvedTrendReportPath -Destination (Join-Path $historyDirectory ($historyBaseName + ".trend.md")) -Force

Write-Host "Control-plane benchmark gate completed successfully."
