param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Warmup = 3,
    [int]$Iterations = 12,
    [string]$OutputPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($Warmup -lt 0) {
    throw "Warmup must be zero or positive."
}
if ($Iterations -lt 1) {
    throw "Iterations must be at least 1."
}

$baseUri = $BaseUrl.TrimEnd("/")
$targets = @(
    @{ Name = "Admin HTML"; Uri = "$baseUri/asl"; Accept = "text/html" },
    @{ Name = "Admin Summary"; Uri = "$baseUri/asl/api/summary"; Accept = "application/json" },
    @{ Name = "Admin Services"; Uri = "$baseUri/asl/api/services"; Accept = "application/json" },
    @{ Name = "Health API"; Uri = "$baseUri/api/health"; Accept = "application/json" }
)

function Invoke-TimedRequest {
    param(
        [hashtable]$Target
    )

    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $response = Invoke-WebRequest -Uri $Target.Uri -Method Get -Headers @{ Accept = $Target.Accept } -UseBasicParsing
    $stopwatch.Stop()

    [pscustomobject]@{
        Name       = $Target.Name
        Uri        = $Target.Uri
        StatusCode = [int]$response.StatusCode
        DurationMs = [math]::Round($stopwatch.Elapsed.TotalMilliseconds, 2)
        Bytes      = if ($null -ne $response.Content) { [Text.Encoding]::UTF8.GetByteCount($response.Content) } else { 0 }
    }
}

function Measure-Target {
    param(
        [hashtable]$Target,
        [int]$WarmupCount,
        [int]$IterationCount
    )

    foreach ($i in 1..$WarmupCount) {
        [void](Invoke-TimedRequest -Target $Target)
    }

    $samples = foreach ($i in 1..$IterationCount) {
        Invoke-TimedRequest -Target $Target
    }

    $orderedDurations = @($samples | ForEach-Object { $_.DurationMs } | Sort-Object)
    $sampleBytes = @($samples | ForEach-Object { $_.Bytes })
    $p95Index = [Math]::Ceiling($orderedDurations.Count * 0.95) - 1
    if ($p95Index -lt 0) {
        $p95Index = 0
    }

    [pscustomobject]@{
        Name         = $Target.Name
        Uri          = $Target.Uri
        StatusCode   = ($samples | Select-Object -First 1).StatusCode
        MinMs        = [math]::Round(($orderedDurations | Select-Object -First 1), 2)
        AvgMs        = [math]::Round((($orderedDurations | Measure-Object -Average).Average), 2)
        MaxMs        = [math]::Round(($orderedDurations | Select-Object -Last 1), 2)
        P95Ms        = [math]::Round($orderedDurations[$p95Index], 2)
        AvgBytes     = [math]::Round((($sampleBytes | Measure-Object -Average).Average), 0)
        Samples      = $samples
    }
}

$summarySnapshot = Invoke-RestMethod -Uri "$baseUri/asl/api/summary" -Method Get
$results = foreach ($target in $targets) {
    Measure-Target -Target $target -WarmupCount $Warmup -IterationCount $Iterations
}

$timestamp = Get-Date
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $reportsDir = Join-Path $PSScriptRoot "..\reports"
    New-Item -ItemType Directory -Path $reportsDir -Force | Out-Null
    $OutputPath = Join-Path $reportsDir ("control-plane-benchmark-{0}.md" -f $timestamp.ToString("yyyyMMdd-HHmmss"))
} else {
    $outputDirectory = Split-Path -Parent $OutputPath
    if (-not [string]::IsNullOrWhiteSpace($outputDirectory)) {
        New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    }
}

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("# Control Plane Benchmark Report")
$lines.Add("")
$lines.Add("Generated: " + $timestamp.ToString("yyyy-MM-dd HH:mm:ss zzz"))
$lines.Add("")
$lines.Add(('Base URL: `{0}`' -f $baseUri))
$lines.Add("")
$lines.Add("Warmup iterations: " + $Warmup)
$lines.Add("")
$lines.Add("Measured iterations: " + $Iterations)
$lines.Add("")
$lines.Add("## Runtime Snapshot")
$lines.Add("")
$lines.Add("| Field | Value |")
$lines.Add("| --- | --- |")
$lines.Add("| Service count | " + $summarySnapshot.serviceCount + " |")
$lines.Add("| Method count | " + $summarySnapshot.methodCount + " |")
$lines.Add("| Running methods | " + $summarySnapshot.runningMethodCount + " |")
$lines.Add("| Stopped methods | " + $summarySnapshot.stoppedMethodCount + " |")
$lines.Add("| Async active | " + $summarySnapshot.asyncActiveMethodCount + " |")
$lines.Add("| Queue depth | " + $summarySnapshot.totalQueueDepth + " |")
$lines.Add("| Failed entries | " + $summarySnapshot.failedEntries + " |")
$lines.Add("| Overall pressure | " + $summarySnapshot.overallPressure + " |")
$lines.Add("")
$lines.Add("## Endpoint Latency")
$lines.Add("")
$lines.Add("| Target | Status | Min ms | Avg ms | P95 ms | Max ms | Avg bytes |")
$lines.Add("| --- | --- | --- | --- | --- | --- | --- |")
foreach ($result in $results) {
    $lines.Add(("| {0} | {1} | {2} | {3} | {4} | {5} | {6} |" -f $result.Name, $result.StatusCode, $result.MinMs, $result.AvgMs, $result.P95Ms, $result.MaxMs, $result.AvgBytes))
}
$lines.Add("")
$lines.Add("## Attention Items")
$lines.Add("")
if ($summarySnapshot.attentionItems.Count -eq 0) {
    $lines.Add("No current attention items were reported by the control plane.")
} else {
    foreach ($item in $summarySnapshot.attentionItems) {
        $lines.Add(("- [{0}] {1} / {2}: {3} | {4}" -f $item.severity, $item.serviceId, $item.methodName, $item.headline, $item.action))
    }
}
$lines.Add("")
$lines.Add("## Notes")
$lines.Add("")
$lines.Add("- This benchmark is a control-plane smoke benchmark, not a synthetic maximum-throughput test.")
$lines.Add("- Run it against the consumer sample or a staging deployment with representative queue state.")
$lines.Add("- Pair the report with `GET /asl/api/summary` snapshots before and after load.")

Set-Content -Path $OutputPath -Value $lines -Encoding UTF8

Write-Host ("Benchmark report written to {0}" -f $OutputPath)
