# ASL Chaos Test Guide

This guide is for manually exercising the MapDB-backed async queue, replay flow, and restart recovery behavior in the sample application. Runtime changes go through the central admin server, the same path an operator uses.

For an automated destructive run that force-kills the JVM and writes a report (PowerShell 7):

```powershell
pwsh -File .\scripts\run-mapdb-abuse-suite.ps1
```

## Scope

- normal async success
- forced async failure
- replay from the failed buffer
- pending queue buildup
- consumer resize and drain
- method stop and reject
- restart with pending queue recovery
- restart with failed queue recovery
- graceful shutdown drain semantics
- interrupted in-progress work after a crash

## Start

Start the admin server and the sample connected to it, as in the [sample README](README.md#run-with-the-admin-portal):

- application API: `http://localhost:8080`
- admin portal: `http://localhost:8090/asl-admin/`
- queue store: `./data/asl-consumer-sample-queue.db`

For restart and recovery tests use `chaos.service -> emit(java.lang.String)`. It is self-contained and does not depend on in-memory business records.

## Shared Helpers

Paste these into the PowerShell session first:

```powershell
$app = "http://localhost:8080"
$api = "http://localhost:8090/asl-admin/api/apps/asl-consumer-sample"
$chaos = @{ serviceId = "chaos.service"; methodId = "emit(java.lang.String)" }

# Application-wide override; the admin server re-applies it when the sample restarts.
function Set-Chaos([hashtable]$setting) { Invoke-RestMethod -Method Post "$api/overrides" -Body ($chaos + $setting) }
function Clear-Chaos { Invoke-RestMethod -Method Delete "$api/overrides?serviceId=chaos.service&methodId=emit%28java.lang.String%29" }

# Queue counters from the latest heartbeat (pending includes retry-wait).
function Get-ChaosCounts {
    $a = Invoke-RestMethod $api
    $i = @(0..($a.methods.Count - 1) | Where-Object { $a.methods[$_].serviceId -eq "chaos.service" })[0]
    $s = $a.instances[0].samples[$i]
    [pscustomobject]@{ pending = $s[7]; failed = $s[8]; inProgress = $s[9]; inSync = $a.instances[0].inSync }
}

# Queue command on the (single) sample replica; the result arrives with its next heartbeat.
function Invoke-ChaosCommand([string]$type, [string]$entryId = "") {
    $body = $chaos + @{ type = $type }
    if ($entryId) { $body.entryId = $entryId }
    $id = (Invoke-RestMethod -Method Post "$api/commands" -Body $body).commandIds[0]
    do { Start-Sleep -Milliseconds 300; $r = (Invoke-RestMethod $api).commands | Where-Object { $_.commandId -eq $id } }
    while ($null -eq $r -or $r.status -eq "PENDING")
    $r
}

function Set-Scenario([int]$failures, [int]$delayMillis) {
    Invoke-RestMethod -Method Post "$app/api/test/scenarios/chaos/emit" -ContentType "application/json" `
        -Body (@{ failuresRemaining = $failures; processingDelayMillis = $delayMillis } | ConvertTo-Json)
}
function Send-Chaos([string]$payload) { Invoke-RestMethod -Method Post "$app/api/chaos/emit/$payload" }
function Get-ChaosEvents { Invoke-RestMethod "$app/api/chaos/events" }
function Reset-Sample { Clear-Chaos; Invoke-RestMethod -Method Post "$app/api/test/scenarios/reset" }
```

After `Set-Chaos`, wait until `Get-ChaosCounts` shows `inSync = True` (one heartbeat, about 2 s while the portal or the API is watching the application).

## Scenario 1: Async Success

1. `Reset-Sample`
2. `Set-Chaos @{ async = "true"; consumerThreads = "1" }`
3. `Set-Scenario 0 0`
4. `Send-Chaos success-1`
5. `Get-ChaosEvents` and `Get-ChaosCounts`

Expected: events contain `CHAOS:success-1`; pending, failed and in progress are `0`.

## Scenario 2: Forced Async Failure

1. `Reset-Sample`
2. `Set-Chaos @{ async = "true"; consumerThreads = "1" }`
3. `Set-Scenario 1 0`
4. `Send-Chaos fail-once`
5. `Get-ChaosCounts` and `Get-ChaosEvents`

Expected: `failed = 1`; no `CHAOS:fail-once` event yet.

## Scenario 3: Replay Failed Entry

1. Complete Scenario 2 first.
2. `Set-Scenario 0 0`
3. `$entry = (Invoke-ChaosCommand BROWSE_QUEUE).entries | Where-Object state -eq FAILED | Select-Object -First 1`
4. `Invoke-ChaosCommand REPLAY_ENTRY $entry.entryId`
5. `Get-ChaosEvents` and `Get-ChaosCounts`

Expected: events contain `CHAOS:fail-once`; `failed` drains to `0`.

Browsing requires `asl.admin.client.queue-browse=true`, which the sample sets.

## Scenario 4: Pending Queue Buildup

1. `Reset-Sample`
2. `Set-Chaos @{ async = "true"; consumerThreads = "0" }`
3. `Set-Scenario 0 0`
4. `Send-Chaos pending-1`, `Send-Chaos pending-2`, `Send-Chaos pending-3`
5. `Get-ChaosCounts`

Expected: `pending = 3`; no events yet.

## Scenario 5: Consumer Resize And Drain

1. Build the pending queue with Scenario 4.
2. `Set-Chaos @{ async = "true"; consumerThreads = "2" }`
3. Repeat `Get-ChaosCounts` until it drains, then `Get-ChaosEvents`.

Expected: all pending payloads are processed and the counters reach `0`.

## Scenario 6: Stop Method And Reject Traffic

1. `Reset-Sample`
2. `Set-Chaos @{ enabled = "false" }`
3. `Send-Chaos rejected-1` (fails with the method's unavailable message)
4. `Clear-Chaos` to re-enable the method.
5. `Get-ChaosCounts` and `Get-ChaosEvents`

Expected: the request is rejected, no queue entry is created and no event is produced.

## Scenario 7: Restart Recovery For Pending Queue

1. `Reset-Sample`
2. `Set-Chaos @{ async = "true"; consumerThreads = "0" }`
3. `Send-Chaos restart-pending`; `Get-ChaosCounts` shows `pending = 1`.
4. Stop the sample with `Ctrl + C` and start it again with the same queue path.
5. The admin server re-applies the override when the sample registers again.
6. `Set-Chaos @{ async = "true"; consumerThreads = "1" }`
7. `Get-ChaosEvents` and `Get-ChaosCounts`

Expected: the pending item survives the restart; after consumers resume, `CHAOS:restart-pending` appears.

## Scenario 8: Restart Recovery For Failed Queue

1. `Reset-Sample`
2. `Set-Chaos @{ async = "true"; consumerThreads = "1" }`
3. `Set-Scenario 1 0`, then `Send-Chaos restart-failed`; `Get-ChaosCounts` shows `failed = 1`.
4. Stop and start the sample with the same queue path.
5. `Set-Scenario 0 0`
6. Browse, then replay the failed entry as in Scenario 3.
7. `Get-ChaosEvents`

Expected: the failed entry survives the restart, the replay succeeds and `CHAOS:restart-failed` is produced.

## Scenario 9: Graceful Shutdown Drain

1. `Reset-Sample`
2. `Set-Chaos @{ async = "true"; consumerThreads = "1" }`
3. `Set-Scenario 0 900`
4. `Send-Chaos shutdown-drain`; repeat `Get-ChaosCounts` until `inProgress = 1`.
5. Stop the sample with `Ctrl + C`, start it again and read `Get-ChaosCounts`.

Expected: no stale entry is left behind; the counters are clean after restart.

## Scenario 10: Interrupted In-Progress Work After A Crash

A hard kill (for example `Stop-Process -Force` on the sample JVM) while an entry is `IN_PROGRESS` leaves it marked in progress in the store. On the next start the engine returns it to `PENDING` without consuming a retry attempt; after `asl.async.mapdb.retry.max-interruptions` interruptions it is marked `FAILED` instead. The recovery stress suite and these tests cover it:

- [MapDbDeliveryContractTest.java](../asl-mapdb/src/test/java/com/reactor/asl/mapdb/MapDbDeliveryContractTest.java)
- [MapDbAsyncExecutionEngineCoverageTest.java](../asl-mapdb/src/test/java/com/reactor/asl/mapdb/MapDbAsyncExecutionEngineCoverageTest.java)

## Portal Usage

The same flows work from the portal at `http://localhost:8090/asl-admin/`: select `asl-consumer-sample`, then `chaos.service` / `emit`, and use the stop/start, execution mode and consumer controls, browse the queue, and replay or delete entries. The portal keeps working while the sample restarts; the replica reappears when it registers again.
