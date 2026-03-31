param(
    [switch]$BuildSample = $true,
    [int]$BasePort = 18120,
    [int]$ReadyTimeoutSeconds = 60,
    [string]$OutputRoot = ".\reports\mapdb-abuse"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$sampleModuleDir = Join-Path $repoRoot "asl-consumer-sample"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runDirectory = Join-Path $repoRoot (($OutputRoot -replace '^[.][\\/]', ''))
$runDirectory = Join-Path $runDirectory $timestamp
$trRunDirectory = Join-Path $repoRoot "tr\reports\mapdb-abuse\$timestamp"
$script:nextPort = $BasePort

$serviceId = "chaos.service"
$methodId = "emit(java.lang.String)"
$encodedMethodId = [System.Uri]::EscapeDataString($methodId)

function Ensure-Directory([string]$path) {
    if (-not (Test-Path $path)) {
        New-Item -ItemType Directory -Path $path -Force | Out-Null
    }
}

function Invoke-CheckedCommand([string]$filePath, [string[]]$arguments) {
    & $filePath @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed: $filePath $($arguments -join ' ')"
    }
}

function Build-SampleJar {
    Write-Host "Building sample jar..."
    Push-Location $repoRoot
    try {
        Invoke-CheckedCommand "mvn" @("-pl", "asl-consumer-sample", "-am", "package", "-DskipTests", "-q")
    } finally {
        Pop-Location
    }
}

function Get-SampleJarPath {
    $jar = Get-ChildItem (Join-Path $sampleModuleDir "target") -Filter "*.jar" |
        Where-Object { $_.Name -notlike "original-*" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $jar) {
        throw "Sample jar could not be found under asl-consumer-sample\\target"
    }
    return $jar.FullName
}

function Get-NextPort {
    $port = $script:nextPort
    $script:nextPort++
    return $port
}

function Wait-Until([scriptblock]$condition, [int]$timeoutSeconds, [string]$message) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (& $condition) {
            return
        }
        Start-Sleep -Milliseconds 250
    }
    throw $message
}

function Invoke-JsonRequest([string]$method, [string]$url, $body = $null) {
    if ($null -eq $body) {
        return Invoke-RestMethod -Method $method -Uri $url -TimeoutSec 30
    }
    $json = $body | ConvertTo-Json -Depth 10
    return Invoke-RestMethod -Method $method -Uri $url -TimeoutSec 30 -ContentType "application/json" -Body $json
}

function Get-PropertyValue($object, [string]$propertyName, $defaultValue) {
    if ($null -eq $object) {
        return $defaultValue
    }
    $property = $object.PSObject.Properties[$propertyName]
    if ($null -eq $property) {
        return $defaultValue
    }
    return $property.Value
}

function Get-StorageRecovery($summary) {
    return Get-PropertyValue $summary "storageRecovery" $null
}

function Test-StorageRecoveryVisible($summary) {
    $storageRecovery = Get-StorageRecovery $summary
    if ($null -eq $storageRecovery) {
        return $false
    }
    $headline = [string](Get-PropertyValue $storageRecovery "headline" "")
    return -not [string]::IsNullOrWhiteSpace($headline)
}

function Start-Sample([string]$jarPath, [string]$dbPath, [string]$scenarioName) {
    $port = Get-NextPort
    $logPrefix = Join-Path $runDirectory $scenarioName
    $stdoutPath = "$logPrefix.out.log"
    $stderrPath = "$logPrefix.err.log"
    $arguments = @(
        "-jar",
        $jarPath,
        "--server.port=$port",
        "--asl.async.mapdb.enabled=true",
        "--asl.async.mapdb.path=$($dbPath -replace '\\','/')"
    )
    $process = Start-Process -FilePath "java" -ArgumentList $arguments -WorkingDirectory $repoRoot -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -PassThru
    $baseUrl = "http://localhost:$port"
    try {
        Wait-Until {
            try {
                $health = Invoke-RestMethod -Method Get -Uri "$baseUrl/api/health" -TimeoutSec 5
                return $health.status -eq "UP"
            } catch {
                return $false
            }
        } $ReadyTimeoutSeconds "Sample did not become healthy for scenario '$scenarioName'"
    } catch {
        if (-not $process.HasExited) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        }
        throw
    }
    return [pscustomobject]@{
        Scenario = $scenarioName
        Port = $port
        BaseUrl = $baseUrl
        Process = $process
        StdOut = $stdoutPath
        StdErr = $stderrPath
        DbPath = $dbPath
    }
}

