# ASL Chaos Test Guide

This guide is for manually exercising the MapDB-backed async queue, replay flow, and restart recovery behavior in the sample application.

If you want an automated destructive run that force-kills the JVM and writes a report, use:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-mapdb-abuse-suite.ps1
```

## Scope

The guide covers:

- normal async success
- forced async failure
- replay from failed buffer
- pending queue buildup
- consumer resize and drain
- method stop and reject
- restart with pending queue recovery
- restart with failed queue recovery
- graceful shutdown drain semantics
- forced JVM kill with pending queue persistence
- forced JVM kill with failed queue persistence
- forced JVM kill while work is in progress
- repeated kill / restart loops against the same queue file
- deliberate header corruption startup recovery

## Defaults

The sample runs with:

- application API: `http://localhost:8081`
- admin UI: `http://localhost:8081/asl`
- admin REST: `http://localhost:8081/asl/api`
- queue store: `./data/asl-consumer-sample-queue.db`

Key async methods:

- `mail.service -> publishAudit(java.lang.String)`
- `customer.service -> publishWelcome(java.lang.String)`
- `inventory.service -> publishSnapshot(java.lang.String)`
- `chaos.service -> emit(java.lang.String)`

For restart and recovery tests, use `chaos.service`. It is intentionally self-contained and does not depend on in-memory business records.

## Start

From the repository root:

```powershell
mvn -pl asl-consumer-sample -am spring-boot:run
```

Open:

- UI: `http://localhost:8081/asl`
- REST root: `http://localhost:8081/asl/api/services`

## Shared Helpers

Reset all sample state:

```powershell
curl.exe -s -X POST "http://localhost:8081/api/test/scenarios/reset"
```

List all governed services:

```powershell
curl.exe -s "http://localhost:8081/asl/api/services"
```

See one governed service:

```powershell
curl.exe -s "http://localhost:8081/asl/api/services/chaos.service"
```

Read one method buffer:

```powershell
curl.exe -s "http://localhost:8081/asl/api/services/chaos.service/methods/emit%28java.lang.String%29/buffer"
```

Switch `chaos.emit` to async:

```powershell
curl.exe -s -X POST "http://localhost:8081/asl/api/services/chaos.service/methods/emit%28java.lang.String%29/mode" -H "Content-Type: application/json" -d "{\"executionMode\":\"ASYNC\"}"
```

Resize `chaos.emit` consumers:

```powershell
curl.exe -s -X POST "http://localhost:8081/asl/api/services/chaos.service/methods/emit%28java.lang.String%29/consumer-threads" -H "Content-Type: application/json" -d "{\"consumerThreads\":1}"
```

Configure `chaos.emit` scenario:

```powershell
curl.exe -s -X POST "http://localhost:8081/api/test/scenarios/chaos/emit" -H "Content-Type: application/json" -d "{\"failuresRemaining\":0,\"processingDelayMillis\":0}"
```

Trigger `chaos.emit`:

```powershell
curl.exe -s -X POST "http://localhost:8081/api/chaos/emit/run-1"
```

Read produced chaos events:

```powershell
curl.exe -s "http://localhost:8081/api/chaos/events"
```

## Scenario 1: Async Success

Purpose:

- verify async mode works
- verify queue drains
- verify business side effect is visible

Steps:

1. Reset state.
2. Set `chaos.emit` to `ASYNC`.
3. Set consumer threads to `1`.
4. Configure scenario with `0` failures and `0` delay.
5. Trigger `POST /api/chaos/emit/success-1`.
6. Read `/api/chaos/events`.
7. Read the method buffer.

Expected result:

- events contain `CHAOS:success-1`
- buffer shows `Pending: 0`, `Failed: 0`, `In progress: 0`

## Scenario 2: Forced Async Failure

Purpose:

- verify async failure lands in failed buffer

Steps:

1. Reset state.
2. Set `chaos.emit` to `ASYNC`.
3. Set consumer threads to `1`.
4. Configure scenario with `1` failure and `0` delay.
5. Trigger `POST /api/chaos/emit/fail-once`.
6. Read the method buffer.
7. Read `/api/chaos/events`.

Expected result:

- one failed buffer entry exists
- no `CHAOS:fail-once` event exists yet

Useful command:

```powershell
curl.exe -s -X POST "http://localhost:8081/api/test/scenarios/chaos/emit" -H "Content-Type: application/json" -d "{\"failuresRemaining\":1,\"processingDelayMillis\":0}"
```

## Scenario 3: Replay Failed Entry

Purpose:

- verify failed entries can be replayed after issue resolution

Steps:

1. Complete Scenario 2 first.
2. Reconfigure scenario with `0` failures.
3. Read the method buffer and copy the failed `entryId`.
4. Replay that entry.
5. Read `/api/chaos/events`.
6. Read the method buffer again.

Replay command template:

```powershell
curl.exe -s -X POST "http://localhost:8081/asl/api/services/chaos.service/methods/emit%28java.lang.String%29/buffer/{entryId}/replay"
```

Expected result:

- event list now contains `CHAOS:fail-once`
- failed buffer drains to zero

## Scenario 4: Pending Queue Buildup

Purpose:

- verify queue accumulates when consumers are disabled

Steps:

