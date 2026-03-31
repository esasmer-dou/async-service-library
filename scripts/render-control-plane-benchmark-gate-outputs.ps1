param(
    [string]$SummaryPath = ".\reports\real-sample\control-plane-benchmark-gate-summary.json",
    [string]$PreviousSummaryPath = "",
    [string]$MarkdownOutputPath = "",
    [string]$ReleaseNoteOutputPath = "",
    [string]$TrendOutputPath = ""
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

function Read-JsonFile {
    param([string]$Path)

    if (-not (Test-Path $Path)) {
        throw "JSON file not found: $Path"
    }

    return Get-Content $Path -Raw | ConvertFrom-Json
}

function Format-Scalar {
    param([object]$Value)

    if ($null -eq $Value) {
        return "-"
    }

    if ($Value -is [double] -or $Value -is [float] -or $Value -is [decimal]) {
        return ([double]$Value).ToString("0.##", [System.Globalization.CultureInfo]::InvariantCulture)
    }

    return [string]$Value
}

function Format-Delta {
    param(
        [object]$CurrentValue,
        [object]$PreviousValue,
        [string]$Suffix = ""
    )

    if ($null -eq $CurrentValue -or $null -eq $PreviousValue) {
        return "-"
    }

    $delta = [double]$CurrentValue - [double]$PreviousValue
    $prefix = if ($delta -gt 0) { "+" } elseif ($delta -lt 0) { "" } else { "" }
    return "{0}{1}{2}" -f $prefix, $delta.ToString("0.##", [System.Globalization.CultureInfo]::InvariantCulture), $Suffix
}

function Join-MarkdownLines {
    param([string[]]$Lines)

    return ($Lines -join [Environment]::NewLine) + [Environment]::NewLine
}

$summary = Read-JsonFile -Path $SummaryPath
$previousSummary = $null
if (-not [string]::IsNullOrWhiteSpace($PreviousSummaryPath) -and (Test-Path $PreviousSummaryPath)) {
    $previousSummary = Read-JsonFile -Path $PreviousSummaryPath
}

$outputDirectory = Split-Path -Parent $SummaryPath
if ([string]::IsNullOrWhiteSpace($MarkdownOutputPath)) {
    $MarkdownOutputPath = Join-Path $outputDirectory "control-plane-benchmark-gate-summary.md"
}
if ([string]::IsNullOrWhiteSpace($ReleaseNoteOutputPath)) {
    $ReleaseNoteOutputPath = Join-Path $outputDirectory "control-plane-benchmark-gate-release-note.md"
}
if ([string]::IsNullOrWhiteSpace($TrendOutputPath)) {
    $TrendOutputPath = Join-Path $outputDirectory "control-plane-benchmark-gate-trend.md"
}

$repoRoot = Split-Path -Parent (Split-Path -Parent $outputDirectory)
$turkishOutputDirectory = Join-Path $repoRoot "tr\reports\real-sample"
if (-not (Test-Path $turkishOutputDirectory)) {
    New-Item -ItemType Directory -Path $turkishOutputDirectory -Force | Out-Null
}
$turkishSummaryPath = Join-Path $turkishOutputDirectory "control-plane-benchmark-gate-summary.md"
$turkishReleaseNotePath = Join-Path $turkishOutputDirectory "control-plane-benchmark-gate-release-note.md"
$turkishTrendPath = Join-Path $turkishOutputDirectory "control-plane-benchmark-gate-trend.md"

$summaryMarkdownLines = @(
    "# Control Plane Benchmark Gate Summary",
    "",
    ('- Status: `{0}`' -f $summary.status),
    ('- Profile: `{0}` (`{1}`)' -f $summary.profile.selected, $summary.profile.source),
    ('- Branch: `{0}`' -f (Format-Scalar -Value $summary.profile.branchName)),
    ('- Release tag: `{0}`' -f (Format-Scalar -Value $summary.profile.releaseTag)),
    ('- Generated at (UTC): `{0}`' -f $summary.generatedAtUtc),
    "",
    "| Signal | Value | Threshold |",
    "| --- | --- | --- |",
    ("| Idle pressure | {0} | LOW |" -f $summary.idle.overallPressure),
    ("| Idle queue depth | {0} | 0 |" -f (Format-Scalar -Value $summary.idle.totalQueueDepth)),
    ("| Idle failed entries | {0} | 0 |" -f (Format-Scalar -Value $summary.idle.failedEntries)),
    ("| Idle Admin Summary avg ms | {0} | <= {1} |" -f (Format-Scalar -Value $summary.idle.adminSummaryAvgMs), (Format-Scalar -Value $summary.thresholds.idleAdminSummaryAvgMaxMs)),
    ("| Backlog pressure | {0} | HIGH |" -f $summary.backlog.overallPressure),
    ("| Backlog queue depth | {0} | >= {1} |" -f (Format-Scalar -Value $summary.backlog.totalQueueDepth), (Format-Scalar -Value $summary.thresholds.minimumBacklogQueueDepth)),
    ("| Backlog failed entries | {0} | >= {1} |" -f (Format-Scalar -Value $summary.backlog.failedEntries), (Format-Scalar -Value $summary.thresholds.minimumBacklogFailedEntries)),
    ("| Backlog HIGH attention items | {0} | >= {1} |" -f (Format-Scalar -Value $summary.backlog.highAttentionItems), (Format-Scalar -Value $summary.thresholds.minimumBacklogHighAttentionItems)),
    ("| Backlog Admin Summary avg ms | {0} | <= {1} |" -f (Format-Scalar -Value $summary.backlog.adminSummaryAvgMs), (Format-Scalar -Value $summary.thresholds.backlogAdminSummaryAvgMaxMs))
)

if (@($summary.failures).Count -gt 0) {
    $summaryMarkdownLines += ""
    $summaryMarkdownLines += "## Failures"
    $summaryMarkdownLines += ""
    foreach ($failure in $summary.failures) {
        $summaryMarkdownLines += ('- {0}' -f $failure)
    }
}

$releaseNoteLines = @(
    "## Control Plane Benchmark Gate",
    "",
    ('- Status: `{0}`' -f $summary.status),
    ('- Resolved profile: `{0}` (`{1}`)' -f $summary.profile.selected, $summary.profile.source),
    ('- Idle pressure / queue / failures: `{0}` / `{1}` / `{2}`' -f $summary.idle.overallPressure, (Format-Scalar -Value $summary.idle.totalQueueDepth), (Format-Scalar -Value $summary.idle.failedEntries)),
    ('- Backlog pressure / queue / failures: `{0}` / `{1}` / `{2}`' -f $summary.backlog.overallPressure, (Format-Scalar -Value $summary.backlog.totalQueueDepth), (Format-Scalar -Value $summary.backlog.failedEntries)),
    ('- Admin Summary avg ms: idle `{0}`, backlog `{1}`' -f (Format-Scalar -Value $summary.idle.adminSummaryAvgMs), (Format-Scalar -Value $summary.backlog.adminSummaryAvgMs))
)

$trendLines = @(
    "# Control Plane Benchmark Trend",
    ""
)
if ($null -eq $previousSummary) {
    $trendLines += "No previous gate summary was available for comparison."
} else {
    $trendLines += ('Comparing current run against previous summary generated at `{0}`.' -f $previousSummary.generatedAtUtc)
    $trendLines += ""
    $trendLines += "| Metric | Previous | Current | Delta |"
    $trendLines += "| --- | --- | --- | --- |"
    $trendLines += ("| Profile | {0} | {1} | - |" -f (Format-Scalar -Value $previousSummary.profile.selected), (Format-Scalar -Value $summary.profile.selected))
    $trendLines += ("| Idle Admin Summary avg ms | {0} | {1} | {2} |" -f (Format-Scalar -Value $previousSummary.idle.adminSummaryAvgMs), (Format-Scalar -Value $summary.idle.adminSummaryAvgMs), (Format-Delta -CurrentValue $summary.idle.adminSummaryAvgMs -PreviousValue $previousSummary.idle.adminSummaryAvgMs -Suffix " ms"))
    $trendLines += ("| Backlog Admin Summary avg ms | {0} | {1} | {2} |" -f (Format-Scalar -Value $previousSummary.backlog.adminSummaryAvgMs), (Format-Scalar -Value $summary.backlog.adminSummaryAvgMs), (Format-Delta -CurrentValue $summary.backlog.adminSummaryAvgMs -PreviousValue $previousSummary.backlog.adminSummaryAvgMs -Suffix " ms"))
    $trendLines += ("| Backlog queue depth | {0} | {1} | {2} |" -f (Format-Scalar -Value $previousSummary.backlog.totalQueueDepth), (Format-Scalar -Value $summary.backlog.totalQueueDepth), (Format-Delta -CurrentValue $summary.backlog.totalQueueDepth -PreviousValue $previousSummary.backlog.totalQueueDepth))
    $trendLines += ("| Backlog failed entries | {0} | {1} | {2} |" -f (Format-Scalar -Value $previousSummary.backlog.failedEntries), (Format-Scalar -Value $summary.backlog.failedEntries), (Format-Delta -CurrentValue $summary.backlog.failedEntries -PreviousValue $previousSummary.backlog.failedEntries))
    $trendLines += ("| Backlog HIGH attention items | {0} | {1} | {2} |" -f (Format-Scalar -Value $previousSummary.backlog.highAttentionItems), (Format-Scalar -Value $summary.backlog.highAttentionItems), (Format-Delta -CurrentValue $summary.backlog.highAttentionItems -PreviousValue $previousSummary.backlog.highAttentionItems))
}

$summaryMarkdown = Join-MarkdownLines -Lines $summaryMarkdownLines
$releaseNoteMarkdown = Join-MarkdownLines -Lines $releaseNoteLines
$trendMarkdown = Join-MarkdownLines -Lines $trendLines

$turkishSummaryLines = @(
    "# Kontrol Duzlemi Benchmark Gate Ozeti",
    "",
    ('- Durum: `{0}`' -f $summary.status),
    ('- Profil: `{0}` (`{1}`)' -f $summary.profile.selected, $summary.profile.source),
    ('- Branch: `{0}`' -f (Format-Scalar -Value $summary.profile.branchName)),
    ('- Release tag: `{0}`' -f (Format-Scalar -Value $summary.profile.releaseTag)),
    ('- UTC uretim zamani: `{0}`' -f $summary.generatedAtUtc),
    "",
    "| Sinyal | Deger | Esik |",
    "| --- | --- | --- |",
    ("| Idle pressure | {0} | LOW |" -f $summary.idle.overallPressure),
    ("| Idle queue depth | {0} | 0 |" -f (Format-Scalar -Value $summary.idle.totalQueueDepth)),
    ("| Idle failed entries | {0} | 0 |" -f (Format-Scalar -Value $summary.idle.failedEntries)),
    ("| Idle Admin Summary avg ms | {0} | <= {1} |" -f (Format-Scalar -Value $summary.idle.adminSummaryAvgMs), (Format-Scalar -Value $summary.thresholds.idleAdminSummaryAvgMaxMs)),
    ("| Backlog pressure | {0} | HIGH |" -f $summary.backlog.overallPressure),
    ("| Backlog queue depth | {0} | >= {1} |" -f (Format-Scalar -Value $summary.backlog.totalQueueDepth), (Format-Scalar -Value $summary.thresholds.minimumBacklogQueueDepth)),
    ("| Backlog failed entries | {0} | >= {1} |" -f (Format-Scalar -Value $summary.backlog.failedEntries), (Format-Scalar -Value $summary.thresholds.minimumBacklogFailedEntries)),
    ("| Backlog HIGH attention items | {0} | >= {1} |" -f (Format-Scalar -Value $summary.backlog.highAttentionItems), (Format-Scalar -Value $summary.thresholds.minimumBacklogHighAttentionItems)),
    ("| Backlog Admin Summary avg ms | {0} | <= {1} |" -f (Format-Scalar -Value $summary.backlog.adminSummaryAvgMs), (Format-Scalar -Value $summary.thresholds.backlogAdminSummaryAvgMaxMs))
)

if (@($summary.failures).Count -gt 0) {
    $turkishSummaryLines += ""
    $turkishSummaryLines += "## Hatalar"
    $turkishSummaryLines += ""
    foreach ($failure in $summary.failures) {
        $turkishSummaryLines += ('- {0}' -f $failure)
    }
}

$turkishReleaseNoteLines = @(
    "## Kontrol Duzlemi Benchmark Gate",
    "",
    ('- Durum: `{0}`' -f $summary.status),
    ('- Cozulen profil: `{0}` (`{1}`)' -f $summary.profile.selected, $summary.profile.source),
    ('- Idle pressure / queue / failure: `{0}` / `{1}` / `{2}`' -f $summary.idle.overallPressure, (Format-Scalar -Value $summary.idle.totalQueueDepth), (Format-Scalar -Value $summary.idle.failedEntries)),
    ('- Backlog pressure / queue / failure: `{0}` / `{1}` / `{2}`' -f $summary.backlog.overallPressure, (Format-Scalar -Value $summary.backlog.totalQueueDepth), (Format-Scalar -Value $summary.backlog.failedEntries)),
    ('- Admin Summary avg ms: idle `{0}`, backlog `{1}`' -f (Format-Scalar -Value $summary.idle.adminSummaryAvgMs), (Format-Scalar -Value $summary.backlog.adminSummaryAvgMs))
)

$turkishTrendLines = @(
    "# Kontrol Duzlemi Benchmark Trend",
    ""
)
if ($null -eq $previousSummary) {
    $turkishTrendLines += "Karsilastirma icin onceki gate ozeti bulunamadi."
} else {
    $turkishTrendLines += ('Mevcut kosu, `{0}` zamaninda uretilen onceki gate ozeti ile karsilastirildi.' -f $previousSummary.generatedAtUtc)
    $turkishTrendLines += ""
    $turkishTrendLines += "| Metrik | Onceki | Guncel | Fark |"
    $turkishTrendLines += "| --- | --- | --- | --- |"
    $turkishTrendLines += ("| Profil | {0} | {1} | - |" -f (Format-Scalar -Value $previousSummary.profile.selected), (Format-Scalar -Value $summary.profile.selected))
    $turkishTrendLines += ("| Idle Admin Summary avg ms | {0} | {1} | {2} |" -f (Format-Scalar -Value $previousSummary.idle.adminSummaryAvgMs), (Format-Scalar -Value $summary.idle.adminSummaryAvgMs), (Format-Delta -CurrentValue $summary.idle.adminSummaryAvgMs -PreviousValue $previousSummary.idle.adminSummaryAvgMs -Suffix " ms"))
    $turkishTrendLines += ("| Backlog Admin Summary avg ms | {0} | {1} | {2} |" -f (Format-Scalar -Value $previousSummary.backlog.adminSummaryAvgMs), (Format-Scalar -Value $summary.backlog.adminSummaryAvgMs), (Format-Delta -CurrentValue $summary.backlog.adminSummaryAvgMs -PreviousValue $previousSummary.backlog.adminSummaryAvgMs -Suffix " ms"))
    $turkishTrendLines += ("| Backlog queue depth | {0} | {1} | {2} |" -f (Format-Scalar -Value $previousSummary.backlog.totalQueueDepth), (Format-Scalar -Value $summary.backlog.totalQueueDepth), (Format-Delta -CurrentValue $summary.backlog.totalQueueDepth -PreviousValue $previousSummary.backlog.totalQueueDepth))
    $turkishTrendLines += ("| Backlog failed entries | {0} | {1} | {2} |" -f (Format-Scalar -Value $previousSummary.backlog.failedEntries), (Format-Scalar -Value $summary.backlog.failedEntries), (Format-Delta -CurrentValue $summary.backlog.failedEntries -PreviousValue $previousSummary.backlog.failedEntries))
    $turkishTrendLines += ("| Backlog HIGH attention items | {0} | {1} | {2} |" -f (Format-Scalar -Value $previousSummary.backlog.highAttentionItems), (Format-Scalar -Value $summary.backlog.highAttentionItems), (Format-Delta -CurrentValue $summary.backlog.highAttentionItems -PreviousValue $previousSummary.backlog.highAttentionItems))
}

$turkishSummaryMarkdown = Join-MarkdownLines -Lines $turkishSummaryLines
$turkishReleaseNoteMarkdown = Join-MarkdownLines -Lines $turkishReleaseNoteLines
$turkishTrendMarkdown = Join-MarkdownLines -Lines $turkishTrendLines

$summaryMarkdown | Set-Content -Path $MarkdownOutputPath
$releaseNoteMarkdown | Set-Content -Path $ReleaseNoteOutputPath
$trendMarkdown | Set-Content -Path $TrendOutputPath
$turkishSummaryMarkdown | Set-Content -Path $turkishSummaryPath
$turkishReleaseNoteMarkdown | Set-Content -Path $turkishReleaseNotePath
$turkishTrendMarkdown | Set-Content -Path $turkishTrendPath

$stepSummaryPath = Get-EnvironmentValue -Name "GITHUB_STEP_SUMMARY"
if (-not [string]::IsNullOrWhiteSpace($stepSummaryPath)) {
    Add-Content -Path $stepSummaryPath -Value $summaryMarkdown
    Add-Content -Path $stepSummaryPath -Value [Environment]::NewLine
    Add-Content -Path $stepSummaryPath -Value $trendMarkdown
}

Write-Host ("Rendered gate summary markdown: {0}" -f $MarkdownOutputPath)
Write-Host ("Rendered release note appendix: {0}" -f $ReleaseNoteOutputPath)
Write-Host ("Rendered trend report: {0}" -f $TrendOutputPath)
Write-Host ("Rendered Turkish gate summary markdown: {0}" -f $turkishSummaryPath)
Write-Host ("Rendered Turkish release note appendix: {0}" -f $turkishReleaseNotePath)
Write-Host ("Rendered Turkish trend report: {0}" -f $turkishTrendPath)