function Stop-Sample($handle) {
    if ($null -ne $handle -and $null -ne $handle.Process -and -not $handle.Process.HasExited) {
        Stop-Process -Id $handle.Process.Id -Force -ErrorAction SilentlyContinue
        Wait-Process -Id $handle.Process.Id -Timeout 15 -ErrorAction SilentlyContinue
    }
}

function Get-AdminSummary($handle) {
    return Invoke-RestMethod -Method Get -Uri "$($handle.BaseUrl)/asl/api/summary" -TimeoutSec 30
}

function Get-Buffer($handle) {
    return Invoke-RestMethod -Method Get -Uri "$($handle.BaseUrl)/asl/api/services/$serviceId/methods/$encodedMethodId/buffer" -TimeoutSec 30
}

function Configure-Chaos($handle, [int]$failuresRemaining, [int]$processingDelayMillis) {
    Invoke-JsonRequest "Post" "$($handle.BaseUrl)/api/test/scenarios/chaos/emit" @{
        failuresRemaining = $failuresRemaining
        processingDelayMillis = $processingDelayMillis
    } | Out-Null
}

function Reset-State($handle) {
    Invoke-RestMethod -Method Post -Uri "$($handle.BaseUrl)/api/test/scenarios/reset" -TimeoutSec 30 | Out-Null
}

function Switch-AsyncMode($handle) {
    Invoke-JsonRequest "Post" "$($handle.BaseUrl)/asl/api/services/$serviceId/methods/$encodedMethodId/mode" @{
        executionMode = "ASYNC"
    } | Out-Null
}

function Resize-Consumers($handle, [int]$consumerThreads) {
    Invoke-JsonRequest "Post" "$($handle.BaseUrl)/asl/api/services/$serviceId/methods/$encodedMethodId/consumer-threads" @{
        consumerThreads = $consumerThreads
    } | Out-Null
}

function Emit-Chaos($handle, [string]$payload) {
    Invoke-RestMethod -Method Post -Uri "$($handle.BaseUrl)/api/chaos/emit/$payload" -TimeoutSec 30 | Out-Null
}

function Get-ChaosEvents($handle) {
    return Invoke-RestMethod -Method Get -Uri "$($handle.BaseUrl)/api/chaos/events" -TimeoutSec 30
}

function Replay-Entry($handle, [string]$entryId) {
    Invoke-RestMethod -Method Post -Uri "$($handle.BaseUrl)/asl/api/services/$serviceId/methods/$encodedMethodId/buffer/$entryId/replay" -TimeoutSec 30 | Out-Null
}

function Wait-ForPending($handle, [int]$expectedCount, [int]$timeoutSeconds) {
    Wait-Until { (Get-Buffer $handle).pendingCount -eq $expectedCount } $timeoutSeconds "Pending count did not reach $expectedCount"
}

function Wait-ForFailedAtLeast($handle, [int]$expectedCount, [int]$timeoutSeconds) {
    Wait-Until { (Get-Buffer $handle).failedCount -ge $expectedCount } $timeoutSeconds "Failed count did not reach $expectedCount"
}

function Wait-ForInProgressAtLeast($handle, [int]$expectedCount, [int]$timeoutSeconds) {
    Wait-Until { (Get-Buffer $handle).inProgressCount -ge $expectedCount } $timeoutSeconds "In-progress count did not reach $expectedCount"
}

function Wait-ForBufferDrain($handle, [int]$timeoutSeconds) {
    Wait-Until {
        $buffer = Get-Buffer $handle
        ($buffer.pendingCount + $buffer.failedCount + $buffer.inProgressCount) -eq 0
    } $timeoutSeconds "Buffer did not drain to zero"
}

function Wait-ForEvent($handle, [string]$eventText, [int]$timeoutSeconds) {
    Wait-Until { (Get-ChaosEvents $handle) -contains $eventText } $timeoutSeconds "Event '$eventText' was not observed"
}