1. Reset state.
2. Set `chaos.emit` to `ASYNC`.
3. Set consumer threads to `0`.
4. Configure scenario with `0` failures and `0` delay.
5. Trigger:
   - `POST /api/chaos/emit/pending-1`
   - `POST /api/chaos/emit/pending-2`
   - `POST /api/chaos/emit/pending-3`
6. Read the method buffer.

Expected result:

- `Pending` count increases
- no events are produced yet

## Scenario 5: Consumer Resize And Drain

Purpose:

- verify queued work drains when consumers are enabled later

Steps:

1. Build pending queue using Scenario 4.
2. Resize consumer threads from `0` to `2`.
3. Read `/api/chaos/events`.
4. Read the method buffer until it drains.

Resize command:

```powershell
curl.exe -s -X POST "http://localhost:8081/asl/api/services/chaos.service/methods/emit%28java.lang.String%29/consumer-threads" -H "Content-Type: application/json" -d "{\"consumerThreads\":2}"
```

Expected result:

- queued items are processed
- events contain all pending payloads
- buffer reaches zero

## Scenario 6: Stop Method And Reject Traffic

Purpose:

- verify disabled method rejects before queueing

Steps:

1. Reset state.
2. Disable `chaos.emit` with a reason.
3. Call `POST /api/chaos/emit/rejected-1`.
4. Re-enable method.
5. Read buffer and events.

Disable command:

```powershell
curl.exe -s -X POST "http://localhost:8081/asl/api/services/chaos.service/methods/emit%28java.lang.String%29/disable?message=maintenance"
```

Enable command:

```powershell
curl.exe -s -X POST "http://localhost:8081/asl/api/services/chaos.service/methods/emit%28java.lang.String%29/enable"
```

Expected result:

- request is rejected
- no queue entry is created
- no event is produced

## Scenario 7: Restart Recovery For Pending Queue

Purpose:

- verify MapDB preserves pending work across app restart

Steps:

1. Reset state.
2. Set `chaos.emit` to `ASYNC`.
3. Set consumers to `0`.
4. Trigger `POST /api/chaos/emit/restart-pending`.
5. Read buffer and confirm one pending entry exists.
6. Stop the Spring Boot app with `Ctrl + C`.
7. Start the app again with the same queue DB path.
8. Switch `chaos.emit` to `ASYNC` again if needed.
9. Set consumers to `1`.
10. Read `/api/chaos/events`.
11. Read the buffer until it drains.

Expected result:

- pending item survives restart
- after consumers resume, event `CHAOS:restart-pending` appears

## Scenario 8: Restart Recovery For Failed Queue

Purpose:

- verify failed entries remain queryable and replayable after restart

Steps:

1. Reset state.
2. Set `chaos.emit` to `ASYNC`.
3. Set consumers to `1`.
4. Configure `failuresRemaining=1`.
5. Trigger `POST /api/chaos/emit/restart-failed`.
6. Confirm one failed buffer entry exists.
7. Stop the app.
8. Start the app again with the same queue DB path.
9. Configure `failuresRemaining=0`.
10. Read failed buffer and copy `entryId`.
11. Replay the failed entry.
12. Read `/api/chaos/events`.

Expected result:

- failed entry survives restart
- replay succeeds after scenario is corrected
- event `CHAOS:restart-failed` is produced

## Scenario 9: Graceful Shutdown Drain

Purpose:

- verify in-flight async work is allowed to finish during controlled shutdown

Steps:

1. Reset state.
2. Set `chaos.emit` to `ASYNC`.
3. Set consumers to `1`.
4. Configure `processingDelayMillis=900`.
5. Trigger `POST /api/chaos/emit/shutdown-drain`.
6. Quickly read buffer until `In progress` becomes visible.
7. Stop the app with `Ctrl + C`.
8. Start the app again.
9. Read the buffer.
10. Read `/api/chaos/events`.

Expected result:

- no stale buffered entry is left behind by the shutdown
- buffer returns clean after restart

Note:

- this sample currently validates drain semantics, not forced crash semantics
- for hard-kill behavior, use the engine-level stale `IN_PROGRESS` recovery test

## Scenario 10: Engine-Level Stale In-Progress Recovery

Purpose:

- verify stale `IN_PROGRESS` rows are marked `FAILED` on engine restart

This is already covered in:

- [MapDbAsyncExecutionEngineCoverageTest.java](E:\ReactorRepository\async-service-library\asl-mapdb\src\test\java\com\reactor\asl\mapdb\MapDbAsyncExecutionEngineCoverageTest.java)

Behavior:

- if the engine sees an old persisted invocation still marked `IN_PROGRESS` during startup
- it converts that row to `FAILED`
- failure message is `Recovered stale in-progress invocation after restart`

## Recommended Manual Order

If you want one clean operator walkthrough, use this order:

1. Scenario 1
2. Scenario 2
3. Scenario 3
4. Scenario 4
5. Scenario 5
6. Scenario 6
7. Scenario 7
8. Scenario 8
9. Scenario 9

This sequence moves from safe queue validation to real recovery behavior.

## UI Usage

You can perform the same flows from the UI:

- open `http://localhost:8081/asl`
- select `chaos.service`
- select `emit`
- use `Start Method` and `Stop Method`
- switch `Execution Mode`
- resize `Consumer Threads`
- inspect `Queue Buffer`
- replay or delete entries from the buffer section

For restart tests, keep the UI open in a browser tab and refresh after the application restarts.
