param(
    [switch]$BuildSample = $true,
    [int]$BasePort = 18120,
    [int]$AdminPort = 0,
    [int]$ReadyTimeoutSeconds = 60,
    [string]$OutputRoot = ".\reports\mapdb-abuse"
)

# Recovery stress suite. Each scenario starts the consumer sample with its MapDB queue, kills it
# hard, restarts it and checks that queued work survives. Runtime control (mode, consumers,
# queue browse and replay) goes through the central ASL admin server, the same path an operator
# uses; the sample only exposes its own business and test endpoints.

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "java-tooling.ps1")
$javaTooling = Initialize-AslJavaTooling

$repoRoot = Split-Path -Parent $PSScriptRoot
$sampleModuleDir = Join-Path $repoRoot "asl-consumer-sample"
$adminModuleDir = Join-Path $repoRoot "asl-admin-server"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runDirectory = Join-Path $repoRoot (($OutputRoot -replace '^[.][\\/]', ''))
$runDirectory = Join-Path $runDirectory $timestamp
$trRunDirectory = Join-Path $repoRoot "tr\reports\mapdb-abuse\$timestamp"
$script:nextPort = $BasePort
if ($AdminPort -le 0) {
    $AdminPort = $BasePort + 90
}
$adminBaseUrl = "http://localhost:$AdminPort"
$adminApi = "$adminBaseUrl/asl-admin/api"
$adminSecret = [guid]::NewGuid().ToString("N")
$script:adminProcess = $null
# Child JVMs run without a console window on Windows (they would otherwise pop up in front).
$script:hiddenWindow = @{}
if ($IsWindows -or $env:OS -eq 'Windows_NT') {
    $script:hiddenWindow.WindowStyle = 'Hidden'
}

$serviceId = "chaos.service"
$methodId = "emit(java.lang.String)"

# Order of the raw counters in a heartbeat sample (see asl-admin-protocol MethodSample).
$SampleQueuePending = 7
$SampleQueueFailed = 8
$SampleQueueInProgress = 9

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

