param(
    [string]$BaseUrl = "http://localhost:18080",
    [int]$Warmup = 3,
    [int]$Iterations = 12,
    [string]$OutputDirectory = "",
    [int]$ReadyTimeoutSeconds = 120,
    [switch]$StartSample
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$mailServiceId = "mail.service"
$mailMethodId = "publishAudit(java.lang.String)"
$customerServiceId = "customer.service"
$customerMethodId = "publishWelcome(java.lang.String)"

function Invoke-JsonPost {
    param(
        [string]$Uri,
        [object]$Body
    )

    return Invoke-RestMethod -Uri $Uri -Method Post -ContentType "application/json" -Body ($Body | ConvertTo-Json -Depth 8)
}

function Invoke-OptionalJsonPost {
    param(
        [string]$Uri,
        [object]$Body = $null
    )

    if ($null -eq $Body) {
        return Invoke-RestMethod -Uri $Uri -Method Post
    }

    return Invoke-JsonPost -Uri $Uri -Body $Body
}

function Wait-ForEndpoint {
    param(
        [string]$Uri,
        [int]$TimeoutSeconds = 120
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-RestMethod -Uri $Uri -Method Get
            if ($null -ne $response) {
                return
            }
        } catch {
        }
        Start-Sleep -Seconds 2
    }

    throw "Endpoint did not become ready within $TimeoutSeconds seconds: $Uri"
}

function Wait-ForCondition {
    param(
        [scriptblock]$Condition,
        [string]$FailureMessage,
        [int]$TimeoutSeconds = 30
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (& $Condition) {
            return
        }
        Start-Sleep -Milliseconds 300
    }

    throw $FailureMessage
}

function Run-Benchmark {
    param(
        [string]$ReportPath,
        [string]$TargetBaseUrl,
        [int]$WarmupCount,
        [int]$IterationCount
    )

    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "run-control-plane-benchmark.ps1") `
        -BaseUrl $TargetBaseUrl `
        -Warmup $WarmupCount `
        -Iterations $IterationCount `
        -OutputPath $ReportPath
    if ($LASTEXITCODE -ne 0) {
        throw "Benchmark generation failed for $ReportPath"
    }
}

function Set-MethodMode {
    param(
        [string]$TargetBaseUrl,
        [string]$ServiceId,
        [string]$MethodId,
        [string]$ExecutionMode
    )

    Invoke-JsonPost -Uri "$TargetBaseUrl/asl/api/services/$([uri]::EscapeDataString($ServiceId))/methods/$([uri]::EscapeDataString($MethodId))/mode" -Body @{
        executionMode = $ExecutionMode
    } | Out-Null
}

function Set-ConsumerThreads {
    param(
        [string]$TargetBaseUrl,
        [string]$ServiceId,
        [string]$MethodId,
        [int]$ConsumerThreads
    )

    Invoke-JsonPost -Uri "$TargetBaseUrl/asl/api/services/$([uri]::EscapeDataString($ServiceId))/methods/$([uri]::EscapeDataString($MethodId))/consumer-threads" -Body @{
        consumerThreads = $ConsumerThreads
    } | Out-Null
}

function Enable-Method {
    param(
        [string]$TargetBaseUrl,
        [string]$ServiceId,
        [string]$MethodId
    )

    Invoke-OptionalJsonPost -Uri "$TargetBaseUrl/asl/api/services/$([uri]::EscapeDataString($ServiceId))/methods/$([uri]::EscapeDataString($MethodId))/enable" | Out-Null
}

function Clear-MethodBuffer {
    param(
        [string]$TargetBaseUrl,
        [string]$ServiceId,
        [string]$MethodId
    )

    Invoke-RestMethod -Uri "$TargetBaseUrl/asl/api/services/$([uri]::EscapeDataString($ServiceId))/methods/$([uri]::EscapeDataString($MethodId))/buffer" -Method Delete | Out-Null
}

function Reset-BenchmarkBaseline {
    param(
        [string]$TargetBaseUrl
    )

    $resetResponse = Invoke-OptionalJsonPost -Uri "$TargetBaseUrl/api/test/scenarios/reset"
    if ($resetResponse.status -ne "reset") {
        throw "Scenario reset did not report success."
    }

    $asyncLanes = @(
        @{ ServiceId = $mailServiceId; MethodId = $mailMethodId },
        @{ ServiceId = $customerServiceId; MethodId = $customerMethodId },
        @{ ServiceId = "inventory.service"; MethodId = "publishSnapshot(java.lang.String)" },
        @{ ServiceId = "chaos.service"; MethodId = "emit(java.lang.String)" }
    )

    foreach ($lane in $asyncLanes) {
        Enable-Method -TargetBaseUrl $TargetBaseUrl -ServiceId $lane.ServiceId -MethodId $lane.MethodId
        Set-MethodMode -TargetBaseUrl $TargetBaseUrl -ServiceId $lane.ServiceId -MethodId $lane.MethodId -ExecutionMode "SYNC"
        Set-ConsumerThreads -TargetBaseUrl $TargetBaseUrl -ServiceId $lane.ServiceId -MethodId $lane.MethodId -ConsumerThreads 0
        Clear-MethodBuffer -TargetBaseUrl $TargetBaseUrl -ServiceId $lane.ServiceId -MethodId $lane.MethodId
    }

    Wait-ForCondition -Condition {
        $summary = Invoke-RestMethod -Uri "$TargetBaseUrl/asl/api/summary" -Method Get
        $summary.totalQueueDepth -eq 0 -and $summary.failedEntries -eq 0 -and $summary.totalRejected -eq 0 -and $summary.overallPressure -eq "LOW"
    } -FailureMessage "Clean benchmark baseline was not restored before idle measurement." -TimeoutSeconds 45
}