function New-Result([string]$name) {
    return [ordered]@{
        scenario = $name
        status = "PASS"
        notes = @()
        checks = @()
        logs = @()
    }
}

function Add-Check($result, [string]$name, $value) {
    $result.checks += [ordered]@{ name = $name; value = $value }
}

function Add-Note($result, [string]$value) {
    $result.notes += $value
}

function Add-Logs($result, $handle) {
    if ($null -ne $handle) {
        $result.logs += $handle.StdOut
        $result.logs += $handle.StdErr
    }
}

function Invoke-PendingSurvivesForceKill($jarPath) {
    $result = New-Result "pending-survives-force-kill"
    $dbPath = Join-Path $runDirectory "pending-survives-force-kill.db"
    $payload = "kill-pending"
    $handle = $null
    try {
        $handle = Start-Sample $jarPath $dbPath "pending-survives-force-kill-start-1"
        Add-Logs $result $handle
        Reset-State $handle
        Switch-AsyncMode $handle
        Resize-Consumers $handle 0
        Emit-Chaos $handle $payload
        Wait-ForPending $handle 1 10
        Add-Check $result "pendingBeforeKill" 1
        Stop-Sample $handle

        $handle = Start-Sample $jarPath $dbPath "pending-survives-force-kill-start-2"
        Add-Logs $result $handle
        Switch-AsyncMode $handle
        Resize-Consumers $handle 1
        Wait-ForEvent $handle "CHAOS:$payload" 15
        Wait-ForBufferDrain $handle 15
        $summary = Get-AdminSummary $handle
        Add-Check $result "startupRecovered" (Test-StorageRecoveryVisible $summary)
        Add-Check $result "overallPressureAfterDrain" $summary.overallPressure
        return $result
    } catch {
        $result.status = "FAIL"
        Add-Note $result $_.Exception.Message
        return $result
    } finally {
        Stop-Sample $handle
    }
}

function Invoke-FailedReplayAfterForceKill($jarPath) {
    $result = New-Result "failed-queue-replay-after-force-kill"
    $dbPath = Join-Path $runDirectory "failed-queue-replay-after-force-kill.db"
    $payload = "kill-failed"
    $handle = $null
    try {
        $handle = Start-Sample $jarPath $dbPath "failed-queue-replay-after-force-kill-start-1"
        Add-Logs $result $handle
        Reset-State $handle
        Configure-Chaos $handle 1 0
        Switch-AsyncMode $handle
        Resize-Consumers $handle 1
        Emit-Chaos $handle $payload
        Wait-ForFailedAtLeast $handle 1 15
        $failedBeforeKill = Get-Buffer $handle
        Add-Check $result "failedBeforeKill" $failedBeforeKill.failedCount
        Stop-Sample $handle

        $handle = Start-Sample $jarPath $dbPath "failed-queue-replay-after-force-kill-start-2"
        Add-Logs $result $handle
        Configure-Chaos $handle 0 0
        $buffer = Get-Buffer $handle
        Add-Check $result "failedAfterRestart" $buffer.failedCount
        $entryId = [string]$buffer.entries[0].entryId
        Replay-Entry $handle $entryId
        Resize-Consumers $handle 1
        Wait-ForEvent $handle "CHAOS:$payload" 15
        Wait-ForBufferDrain $handle 15
        return $result
    } catch {
        $result.status = "FAIL"
        Add-Note $result $_.Exception.Message
        return $result
    } finally {
        Stop-Sample $handle
    }
}

