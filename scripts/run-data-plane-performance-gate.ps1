param(
    [int[]]$Threads = @(1, 8, 32, 128),
    [int]$Warmup = 2,
    [int]$Iterations = 3,
    [int]$Forks = 1,
    [string]$ThresholdFile = "config/performance/data-plane-thresholds.json",
    [string]$OutputDirectory = "asl-benchmarks/target/performance-gate"
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "java-tooling.ps1")
$javaTooling = Initialize-AslJavaTooling

$root = Split-Path -Parent $PSScriptRoot
$thresholdPath = Join-Path $root $ThresholdFile
$outputPath = Join-Path $root $OutputDirectory
$benchmarkTarget = Join-Path $root "asl-benchmarks/target"

function Get-SecondaryMetricScore {
    param(
        [Parameter(Mandatory = $true)]$Result,
        [Parameter(Mandatory = $true)][string]$Name
    )

    $property = $Result.secondaryMetrics.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $null
    }
    return [double]$property.Value.score
}

function Assert-AtMost {
    param(
        [Parameter(Mandatory = $true)][double]$Actual,
        [Parameter(Mandatory = $true)][double]$Limit,
        [Parameter(Mandatory = $true)][string]$Description
    )

    if ($Actual -gt $Limit) {
        throw "$Description is $Actual; limit is $Limit"
    }
}

if (-not (Test-Path -LiteralPath $thresholdPath)) {
    throw "Threshold file does not exist: $thresholdPath"
}
if ($Warmup -lt 1 -or $Iterations -lt 1 -or $Forks -lt 1) {
    throw "Warmup, iterations and forks must be positive"
}

New-Item -ItemType Directory -Force -Path $outputPath | Out-Null
Push-Location $root
try {
    & $javaTooling.MavenCommand -B -ntp -pl asl-benchmarks -am -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        throw "Could not build the JMH benchmark artifact"
    }

    $jars = @(Get-ChildItem -LiteralPath $benchmarkTarget -Filter "*-benchmarks.jar" -File |
        Where-Object { $_.Name -notlike "original-*" })
    if ($jars.Count -ne 1) {
        throw "Expected exactly one benchmark artifact under $benchmarkTarget, found $($jars.Count)"
    }
    $jarPath = $jars[0].FullName
    $thresholds = Get-Content -Raw -LiteralPath $thresholdPath | ConvertFrom-Json
    $summaryRows = @()
    $allocationMetric = "gc.alloc.rate.norm"

    foreach ($threadCount in $Threads) {
        if ($threadCount -le 0) {
            throw "Thread counts must be positive"
        }

        $resultFile = Join-Path $outputPath "jmh-$threadCount-threads.json"
        & $javaTooling.JavaCommand -jar $jarPath `
            'com.reactor.asl.benchmarks.GovernanceDataPlaneBenchmark.*' `
            -wi $Warmup -i $Iterations -f $Forks -t $threadCount -prof gc -rf json -rff $resultFile
        if ($LASTEXITCODE -ne 0) {
            throw "JMH failed for $threadCount threads"
        }

        $results = Get-Content -Raw -LiteralPath $resultFile | ConvertFrom-Json
        $byName = @{}
        foreach ($result in $results) {
            $name = ($result.benchmark -split '\.')[-1]
            $byName[$name] = $result
        }

        $requiredNames = @("direct") + @($thresholds.successPaths.PSObject.Properties.Name) + @($thresholds.diagnosticPaths.PSObject.Properties.Name)
        foreach ($required in $requiredNames) {
            if (-not $byName.ContainsKey($required)) {
                throw "Missing JMH result '$required' for $threadCount threads"
            }
        }

        $directNanos = [double]$byName.direct.primaryMetric.score
        if ($directNanos -le 0) {
            throw "Invalid direct baseline for $threadCount threads: $directNanos"
        }

        $allocationSupported = $null -ne (Get-SecondaryMetricScore -Result $byName.direct -Name $allocationMetric)
        if ([bool]$thresholds.requireAllocationMetric -and -not $allocationSupported) {
            throw "JVM did not expose $allocationMetric for $threadCount threads"
        }

        $pathRows = [ordered]@{}
        foreach ($property in $thresholds.successPaths.PSObject.Properties) {
            $name = $property.Name
            $result = $byName[$name]
            $scoreNanos = [double]$result.primaryMetric.score
            $additionalNanos = $scoreNanos - $directNanos
            Assert-AtMost -Actual $additionalNanos -Limit ([double]$property.Value.maxAdditionalNanos) `
                -Description "$name additional latency with $threadCount threads"

            $allocation = Get-SecondaryMetricScore -Result $result -Name $allocationMetric
            if ($null -ne $allocation) {
                Assert-AtMost -Actual $allocation -Limit ([double]$thresholds.maxAllocationBytesPerOperation) `
                    -Description "$name allocation in B/op with $threadCount threads"
            }

            $pathRows[$name] = [ordered]@{
                scoreNanos = $scoreNanos
                additionalNanos = $additionalNanos
                allocationBytesPerOperation = $allocation
            }
        }

        foreach ($property in $thresholds.diagnosticPaths.PSObject.Properties) {
            $name = $property.Name
            $result = $byName[$name]
            $scoreNanos = [double]$result.primaryMetric.score
            Assert-AtMost -Actual $scoreNanos -Limit ([double]$property.Value.maxNanos) `
                -Description "$name latency with $threadCount threads"

            $allocation = Get-SecondaryMetricScore -Result $result -Name $allocationMetric
            if ($null -ne $allocation) {
                Assert-AtMost -Actual $allocation -Limit ([double]$property.Value.maxAllocationBytesPerOperation) `
                    -Description "$name allocation in B/op with $threadCount threads"
            }

            $pathRows[$name] = [ordered]@{
                scoreNanos = $scoreNanos
                additionalNanos = $scoreNanos - $directNanos
                allocationBytesPerOperation = $allocation
            }
        }

        $summaryRows += [ordered]@{
            threads = $threadCount
            directNanos = $directNanos
            allocationMetricAvailable = $allocationSupported
            paths = $pathRows
        }
    }

    $firstResult = (Get-Content -Raw -LiteralPath (Join-Path $outputPath "jmh-$($Threads[0])-threads.json") | ConvertFrom-Json)[0]
    $summary = [ordered]@{
        generatedAtUtc = [DateTimeOffset]::UtcNow.ToString("O")
        status = "PASS"
        benchmarkArtifact = $jars[0].Name
        thresholds = (Resolve-Path -LiteralPath $thresholdPath).Path
        environment = [ordered]@{
            os = [System.Runtime.InteropServices.RuntimeInformation]::OSDescription
            architecture = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
            jdkVersion = $firstResult.jdkVersion
            vmName = $firstResult.vmName
            vmVersion = $firstResult.vmVersion
        }
        settings = [ordered]@{
            threads = $Threads
            warmupIterations = $Warmup
            measurementIterations = $Iterations
            forks = $Forks
        }
        results = $summaryRows
    }

    $summaryFile = Join-Path $outputPath "summary.json"
    $summary | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 -LiteralPath $summaryFile
    Write-Host "Data-plane performance gate passed. Summary: $summaryFile"
} finally {
    Pop-Location
}