function Build-Jars {
    Write-Host "Building sample and admin server jars..."
    Push-Location $repoRoot
    try {
        Invoke-CheckedCommand $javaTooling.MavenCommand @("-pl", "asl-consumer-sample,asl-admin-server", "-am", "package", "-DskipTests", "-q")
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

function Get-AdminJarPath {
    $jar = Get-ChildItem (Join-Path $adminModuleDir "target") -Filter "*-exec.jar" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $jar) {
        throw "Admin server jar could not be found under asl-admin-server\\target"
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
        $ok = $false
        try {
            $ok = [bool](& $condition)
        } catch {
            $ok = $false
        }
        if ($ok) {
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

function Start-AdminServer([string]$jarPath) {
    $arguments = @(
        "-jar", $jarPath,
        "--port=$AdminPort",
        "--secret=$adminSecret",
        "--security.mode=NONE",
        "--storage.mode=MEMORY",
        "--history.enabled=false",
        "--interval=2s",
        "--watch.interval=500ms",
        "--instance.expire-after=30s"
    )
    $script:adminProcess = Start-Process @script:hiddenWindow -FilePath $javaTooling.JavaCommand -ArgumentList $arguments -WorkingDirectory $runDirectory `
        -RedirectStandardOutput (Join-Path $runDirectory "admin-server.out.log") `
        -RedirectStandardError (Join-Path $runDirectory "admin-server.err.log") -PassThru
    Wait-Until { (Invoke-RestMethod -Method Get -Uri "$adminApi/server" -TimeoutSec 5) -ne $null } $ReadyTimeoutSeconds "Admin server did not start on port $AdminPort"
}

function Stop-AdminServer {
    if ($null -ne $script:adminProcess -and -not $script:adminProcess.HasExited) {
        Stop-Process -Id $script:adminProcess.Id -Force -ErrorAction SilentlyContinue
        Wait-Process -Id $script:adminProcess.Id -Timeout 15 -ErrorAction SilentlyContinue
    }
}

function Start-Sample([string]$jarPath, [string]$dbPath, [string]$application, [string]$scenarioName) {
    $port = Get-NextPort
    $logPrefix = Join-Path $runDirectory $scenarioName
    $stdoutPath = "$logPrefix.out.log"
    $stderrPath = "$logPrefix.err.log"
    $arguments = @(
        "-jar",
        $jarPath,
        "--server.port=$port",
        "--asl.async.mapdb.enabled=true",
        "--asl.async.mapdb.path=$($dbPath -replace '\\','/')",
        "--asl.admin.client.enabled=true",
        "--asl.admin.client.server-url=$adminBaseUrl",
        "--asl.admin.client.secret=$adminSecret",
        "--asl.admin.client.application=$application",
        "--asl.admin.client.instance=$scenarioName",
        "--asl.admin.client.interval=2s",
        "--asl.admin.client.queue-browse=true"
    )
    $process = Start-Process @script:hiddenWindow -FilePath $javaTooling.JavaCommand -ArgumentList $arguments -WorkingDirectory $repoRoot -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -PassThru
    $handle = [pscustomobject]@{
        Scenario = $scenarioName
        Application = $application
        Instance = $scenarioName
        Port = $port
        BaseUrl = "http://localhost:$port"
        Process = $process
        StdOut = $stdoutPath
        StdErr = $stderrPath
        DbPath = $dbPath
    }
    try {
        Wait-Until {
            $health = Invoke-RestMethod -Method Get -Uri "$($handle.BaseUrl)/api/health" -TimeoutSec 5
            return $health.status -eq "UP"
        } $ReadyTimeoutSeconds "Sample did not become healthy for scenario '$scenarioName'"
        Wait-Until { $null -ne (Get-MethodSample $handle) } $ReadyTimeoutSeconds "Sample '$scenarioName' did not report to the admin server"
    } catch {
        if (-not $process.HasExited) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        }
        throw
    }
    return $handle
}

function Stop-Sample($handle) {
    if ($null -ne $handle -and $null -ne $handle.Process -and -not $handle.Process.HasExited) {
        Stop-Process -Id $handle.Process.Id -Force -ErrorAction SilentlyContinue
        Wait-Process -Id $handle.Process.Id -Timeout 15 -ErrorAction SilentlyContinue
    }
}

# ---- admin server view of one sample ---------------------------------------------------------

function Get-AdminApp($handle) {
    return Invoke-RestMethod -Method Get -Uri "$adminApi/apps/$($handle.Application)" -TimeoutSec 30
}

function Get-AdminInstance($app, $handle) {
    return @($app.instances | Where-Object { $_.name -eq $handle.Instance }) | Select-Object -First 1
}

function Get-MethodSample($handle) {
    $app = Get-AdminApp $handle
    $index = -1
    for ($i = 0; $i -lt @($app.methods).Count; $i++) {
        if ($app.methods[$i].serviceId -eq $serviceId -and $app.methods[$i].methodId -eq $methodId) {
            $index = $i
        }
    }
    $instance = Get-AdminInstance $app $handle
    if ($index -lt 0 -or $null -eq $instance) {
        return $null
    }
    return $instance.samples[$index]
}

function Get-Buffer($handle) {
    $sample = Get-MethodSample $handle
    if ($null -eq $sample) {
        throw "No telemetry from '$($handle.Instance)' yet"
    }
    return [pscustomobject]@{
        pendingCount = [long]$sample[$SampleQueuePending]
        failedCount = [long]$sample[$SampleQueueFailed]
        inProgressCount = [long]$sample[$SampleQueueInProgress]
    }
}

function Set-Override($handle, [hashtable]$setting) {
    $form = @{ serviceId = $serviceId; methodId = $methodId }
    foreach ($key in $setting.Keys) {
        $form[$key] = [string]$setting[$key]
    }
    Invoke-RestMethod -Method Post -Uri "$adminApi/apps/$($handle.Application)/overrides" -Body $form -TimeoutSec 30 | Out-Null
    Wait-Until {
        $app = Get-AdminApp $handle
        $instance = Get-AdminInstance $app $handle
        return $null -ne $instance -and $instance.inSync -and [long]$instance.appliedConfigVersion -eq [long]$app.configVersion
    } 30 "Override was not applied by '$($handle.Instance)'"
}

function Invoke-AdminCommand($handle, [string]$type, [string]$entryId = "", [int]$limit = 0) {
    $form = @{ serviceId = $serviceId; methodId = $methodId; instance = $handle.Instance; type = $type }
    if ($entryId) { $form.entryId = $entryId }
    if ($limit -gt 0) { $form.limit = [string]$limit }
    $created = Invoke-RestMethod -Method Post -Uri "$adminApi/apps/$($handle.Application)/commands" -Body $form -TimeoutSec 30
    $commandId = [long]@($created.commandIds)[0]
    $script:commandResult = $null
    Wait-Until {
        $app = Get-AdminApp $handle
        $script:commandResult = @($app.commands | Where-Object { [long]$_.commandId -eq $commandId -and $_.status -ne "PENDING" }) | Select-Object -First 1
        return $null -ne $script:commandResult
    } 30 "Command $type was not answered by '$($handle.Instance)'"
    if ($script:commandResult.status -ne "OK") {
        throw "Command $type ended with $($script:commandResult.status): $($script:commandResult.message)"
    }
    return $script:commandResult
}

function Get-QueueEntries($handle) {
    return @((Invoke-AdminCommand $handle "BROWSE_QUEUE" "" 10).entries)
}

# ---- sample operations ------------------------------------------------------------------------

function Configure-Chaos($handle, [int]$failuresRemaining, [int]$processingDelayMillis) {
    Invoke-JsonRequest "Post" "$($handle.BaseUrl)/api/test/scenarios/chaos/emit" @{
        failuresRemaining = $failuresRemaining
        processingDelayMillis = $processingDelayMillis
    } | Out-Null
}

function Reset-State($handle) {
    Invoke-RestMethod -Method Post -Uri "$($handle.BaseUrl)/api/test/scenarios/reset" -TimeoutSec 30 | Out-Null
}

# Puts the chaos method in ASYNC mode with the given consumer count, as one application-wide
# override: it is stored on the admin server and applied again when a restarted pod registers.
function Resize-Consumers($handle, [int]$consumerThreads) {
    Set-Override $handle @{ async = "true"; consumerThreads = $consumerThreads }
}

function Emit-Chaos($handle, [string]$payload) {
    Invoke-RestMethod -Method Post -Uri "$($handle.BaseUrl)/api/chaos/emit/$payload" -TimeoutSec 30 | Out-Null
}

function Get-ChaosEvents($handle) {
    return Invoke-RestMethod -Method Get -Uri "$($handle.BaseUrl)/api/chaos/events" -TimeoutSec 30
}

function Get-StorageRecovery($handle) {
    return Invoke-RestMethod -Method Get -Uri "$($handle.BaseUrl)/api/chaos/storage-recovery" -TimeoutSec 30
}

function Replay-Entry($handle, [string]$entryId) {
    $result = Invoke-AdminCommand $handle "REPLAY_ENTRY" $entryId
    if ([long]$result.value -ne 1) {
        throw "Replay of entry $entryId did not take effect"
    }
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
    $application = "abuse-pending"
    $dbPath = Join-Path $runDirectory "pending-survives-force-kill.db"
    $payload = "kill-pending"
    $handle = $null
    try {
        $handle = Start-Sample $jarPath $dbPath $application "pending-survives-force-kill-start-1"
        Add-Logs $result $handle
        Reset-State $handle
        Resize-Consumers $handle 0
        Emit-Chaos $handle $payload
        Wait-ForPending $handle 1 15
        Add-Check $result "pendingBeforeKill" 1
        Stop-Sample $handle

        $handle = Start-Sample $jarPath $dbPath $application "pending-survives-force-kill-start-2"
        Add-Logs $result $handle
        Resize-Consumers $handle 1
        Wait-ForEvent $handle "CHAOS:$payload" 20
        Wait-ForBufferDrain $handle 20
        Add-Check $result "storeArchivedOnRestart" ([bool](Get-StorageRecovery $handle).recovered)
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
    $application = "abuse-failed-replay"
    $dbPath = Join-Path $runDirectory "failed-queue-replay-after-force-kill.db"
    $payload = "kill-failed"
    $handle = $null
    try {
        $handle = Start-Sample $jarPath $dbPath $application "failed-queue-replay-after-force-kill-start-1"
        Add-Logs $result $handle
        Reset-State $handle
        Configure-Chaos $handle 1 0
        Resize-Consumers $handle 1
        Emit-Chaos $handle $payload
        Wait-ForFailedAtLeast $handle 1 20
        Add-Check $result "failedBeforeKill" (Get-Buffer $handle).failedCount
        Stop-Sample $handle

        $handle = Start-Sample $jarPath $dbPath $application "failed-queue-replay-after-force-kill-start-2"
        Add-Logs $result $handle
        Configure-Chaos $handle 0 0
        Wait-ForFailedAtLeast $handle 1 20
        Add-Check $result "failedAfterRestart" (Get-Buffer $handle).failedCount
        $entries = Get-QueueEntries $handle
        $failed = @($entries | Where-Object { $_.state -eq "FAILED" }) | Select-Object -First 1
        if ($null -eq $failed) {
            throw "Browse through the admin server returned no FAILED entry"
        }
        Replay-Entry $handle ([string]$failed.entryId)
        Resize-Consumers $handle 1
        Wait-ForEvent $handle "CHAOS:$payload" 20
        Wait-ForBufferDrain $handle 20
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
    $application = "abuse-in-progress"
    $dbPath = Join-Path $runDirectory "in-progress-recovery-after-force-kill.db"
    $payload = "kill-in-progress"
    $handle = $null
    try {
        $handle = Start-Sample $jarPath $dbPath $application "in-progress-recovery-after-force-kill-start-1"
        Add-Logs $result $handle
        Reset-State $handle
        Configure-Chaos $handle 0 15000
        Resize-Consumers $handle 1
        Emit-Chaos $handle $payload
        Wait-ForInProgressAtLeast $handle 1 15
        Add-Check $result "inProgressBeforeKill" (Get-Buffer $handle).inProgressCount
        Stop-Sample $handle

        $handle = Start-Sample $jarPath $dbPath $application "in-progress-recovery-after-force-kill-start-2"
        Add-Logs $result $handle
        Configure-Chaos $handle 0 0
        Wait-Until {
            $buffer = Get-Buffer $handle
            return ($buffer.failedCount + $buffer.pendingCount + $buffer.inProgressCount) -ge 1 -or ((Get-ChaosEvents $handle) -contains "CHAOS:$payload")
        } 20 "Interrupted entry was neither kept nor redelivered after restart"
        $buffer = Get-Buffer $handle
        Add-Check $result "failedAfterRestart" $buffer.failedCount
        Add-Check $result "pendingAfterRestart" $buffer.pendingCount
        if ($buffer.failedCount -ge 1) {
            $entries = Get-QueueEntries $handle
            $failed = @($entries | Where-Object { $_.state -eq "FAILED" }) | Select-Object -First 1
            Add-Check $result "recoveryErrorCategory" ([string](Get-PropertyValue $failed "errorCategory" ""))
            Add-Check $result "recoveryLastError" ([string](Get-PropertyValue $failed "lastError" ""))
            Replay-Entry $handle ([string]$failed.entryId)
        }
        Resize-Consumers $handle 1
        Wait-ForEvent $handle "CHAOS:$payload" 20
        Wait-ForBufferDrain $handle 20
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
    $application = "abuse-repeated"
    $dbPath = Join-Path $runDirectory "repeated-pending-force-kill-loop.db"
    $handle = $null
    try {
        foreach ($i in 1..3) {
            $handle = Start-Sample $jarPath $dbPath $application "repeated-pending-force-kill-loop-start-$i"
            Add-Logs $result $handle
            if ($i -eq 1) {
                Reset-State $handle
            }
            Resize-Consumers $handle 0
            Emit-Chaos $handle "loop-$i"
            Wait-ForPending $handle $i 15
            Add-Check $result "pendingBeforeKill#$i" (Get-Buffer $handle).pendingCount
            Stop-Sample $handle
        }

        $handle = Start-Sample $jarPath $dbPath $application "repeated-pending-force-kill-loop-final"
        Add-Logs $result $handle
        Resize-Consumers $handle 1
        foreach ($i in 1..3) {
            Wait-ForEvent $handle "CHAOS:loop-$i" 20
        }
        Wait-ForBufferDrain $handle 20
        $finalEvents = @(Get-ChaosEvents $handle | ForEach-Object { $_ } | Where-Object { $_ -like "CHAOS:loop-*" })
        Add-Check $result "finalEventCount" $finalEvents.Count
        if ($finalEvents.Count -ne 3) {
            throw "Expected 3 delivered loop events, saw $($finalEvents.Count)"
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

function Invoke-HeaderCorruptionStartupRecovery($jarPath) {
    $result = New-Result "header-corruption-startup-recovery"
    $application = "abuse-corruption"
    $dbPath = Join-Path $runDirectory "header-corruption-startup-recovery.db"
    [System.IO.File]::WriteAllBytes($dbPath, [byte[]](1, 2, 3, 4, 5))
    $handle = $null
    try {
        $handle = Start-Sample $jarPath $dbPath $application "header-corruption-startup-recovery-start"
        Add-Logs $result $handle
        $recovery = Get-StorageRecovery $handle
        Add-Check $result "storageRecovered" ([bool]$recovery.recovered)
        Add-Check $result "archivedPath" ([string](Get-PropertyValue $recovery "archivedPath" ""))
        Add-Check $result "reportingToAdminServer" ($null -ne (Get-MethodSample $handle))
        if (-not [bool]$recovery.recovered) {
            throw "Expected the corrupted store to be archived and recreated at startup"
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
    Build-Jars
}

$jarPath = Get-SampleJarPath
Start-AdminServer (Get-AdminJarPath)
try {
    $results = @(
        Invoke-PendingSurvivesForceKill $jarPath
        Invoke-FailedReplayAfterForceKill $jarPath
        Invoke-InProgressRecoveryAfterForceKill $jarPath
        Invoke-RepeatedPendingKills $jarPath
        Invoke-HeaderCorruptionStartupRecovery $jarPath
    )
} finally {
    Stop-AdminServer
}

$report = Write-ReportFiles $results
Write-Host "MapDB abuse suite completed with status $($report.OverallStatus)"
Write-Host "JSON report: $($report.JsonPath)"
Write-Host "Markdown report: $($report.MarkdownPath)"
Write-Host "Turkce rapor: $($report.TurkishMarkdownPath)"

if ($report.OverallStatus -ne "PASS") {
    exit 1
}