function Invoke-InProgressRecoveryAfterForceKill($jarPath) {
    $result = New-Result "in-progress-recovery-after-force-kill"
    $dbPath = Join-Path $runDirectory "in-progress-recovery-after-force-kill.db"
    $payload = "kill-in-progress"
    $handle = $null
    try {
        $handle = Start-Sample $jarPath $dbPath "in-progress-recovery-after-force-kill-start-1"
        Add-Logs $result $handle
        Reset-State $handle
        Configure-Chaos $handle 0 5000
        Switch-AsyncMode $handle
        Resize-Consumers $handle 1
        Emit-Chaos $handle $payload
        Wait-ForInProgressAtLeast $handle 1 10
        Add-Check $result "inProgressBeforeKill" (Get-Buffer $handle).inProgressCount
        Stop-Sample $handle

        $handle = Start-Sample $jarPath $dbPath "in-progress-recovery-after-force-kill-start-2"
        Add-Logs $result $handle
        $buffer = $null
        Wait-Until {
            $buffer = Get-Buffer $handle
            $failedCount = [int](Get-PropertyValue $buffer "failedCount" 0)
            return $failedCount -ge 1
        } 15 "Recovered failed entry was not observed after restart"
        $buffer = Get-Buffer $handle
        Add-Check $result "failedAfterRestart" ([int](Get-PropertyValue $buffer "failedCount" 0))
        Add-Check $result "recoveryErrorCategory" ([string](Get-PropertyValue $buffer.entries[0] "errorCategory" ""))
        Add-Check $result "recoveryLastError" ([string](Get-PropertyValue $buffer.entries[0] "lastError" ""))
        Configure-Chaos $handle 0 0
        Replay-Entry $handle ([string](Get-PropertyValue $buffer.entries[0] "entryId" ""))
        Resize-Consumers $handle 1
        Wait-ForEvent $handle "CHAOS:$payload" 15
        Wait-ForBufferDrain $handle 15
        return $result
    } catch {
        $result.status = "FAIL"
        Add-Note $result $_.Exception.Message
        return $result
    } finally {
        Stop-Sample $handle
    }
}

function Invoke-RepeatedPendingKills($jarPath) {
    $result = New-Result "repeated-pending-force-kill-loop"
    $dbPath = Join-Path $runDirectory "repeated-pending-force-kill-loop.db"
    $handle = $null
    try {
        foreach ($i in 1..3) {
            $handle = Start-Sample $jarPath $dbPath "repeated-pending-force-kill-loop-start-$i"
            Add-Logs $result $handle
            if ($i -eq 1) {
                Reset-State $handle
            }
            Switch-AsyncMode $handle
            Resize-Consumers $handle 0
            Emit-Chaos $handle "loop-$i"
            Wait-ForPending $handle $i 12
            Add-Check $result "pendingBeforeKill#$i" (Get-Buffer $handle).pendingCount
            Stop-Sample $handle
        }

        $handle = Start-Sample $jarPath $dbPath "repeated-pending-force-kill-loop-final"
        Add-Logs $result $handle
        Switch-AsyncMode $handle
        Resize-Consumers $handle 1
        foreach ($i in 1..3) {
            Wait-ForEvent $handle "CHAOS:loop-$i" 15
        }
        Wait-ForBufferDrain $handle 15
        Add-Check $result "finalEventCount" ((Get-ChaosEvents $handle).Count)
        return $result
    } catch {
        $result.status = "FAIL"
        Add-Note $result $_.Exception.Message
        return $result
    } finally {
        Stop-Sample $handle
    }
}

function Invoke-HeaderCorruptionStartupRecovery($jarPath) {
    $result = New-Result "header-corruption-startup-recovery"
    $dbPath = Join-Path $runDirectory "header-corruption-startup-recovery.db"
    [System.IO.File]::WriteAllBytes($dbPath, [byte[]](1, 2, 3, 4, 5))
    $handle = $null
    try {
        $handle = Start-Sample $jarPath $dbPath "header-corruption-startup-recovery-start"
        Add-Logs $result $handle
        $summary = Get-AdminSummary $handle
        $storageRecovery = Get-StorageRecovery $summary
        Add-Check $result "storageRecoveryVisible" (Test-StorageRecoveryVisible $summary)
        Add-Check $result "storageRecoveryStatus" ([string](Get-PropertyValue $storageRecovery "statusLabel" ""))
        Add-Check $result "storageRecoveryMovedToPath" ([string](Get-PropertyValue $storageRecovery "movedToPath" ""))
        if (-not (Test-StorageRecoveryVisible $summary)) {
            throw "Expected startup recovery banner after deliberate header corruption"
        }
        return $result
    } catch {
        $result.status = "FAIL"
        Add-Note $result $_.Exception.Message
        return $result
    } finally {
        Stop-Sample $handle
    }
}

