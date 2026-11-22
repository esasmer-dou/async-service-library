param(
    [string]$BaselineRef = $env:ASL_API_BASELINE_REF,
    [string[]]$Modules = @("asl-annotations", "asl-core", "asl-mapdb"),
    [switch]$SkipCurrentBuild,
    [string]$OutputDirectory = "target/api-compatibility"
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "java-tooling.ps1")

$root = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$outputRoot = [System.IO.Path]::GetFullPath((Join-Path $root $OutputDirectory))
$baselineRoot = [System.IO.Path]::GetFullPath((Join-Path $outputRoot "baseline-worktree"))
$comparisonPom = Join-Path $root "config/api-compatibility/japicmp-pom.xml"
$summaryFile = Join-Path $outputRoot "summary.json"

function Assert-ChildPath {
    param([Parameter(Mandatory = $true)][string]$Candidate)
    $prefix = $root.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
    if (-not $Candidate.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing filesystem operation outside repository root: $Candidate"
    }
}

function Invoke-Build {
    param(
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$ModuleList
    )
    Push-Location $WorkingDirectory
    try {
        & $javaTooling.MavenCommand -B -ntp -pl $ModuleList -am -DskipTests package
        if ($LASTEXITCODE -ne 0) {
            throw "Maven package failed under $WorkingDirectory"
        }
    } finally {
        Pop-Location
    }
}

function Find-MainJar {
    param(
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$Module
    )
    $target = Join-Path $WorkingDirectory "$Module/target"
    $candidates = @(Get-ChildItem -LiteralPath $target -Filter "$Module-*.jar" -File |
        Where-Object { $_.Name -notmatch '-(sources|javadoc|tests|benchmarks)\.jar$' })
    if ($candidates.Count -ne 1) {
        throw "Expected one primary JAR for $Module under $target, found $($candidates.Count)"
    }
    return $candidates[0].FullName
}

Assert-ChildPath -Candidate $outputRoot
Assert-ChildPath -Candidate $baselineRoot
if (-not (Test-Path -LiteralPath $comparisonPom)) {
    throw "japicmp configuration does not exist: $comparisonPom"
}
if ($Modules.Count -eq 0) {
    throw "At least one module must be supplied"
}

New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
if ([string]::IsNullOrWhiteSpace($BaselineRef)) {
    $BaselineRef = (& git -C $root tag --merged HEAD --sort=-version:refname | Select-Object -First 1)
}
if ([string]::IsNullOrWhiteSpace($BaselineRef)) {
    [ordered]@{
        status = "SKIPPED"
        reason = "No reachable release tag was found"
        generatedAtUtc = [DateTimeOffset]::UtcNow.ToString("O")
    } | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 -LiteralPath $summaryFile
    Write-Host "API compatibility gate skipped because no release tag was found."
    exit 0
}

$javaTooling = Initialize-AslJavaTooling
$moduleList = $Modules -join ','
$worktreeAdded = $false
$results = @()
$failed = $false
try {
    & git -C $root worktree prune
    if (Test-Path -LiteralPath $baselineRoot) {
        throw "Baseline worktree path already exists: $baselineRoot"
    }
    & git -C $root worktree add --detach $baselineRoot $BaselineRef
    if ($LASTEXITCODE -ne 0) {
        throw "Could not create baseline worktree for $BaselineRef"
    }
    $worktreeAdded = $true

    Invoke-Build -WorkingDirectory $baselineRoot -ModuleList $moduleList
    if (-not $SkipCurrentBuild) {
        Invoke-Build -WorkingDirectory $root -ModuleList $moduleList
    }

    foreach ($module in $Modules) {
        $oldJar = Find-MainJar -WorkingDirectory $baselineRoot -Module $module
        $newJar = Find-MainJar -WorkingDirectory $root -Module $module
        $reportDirectory = Join-Path $outputRoot "reports/$module"
        New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null

        & $javaTooling.MavenCommand -B -ntp -f $comparisonPom `
            "-Dold.jar=$oldJar" `
            "-Dnew.jar=$newJar" `
            "-Dreport.dir=$reportDirectory" `
            japicmp:cmp
        $modulePassed = $LASTEXITCODE -eq 0
        if (-not $modulePassed) {
            $failed = $true
        }
        $results += [ordered]@{
            module = $module
            status = $modulePassed ? "PASS" : "FAIL"
            oldJar = [System.IO.Path]::GetFileName($oldJar)
            newJar = [System.IO.Path]::GetFileName($newJar)
            reportDirectory = $reportDirectory
        }
    }

    [ordered]@{
        status = $failed ? "FAIL" : "PASS"
        baselineRef = $BaselineRef
        currentCommit = (& git -C $root rev-parse HEAD).Trim()
        generatedAtUtc = [DateTimeOffset]::UtcNow.ToString("O")
        modules = $results
    } | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 -LiteralPath $summaryFile
} finally {
    if ($worktreeAdded) {
        & git -C $root worktree remove --force $baselineRoot
        & git -C $root worktree prune
    }
}

if ($failed) {
    throw "API compatibility gate failed. See $summaryFile"
}
Write-Host "API compatibility gate passed against $BaselineRef. Summary: $summaryFile"