$baseUri = $BaseUrl.TrimEnd("/")
$targetUri = [Uri]$baseUri
$startedProcess = $null
$sampleLogPath = ""
$sampleErrorLogPath = ""
$sampleRuntimeDirectory = Join-Path $OutputDirectory "sample-runtime"
$sampleQueuePath = Join-Path $sampleRuntimeDirectory "benchmark-queue.db"
$sampleQueuePattern = Join-Path $sampleRuntimeDirectory "benchmark-queue.db*"

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $PSScriptRoot "..\reports\real-sample"
}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
New-Item -ItemType Directory -Path $sampleRuntimeDirectory -Force | Out-Null

try {
    if ($StartSample) {
        & mvn.cmd "-pl" "asl-spring-boot-starter,asl-consumer-sample" "-am" "-DskipTests" "install" | Out-Null

        $sampleLogPath = Join-Path $OutputDirectory "sample-run.log"
        $sampleErrorLogPath = Join-Path $OutputDirectory "sample-run.err.log"
        Remove-Item $sampleQueuePattern -Force -ErrorAction SilentlyContinue
        $startedProcess = Start-Process -FilePath "mvn.cmd" `
            -ArgumentList "-f", "asl-consumer-sample/pom.xml", "-am", ("-Dspring-boot.run.arguments=--server.port={0} --asl.async.mapdb.path={1}" -f $targetUri.Port, ($sampleQueuePath -replace "\\", "/")), "spring-boot:run" `
            -WorkingDirectory (Join-Path $PSScriptRoot "..") `
            -RedirectStandardOutput $sampleLogPath `
            -RedirectStandardError $sampleErrorLogPath `
            -PassThru
    }

    Wait-ForEndpoint -Uri "$baseUri/api/health" -TimeoutSeconds $ReadyTimeoutSeconds
    Reset-BenchmarkBaseline -TargetBaseUrl $baseUri

    $idleReport = Join-Path $OutputDirectory "control-plane-benchmark-sample-idle.md"
    $backlogReport = Join-Path $OutputDirectory "control-plane-benchmark-sample-backlog.md"
    $idleSummaryPath = Join-Path $OutputDirectory "summary-idle.json"
    $backlogSummaryPath = Join-Path $OutputDirectory "summary-backlog.json"

    Run-Benchmark -ReportPath $idleReport -TargetBaseUrl $baseUri -WarmupCount $Warmup -IterationCount $Iterations
    Invoke-RestMethod -Uri "$baseUri/asl/api/summary" -Method Get | ConvertTo-Json -Depth 8 | Set-Content -Path $idleSummaryPath -Encoding UTF8

    Invoke-JsonPost -Uri "$baseUri/api/test/scenarios/mail/audit" -Body @{
        failuresRemaining = 1
        processingDelayMillis = 0
    } | Out-Null

    $mail = Invoke-JsonPost -Uri "$baseUri/api/mails" -Body @{
        recipient = "benchmark@company.com"
        subject = "benchmark"
        body = "suite"
    }

    Set-MethodMode -TargetBaseUrl $baseUri -ServiceId $mailServiceId -MethodId $mailMethodId -ExecutionMode "ASYNC"
    Set-ConsumerThreads -TargetBaseUrl $baseUri -ServiceId $mailServiceId -MethodId $mailMethodId -ConsumerThreads 1
    Invoke-RestMethod -Uri "$baseUri/api/mails/$($mail.id)/publish-audit" -Method Post | Out-Null

    $customer = Invoke-JsonPost -Uri "$baseUri/api/customers" -Body @{
        email = "benchmark-customer@company.com"
        fullName = "Benchmark Customer"
    }

    Set-MethodMode -TargetBaseUrl $baseUri -ServiceId $customerServiceId -MethodId $customerMethodId -ExecutionMode "ASYNC"
    Set-ConsumerThreads -TargetBaseUrl $baseUri -ServiceId $customerServiceId -MethodId $customerMethodId -ConsumerThreads 0

    1..3 | ForEach-Object {
        Invoke-RestMethod -Uri "$baseUri/api/customers/$($customer.id)/publish-welcome" -Method Post | Out-Null
    }

    Wait-ForCondition -Condition {
        $summary = Invoke-RestMethod -Uri "$baseUri/asl/api/summary" -Method Get
        $summary.totalQueueDepth -ge 3 -and $summary.overallPressure -eq "HIGH"
    } -FailureMessage "Backlog scenario did not reach HIGH pressure with visible queue depth."

    Run-Benchmark -ReportPath $backlogReport -TargetBaseUrl $baseUri -WarmupCount $Warmup -IterationCount $Iterations
    Invoke-RestMethod -Uri "$baseUri/asl/api/summary" -Method Get | ConvertTo-Json -Depth 8 | Set-Content -Path $backlogSummaryPath -Encoding UTF8

    Write-Host ("Generated sample benchmark suite in {0}" -f $OutputDirectory)
} finally {
    if ($StartSample) {
        $listener = Get-NetTCPConnection -LocalPort $targetUri.Port -State Listen -ErrorAction SilentlyContinue
        if ($listener) {
            Stop-Process -Id $listener.OwningProcess -Force -ErrorAction SilentlyContinue
        }
        if ($null -ne $startedProcess -and -not $startedProcess.HasExited) {
            Stop-Process -Id $startedProcess.Id -Force -ErrorAction SilentlyContinue
        }
        Remove-Item $sampleQueuePattern -Force -ErrorAction SilentlyContinue
    }
}