function Write-ReportFiles([array]$results) {
    $overallStatus = if ($results.status -contains "FAIL") { "FAIL" } else { "PASS" }
    $reportObject = [ordered]@{
        executedAt = (Get-Date).ToString("o")
        overallStatus = $overallStatus
        scenarioCount = $results.Count
        passedCount = @($results | Where-Object { $_.status -eq "PASS" }).Count
        failedCount = @($results | Where-Object { $_.status -eq "FAIL" }).Count
        results = $results
    }

    $jsonPath = Join-Path $runDirectory "mapdb-abuse-suite-summary.json"
    $mdPath = Join-Path $runDirectory "mapdb-abuse-suite-summary.md"
    $trMdPath = Join-Path $trRunDirectory "mapdb-abuse-suite-summary.md"

    $reportObject | ConvertTo-Json -Depth 10 | Set-Content -Path $jsonPath -Encoding UTF8

    $english = @()
    $english += "# MapDB Abuse Suite"
    $english += ""
    $english += "- Executed at: $($reportObject.executedAt)"
    $english += "- Overall status: $overallStatus"
    $english += ""
    $english += "| Scenario | Status | Key Signal |"
    $english += "| --- | --- | --- |"
    foreach ($result in $results) {
        $keySignal = if ($result.checks.Count -gt 0) { "$($result.checks[0].name)=$($result.checks[0].value)" } else { "-" }
        $english += "| $($result.scenario) | $($result.status) | $keySignal |"
    }
    $english += ""
    foreach ($result in $results) {
        $english += "## $($result.scenario)"
        $english += ""
        $english += "- Status: $($result.status)"
        foreach ($check in $result.checks) {
            $english += "- $($check.name): $($check.value)"
        }
        foreach ($note in $result.notes) {
            $english += "- Note: $note"
        }
        foreach ($logPath in ($result.logs | Select-Object -Unique)) {
            $english += "- Log: $logPath"
        }
        $english += ""
    }
    $english | Set-Content -Path $mdPath -Encoding UTF8

    $turkish = @()
    $turkish += "# MapDB Yipratici Test Raporu"
    $turkish += ""
    $turkish += "- Calisma zamani: $($reportObject.executedAt)"
    $turkish += "- Genel durum: $overallStatus"
    $turkish += ""
    $turkish += "| Senaryo | Durum | Ana sinyal |"
    $turkish += "| --- | --- | --- |"
    foreach ($result in $results) {
        $keySignal = if ($result.checks.Count -gt 0) { "$($result.checks[0].name)=$($result.checks[0].value)" } else { "-" }
        $turkish += "| $($result.scenario) | $($result.status) | $keySignal |"
    }
    $turkish += ""
    foreach ($result in $results) {
        $turkish += "## $($result.scenario)"
        $turkish += ""
        $turkish += "- Durum: $($result.status)"
        foreach ($check in $result.checks) {
            $turkish += "- $($check.name): $($check.value)"
        }
        foreach ($note in $result.notes) {
            $turkish += "- Not: $note"
        }
        foreach ($logPath in ($result.logs | Select-Object -Unique)) {
            $turkish += "- Log: $logPath"
        }
        $turkish += ""
    }
    $turkish | Set-Content -Path $trMdPath -Encoding UTF8

    return [pscustomobject]@{
        JsonPath = $jsonPath
        MarkdownPath = $mdPath
        TurkishMarkdownPath = $trMdPath
        OverallStatus = $overallStatus
    }
}

Ensure-Directory $runDirectory
Ensure-Directory $trRunDirectory

if ($BuildSample) {
    Build-SampleJar
}

$jarPath = Get-SampleJarPath
$results = @(
    Invoke-PendingSurvivesForceKill $jarPath
    Invoke-FailedReplayAfterForceKill $jarPath
    Invoke-InProgressRecoveryAfterForceKill $jarPath
    Invoke-RepeatedPendingKills $jarPath
    Invoke-HeaderCorruptionStartupRecovery $jarPath
)

$report = Write-ReportFiles $results
Write-Host "MapDB abuse suite completed with status $($report.OverallStatus)"
Write-Host "JSON report: $($report.JsonPath)"
Write-Host "Markdown report: $($report.MarkdownPath)"
Write-Host "Turkce rapor: $($report.TurkishMarkdownPath)"

if ($report.OverallStatus -ne "PASS") {
    exit 1
}
