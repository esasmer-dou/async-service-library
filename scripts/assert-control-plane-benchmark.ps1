param(
    [string]$IdleSummaryPath = ".\reports\real-sample\summary-idle.json",
    [string]$BacklogSummaryPath = ".\reports\real-sample\summary-backlog.json",
    [string]$IdleReportPath = ".\reports\real-sample\control-plane-benchmark-sample-idle.md",
    [string]$BacklogReportPath = ".\reports\real-sample\control-plane-benchmark-sample-backlog.md",
    [string]$Profile = "",
    [string]$ThresholdsPath = "",
    [string[]]$AdditionalThresholdsPaths = @(),
    [string]$ProfileResolutionPath = "",
    [string]$SummaryOutputPath = "",
    [Nullable[double]]$IdleAdminSummaryAvgMaxMs = $null,
    [Nullable[double]]$BacklogAdminSummaryAvgMaxMs = $null,
    [Nullable[int]]$MinimumBacklogQueueDepth = $null,
    [Nullable[int]]$MinimumBacklogFailedEntries = $null,
    [Nullable[int]]$MinimumBacklogHighAttentionItems = $null
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

function Get-PathListFromString {
    param([string]$Value)

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return @()
    }

    return @((
        $Value -split "[;\r\n]+"
    ) | ForEach-Object { $_.Trim() } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
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

    foreach ($value in Get-PathListFromString -Value (Get-EnvironmentValue -Name $EnvironmentName)) {
        $combined.Add($value)
    }

    return @($combined | Select-Object -Unique)
}

function Read-JsonFile {
    param([string]$Path)

    if (-not (Test-Path $Path)) {
        throw "JSON file not found: $Path"
    }

    return Get-Content $Path -Raw | ConvertFrom-Json
}

function Copy-ProfilesToHashtable {
    param($ProfilesObject)

    $profiles = @{}
    if ($null -eq $ProfilesObject) {
        return $profiles
    }

    foreach ($profileProperty in $ProfilesObject.PSObject.Properties) {
        $profileValues = @{}
        if ($null -ne $profileProperty.Value) {
            foreach ($thresholdProperty in $profileProperty.Value.PSObject.Properties) {
                $profileValues[$thresholdProperty.Name] = $thresholdProperty.Value
            }
        }
        $profiles[$profileProperty.Name] = $profileValues
    }

    return $profiles
}

function Convert-HashtableToConfigurationObject {
    param([hashtable]$Profiles)

    $profileObjects = [ordered]@{}
    foreach ($profileName in ($Profiles.Keys | Sort-Object)) {
        $profileObjects[$profileName] = [pscustomobject]$Profiles[$profileName]
    }

    return [pscustomobject]@{
        profiles = [pscustomobject]$profileObjects
    }
}

function Merge-ThresholdConfiguration {
    param(
        $BaseConfiguration,
        $OverlayConfiguration
    )

    $mergedProfiles = Copy-ProfilesToHashtable -ProfilesObject $BaseConfiguration.profiles
    $overlayProfiles = Copy-ProfilesToHashtable -ProfilesObject $OverlayConfiguration.profiles

    foreach ($profileName in $overlayProfiles.Keys) {
        if (-not $mergedProfiles.ContainsKey($profileName)) {
            $mergedProfiles[$profileName] = @{}
        }

        foreach ($thresholdName in $overlayProfiles[$profileName].Keys) {
            $mergedProfiles[$profileName][$thresholdName] = $overlayProfiles[$profileName][$thresholdName]
        }
    }

    return Convert-HashtableToConfigurationObject -Profiles $mergedProfiles
}

function Resolve-ThresholdConfiguration {
    param(
        [string]$SelectedProfile,
        [string]$ConfigurationPath,
        [string[]]$OverlayPaths
    )

    if ([string]::IsNullOrWhiteSpace($ConfigurationPath)) {
        $ConfigurationPath = Get-EnvironmentValue -Name "ASL_BENCHMARK_THRESHOLDS_PATH"
    }

    if ([string]::IsNullOrWhiteSpace($ConfigurationPath)) {
        $ConfigurationPath = Join-Path $PSScriptRoot "control-plane-benchmark-thresholds.json"
    }

    $configuration = Read-JsonFile -Path $ConfigurationPath
    if ($null -eq $configuration.profiles) {
        throw "Threshold configuration must contain a top-level 'profiles' object: $ConfigurationPath"
    }

    $resolvedOverlayPaths = New-Object System.Collections.Generic.List[string]
    foreach ($overlayPath in @($OverlayPaths)) {
        if (-not [string]::IsNullOrWhiteSpace($overlayPath)) {
            $resolvedOverlayPaths.Add($overlayPath.Trim())
        }
    }

    foreach ($candidate in @(
        (Join-Path $PSScriptRoot "control-plane-benchmark-thresholds.override.json"),
        (Join-Path $PSScriptRoot ("control-plane-benchmark-thresholds.{0}.override.json" -f $SelectedProfile))
    )) {
        if (Test-Path $candidate) {
            $resolvedOverlayPaths.Add($candidate)
        }
    }

    $resolvedOverlayPaths = @($resolvedOverlayPaths | Select-Object -Unique)
    foreach ($overlayPath in $resolvedOverlayPaths) {
        $overlayConfiguration = Read-JsonFile -Path $overlayPath
        if ($null -eq $overlayConfiguration.profiles) {
            throw "Overlay threshold configuration must contain a top-level 'profiles' object: $overlayPath"
        }
        $configuration = Merge-ThresholdConfiguration -BaseConfiguration $configuration -OverlayConfiguration $overlayConfiguration
    }

    $profileConfig = $configuration.profiles.$SelectedProfile
    if ($null -eq $profileConfig) {
        $availableProfiles = @($configuration.profiles.PSObject.Properties.Name) -join ", "
        throw "Threshold profile not found: $SelectedProfile. Available profiles: $availableProfiles"
    }

    return [pscustomobject]@{
        ThresholdsPath = $ConfigurationPath
        AppliedOverlayPaths = $resolvedOverlayPaths
        Profile = $profileConfig
    }
}

function Resolve-DoubleThreshold {
    param(
        [Nullable[double]]$ExplicitValue,
        [string]$EnvironmentName,
        [object]$DefaultValue
    )

    if ($null -ne $ExplicitValue) {
        return [double]$ExplicitValue
    }

    $environmentValue = Get-EnvironmentValue -Name $EnvironmentName
    if (-not [string]::IsNullOrWhiteSpace($environmentValue)) {
        $parsedValue = 0.0
        if (-not [double]::TryParse($environmentValue, [System.Globalization.NumberStyles]::Float, [System.Globalization.CultureInfo]::InvariantCulture, [ref]$parsedValue)) {
            throw "Environment variable $EnvironmentName must be a floating-point number."
        }
        return $parsedValue
    }

    return [double]$DefaultValue
}

function Resolve-IntThreshold {
    param(
        [Nullable[int]]$ExplicitValue,
        [string]$EnvironmentName,
        [object]$DefaultValue
    )

    if ($null -ne $ExplicitValue) {
        return [int]$ExplicitValue
    }

    $environmentValue = Get-EnvironmentValue -Name $EnvironmentName
    if (-not [string]::IsNullOrWhiteSpace($environmentValue)) {
        $parsedValue = 0
        if (-not [int]::TryParse($environmentValue, [ref]$parsedValue)) {
            throw "Environment variable $EnvironmentName must be an integer."
        }
        return $parsedValue
    }

    return [int]$DefaultValue
}

function Read-TextFile {
    param([string]$Path)

    if (-not (Test-Path $Path)) {
        throw "Text file not found: $Path"
    }

    return Get-Content $Path
}

function Parse-LatencyTable {
    param([string[]]$Lines)

    $latencies = @{}
    foreach ($line in $Lines) {
        if (-not $line.StartsWith("|")) {
            continue
        }
        if ($line -match '^\|\s*Target\s*\|') {
            continue
        }
        if ($line -match '^\|\s*---') {
            continue
        }

        $columns = $line.Trim('|').Split('|') | ForEach-Object { $_.Trim() }
        if ($columns.Count -lt 7) {
            continue
        }

        $target = $columns[0]
        $avgMs = $columns[3].Replace(',', '.')
        $parsedAvg = 0.0
        if (-not [double]::TryParse($avgMs, [System.Globalization.NumberStyles]::Float, [System.Globalization.CultureInfo]::InvariantCulture, [ref]$parsedAvg)) {
            continue
        }

        $latencies[$target] = [pscustomobject]@{
            StatusCode = $columns[1]
            MinMs = $columns[2]
            AvgMs = $parsedAvg
            P95Ms = $columns[4]
            MaxMs = $columns[5]
            AvgBytes = $columns[6]
        }
    }
    return $latencies
}

function Assert-Condition {
    param(
        [bool]$Condition,
        [string]$Message,
        [System.Collections.Generic.List[string]]$Failures
    )

    if (-not $Condition) {
        $Failures.Add($Message)
    }
}

function Resolve-ProfileSelectionConfiguration {
    param([string]$ConfigurationPath)

    if ([string]::IsNullOrWhiteSpace($ConfigurationPath)) {
        $ConfigurationPath = Get-EnvironmentValue -Name "ASL_BENCHMARK_PROFILE_RESOLUTION_PATH"
    }

    if ([string]::IsNullOrWhiteSpace($ConfigurationPath)) {
        $ConfigurationPath = Join-Path $PSScriptRoot "control-plane-benchmark-profile-resolution.json"
    }

    $configuration = Read-JsonFile -Path $ConfigurationPath
    if ([string]::IsNullOrWhiteSpace($configuration.defaultProfile)) {
        throw "Profile resolution config must define 'defaultProfile': $ConfigurationPath"
    }

    return [pscustomobject]@{
        Path = $ConfigurationPath
        Config = $configuration
    }
}

function Find-MappedProfile {
    param(
        [string]$CandidateValue,
        $Mappings
    )

    if ([string]::IsNullOrWhiteSpace($CandidateValue) -or $null -eq $Mappings) {
        return $null
    }

    foreach ($mapping in $Mappings) {
        if ([string]::IsNullOrWhiteSpace($mapping.pattern) -or [string]::IsNullOrWhiteSpace($mapping.profile)) {
            continue
        }
        if ($CandidateValue -like $mapping.pattern) {
            return [pscustomobject]@{
                Profile = $mapping.profile
                Pattern = $mapping.pattern
            }
        }
    }

    return $null
}

function Resolve-ProfileSelection {
    param(
        [string]$ExplicitProfile,
        [string]$ConfigurationPath
    )

    $resolution = Resolve-ProfileSelectionConfiguration -ConfigurationPath $ConfigurationPath
    $envProfile = Get-EnvironmentValue -Name "ASL_BENCHMARK_PROFILE"
    if (-not [string]::IsNullOrWhiteSpace($ExplicitProfile)) {
        return [pscustomobject]@{
            Profile = $ExplicitProfile.Trim()
            Source = "explicit-parameter"
            BranchName = $null
            ReleaseTag = $null
            ResolutionPath = $resolution.Path
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($envProfile)) {
        return [pscustomobject]@{
            Profile = $envProfile
            Source = "environment-variable:ASL_BENCHMARK_PROFILE"
            BranchName = $null
            ReleaseTag = $null
            ResolutionPath = $resolution.Path
        }
    }

    $releaseTag = Get-EnvironmentValue -Name "ASL_BENCHMARK_RELEASE_TAG"
    if ([string]::IsNullOrWhiteSpace($releaseTag)) {
        $githubRefType = Get-EnvironmentValue -Name "GITHUB_REF_TYPE"
        $githubRefName = Get-EnvironmentValue -Name "GITHUB_REF_NAME"
        if ($githubRefType -eq "tag" -and -not [string]::IsNullOrWhiteSpace($githubRefName)) {
            $releaseTag = $githubRefName
        }
    }

    $branchName = Get-EnvironmentValue -Name "ASL_BENCHMARK_BRANCH_NAME"
    if ([string]::IsNullOrWhiteSpace($branchName)) {
        $headRef = Get-EnvironmentValue -Name "GITHUB_HEAD_REF"
        if (-not [string]::IsNullOrWhiteSpace($headRef)) {
            $branchName = $headRef
        } else {
            $githubRefType = Get-EnvironmentValue -Name "GITHUB_REF_TYPE"
            $githubRefName = Get-EnvironmentValue -Name "GITHUB_REF_NAME"
            if ($githubRefType -eq "branch" -and -not [string]::IsNullOrWhiteSpace($githubRefName)) {
                $branchName = $githubRefName
            }
        }
    }

    $tagMatch = Find-MappedProfile -CandidateValue $releaseTag -Mappings $resolution.Config.tagProfiles
    if ($null -ne $tagMatch) {
        return [pscustomobject]@{
            Profile = $tagMatch.Profile
            Source = ("tag-pattern:{0}" -f $tagMatch.Pattern)
            BranchName = $branchName
            ReleaseTag = $releaseTag
            ResolutionPath = $resolution.Path
        }
    }

    $branchMatch = Find-MappedProfile -CandidateValue $branchName -Mappings $resolution.Config.branchProfiles
    if ($null -ne $branchMatch) {
        return [pscustomobject]@{
            Profile = $branchMatch.Profile
            Source = ("branch-pattern:{0}" -f $branchMatch.Pattern)
            BranchName = $branchName
            ReleaseTag = $releaseTag
            ResolutionPath = $resolution.Path
        }
    }

    return [pscustomobject]@{
        Profile = $resolution.Config.defaultProfile
        Source = "default-profile"
        BranchName = $branchName
        ReleaseTag = $releaseTag
        ResolutionPath = $resolution.Path
    }
}

$failures = New-Object System.Collections.Generic.List[string]
$profileSelection = Resolve-ProfileSelection -ExplicitProfile $Profile -ConfigurationPath $ProfileResolutionPath
$selectedProfile = $profileSelection.Profile
$resolvedThresholdsPath = if (-not [string]::IsNullOrWhiteSpace($ThresholdsPath)) { $ThresholdsPath } else { "" }
$additionalThresholdPaths = Get-CombinedPathList -ExplicitValues $AdditionalThresholdsPaths -EnvironmentName "ASL_BENCHMARK_EXTRA_THRESHOLDS_PATHS"
$thresholdConfig = Resolve-ThresholdConfiguration -SelectedProfile $selectedProfile -ConfigurationPath $resolvedThresholdsPath -OverlayPaths $additionalThresholdPaths
$profileThresholds = $thresholdConfig.Profile
$resolvedIdleAdminSummaryAvgMaxMs = Resolve-DoubleThreshold -ExplicitValue $IdleAdminSummaryAvgMaxMs -EnvironmentName "ASL_BENCHMARK_IDLE_ADMIN_SUMMARY_AVG_MAX_MS" -DefaultValue $profileThresholds.idleAdminSummaryAvgMaxMs
$resolvedBacklogAdminSummaryAvgMaxMs = Resolve-DoubleThreshold -ExplicitValue $BacklogAdminSummaryAvgMaxMs -EnvironmentName "ASL_BENCHMARK_BACKLOG_ADMIN_SUMMARY_AVG_MAX_MS" -DefaultValue $profileThresholds.backlogAdminSummaryAvgMaxMs
$resolvedMinimumBacklogQueueDepth = Resolve-IntThreshold -ExplicitValue $MinimumBacklogQueueDepth -EnvironmentName "ASL_BENCHMARK_MINIMUM_BACKLOG_QUEUE_DEPTH" -DefaultValue $profileThresholds.minimumBacklogQueueDepth
$resolvedMinimumBacklogFailedEntries = Resolve-IntThreshold -ExplicitValue $MinimumBacklogFailedEntries -EnvironmentName "ASL_BENCHMARK_MINIMUM_BACKLOG_FAILED_ENTRIES" -DefaultValue $profileThresholds.minimumBacklogFailedEntries
$resolvedMinimumBacklogHighAttentionItems = Resolve-IntThreshold -ExplicitValue $MinimumBacklogHighAttentionItems -EnvironmentName "ASL_BENCHMARK_MINIMUM_BACKLOG_HIGH_ATTENTION_ITEMS" -DefaultValue $profileThresholds.minimumBacklogHighAttentionItems
$idleSummary = Read-JsonFile -Path $IdleSummaryPath
$backlogSummary = Read-JsonFile -Path $BacklogSummaryPath
$idleLatencies = Parse-LatencyTable -Lines (Read-TextFile -Path $IdleReportPath)
$backlogLatencies = Parse-LatencyTable -Lines (Read-TextFile -Path $BacklogReportPath)

Assert-Condition -Condition ($idleSummary.overallPressure -eq "LOW") -Message "Idle summary overallPressure must be LOW." -Failures $failures
Assert-Condition -Condition ($idleSummary.totalQueueDepth -eq 0) -Message "Idle summary totalQueueDepth must be 0." -Failures $failures
Assert-Condition -Condition ($idleSummary.failedEntries -eq 0) -Message "Idle summary failedEntries must be 0." -Failures $failures
Assert-Condition -Condition ($idleSummary.totalRejected -eq 0) -Message "Idle summary totalRejected must be 0." -Failures $failures
Assert-Condition -Condition ($backlogSummary.overallPressure -eq "HIGH") -Message "Backlog summary overallPressure must be HIGH." -Failures $failures
Assert-Condition -Condition ($backlogSummary.totalQueueDepth -ge $resolvedMinimumBacklogQueueDepth) -Message ("Backlog summary totalQueueDepth must be >= {0}." -f $resolvedMinimumBacklogQueueDepth) -Failures $failures
Assert-Condition -Condition ($backlogSummary.failedEntries -ge $resolvedMinimumBacklogFailedEntries) -Message ("Backlog summary failedEntries must be >= {0}." -f $resolvedMinimumBacklogFailedEntries) -Failures $failures
Assert-Condition -Condition ($backlogSummary.asyncActiveMethodCount -ge 1) -Message "Backlog summary asyncActiveMethodCount must be >= 1." -Failures $failures

$backlogHighAttentionCount = @($backlogSummary.attentionItems | Where-Object { $_.severity -eq "HIGH" }).Count
Assert-Condition -Condition ($backlogHighAttentionCount -ge $resolvedMinimumBacklogHighAttentionItems) -Message ("Backlog summary HIGH attention count must be >= {0}." -f $resolvedMinimumBacklogHighAttentionItems) -Failures $failures
Assert-Condition -Condition ($idleLatencies.ContainsKey("Admin Summary")) -Message "Idle report must include the Admin Summary latency row." -Failures $failures
Assert-Condition -Condition ($backlogLatencies.ContainsKey("Admin Summary")) -Message "Backlog report must include the Admin Summary latency row." -Failures $failures

if ($idleLatencies.ContainsKey("Admin Summary")) {
    Assert-Condition -Condition ($idleLatencies["Admin Summary"].AvgMs -le $resolvedIdleAdminSummaryAvgMaxMs) `
        -Message ("Idle Admin Summary avg latency {0}ms exceeds threshold {1}ms." -f $idleLatencies["Admin Summary"].AvgMs, $resolvedIdleAdminSummaryAvgMaxMs) `
        -Failures $failures
}

if ($backlogLatencies.ContainsKey("Admin Summary")) {
    Assert-Condition -Condition ($backlogLatencies["Admin Summary"].AvgMs -le $resolvedBacklogAdminSummaryAvgMaxMs) `
        -Message ("Backlog Admin Summary avg latency {0}ms exceeds threshold {1}ms." -f $backlogLatencies["Admin Summary"].AvgMs, $resolvedBacklogAdminSummaryAvgMaxMs) `
        -Failures $failures
}

$status = if ($failures.Count -gt 0) { "FAILED" } else { "PASSED" }
$resolvedSummaryOutputPath = if (-not [string]::IsNullOrWhiteSpace($SummaryOutputPath)) {
    $SummaryOutputPath
} else {
    Join-Path (Split-Path -Parent $IdleSummaryPath) "control-plane-benchmark-gate-summary.json"
}

$gateSummary = [pscustomobject]@{
    status = $status
    generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    profile = [pscustomobject]@{
        selected = $selectedProfile
        source = $profileSelection.Source
        branchName = $profileSelection.BranchName
        releaseTag = $profileSelection.ReleaseTag
        resolutionPath = $profileSelection.ResolutionPath
    }
    thresholds = [pscustomobject]@{
        thresholdsPath = $thresholdConfig.ThresholdsPath
        overlayPaths = @($thresholdConfig.AppliedOverlayPaths)
        idleAdminSummaryAvgMaxMs = $resolvedIdleAdminSummaryAvgMaxMs
        backlogAdminSummaryAvgMaxMs = $resolvedBacklogAdminSummaryAvgMaxMs
        minimumBacklogQueueDepth = $resolvedMinimumBacklogQueueDepth
        minimumBacklogFailedEntries = $resolvedMinimumBacklogFailedEntries
        minimumBacklogHighAttentionItems = $resolvedMinimumBacklogHighAttentionItems
    }
    idle = [pscustomobject]@{
        overallPressure = $idleSummary.overallPressure
        totalQueueDepth = $idleSummary.totalQueueDepth
        failedEntries = $idleSummary.failedEntries
        totalRejected = $idleSummary.totalRejected
        adminSummaryAvgMs = $(if ($idleLatencies.ContainsKey("Admin Summary")) { $idleLatencies["Admin Summary"].AvgMs } else { $null })
    }
    backlog = [pscustomobject]@{
        overallPressure = $backlogSummary.overallPressure
        totalQueueDepth = $backlogSummary.totalQueueDepth
        failedEntries = $backlogSummary.failedEntries
        asyncActiveMethodCount = $backlogSummary.asyncActiveMethodCount
        highAttentionItems = $backlogHighAttentionCount
        adminSummaryAvgMs = $(if ($backlogLatencies.ContainsKey("Admin Summary")) { $backlogLatencies["Admin Summary"].AvgMs } else { $null })
    }
    failures = @($failures)
}

$summaryDirectory = Split-Path -Parent $resolvedSummaryOutputPath
if (-not [string]::IsNullOrWhiteSpace($summaryDirectory) -and -not (Test-Path $summaryDirectory)) {
    New-Item -ItemType Directory -Path $summaryDirectory -Force | Out-Null
}
$gateSummary | ConvertTo-Json -Depth 8 | Set-Content -Path $resolvedSummaryOutputPath

Write-Host "Control-plane benchmark gate summary:"
Write-Host ("- Status: {0}" -f $status)
Write-Host ("- Profile: {0}" -f $selectedProfile)
Write-Host ("- Profile source: {0}" -f $profileSelection.Source)
Write-Host ("- Branch: {0}" -f $(if ($null -ne $profileSelection.BranchName) { $profileSelection.BranchName } else { "(none)" }))
Write-Host ("- Release tag: {0}" -f $(if ($null -ne $profileSelection.ReleaseTag) { $profileSelection.ReleaseTag } else { "(none)" }))
Write-Host ("- Profile resolution path: {0}" -f $profileSelection.ResolutionPath)
Write-Host ("- Thresholds path: {0}" -f $thresholdConfig.ThresholdsPath)
Write-Host ("- Overlay paths: {0}" -f $(if ($thresholdConfig.AppliedOverlayPaths.Count -gt 0) { $thresholdConfig.AppliedOverlayPaths -join ", " } else { "(none)" }))
Write-Host ("- Summary artifact: {0}" -f $resolvedSummaryOutputPath)
Write-Host ("- Idle pressure: {0}" -f $idleSummary.overallPressure)
Write-Host ("- Idle queue depth: {0}" -f $idleSummary.totalQueueDepth)
Write-Host ("- Idle failed entries: {0}" -f $idleSummary.failedEntries)
if ($idleLatencies.ContainsKey("Admin Summary")) {
    Write-Host ("- Idle Admin Summary avg ms: {0}" -f $idleLatencies["Admin Summary"].AvgMs)
    Write-Host ("- Idle Admin Summary threshold ms: {0}" -f $resolvedIdleAdminSummaryAvgMaxMs)
}
Write-Host ("- Backlog pressure: {0}" -f $backlogSummary.overallPressure)
Write-Host ("- Backlog queue depth: {0}" -f $backlogSummary.totalQueueDepth)
Write-Host ("- Backlog queue depth threshold: {0}" -f $resolvedMinimumBacklogQueueDepth)
Write-Host ("- Backlog failed entries: {0}" -f $backlogSummary.failedEntries)
Write-Host ("- Backlog failed entries threshold: {0}" -f $resolvedMinimumBacklogFailedEntries)
Write-Host ("- Backlog HIGH attention items: {0}" -f $backlogHighAttentionCount)
Write-Host ("- Backlog HIGH attention threshold: {0}" -f $resolvedMinimumBacklogHighAttentionItems)
if ($backlogLatencies.ContainsKey("Admin Summary")) {
    Write-Host ("- Backlog Admin Summary avg ms: {0}" -f $backlogLatencies["Admin Summary"].AvgMs)
    Write-Host ("- Backlog Admin Summary threshold ms: {0}" -f $resolvedBacklogAdminSummaryAvgMaxMs)
}

if ($failures.Count -gt 0) {
    Write-Host ""
    Write-Host "Control-plane benchmark gate failed:" -ForegroundColor Red
    foreach ($failure in $failures) {
        Write-Host ("- {0}" -f $failure) -ForegroundColor Red
    }
    exit 1
}

Write-Host ""
Write-Host "Control-plane benchmark gate passed."
