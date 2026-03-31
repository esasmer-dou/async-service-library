# Admin Control Plane Guide

## Purpose

The ASL admin control plane is the operational surface for:

- reading runtime pressure before drilling into a method
- identifying blocked async lanes and retained failures
- switching execution mode and concurrency at runtime
- replaying, deleting or clearing queue entries

The control plane is exposed from the same Spring Boot process:

- UI: `/asl`
- REST: `/asl/api`
- summary snapshot: `/asl/api/summary`

## Information Architecture

The current admin page is organized in three layers:

1. Global overview
   Shows service count, governed method count, queue backlog, failures, in-flight work and overall system pressure.

2. Operations digest
   Surfaces the most important attention items before the operator enters a specific service or method.
   Includes direct lane shortcuts so the operator can jump straight into the affected method workspace.

3. Service workspace
   Lets the operator search, filter and then open a single service and method for detailed actions.

This structure is deliberate. The operator should not need to inspect individual methods just to understand whether the runtime is healthy.

## Recommended Operational Flow

1. Open `/asl`
2. Read `System Pressure`
3. Review `Operations Digest`
4. Use `Open Highest-Priority Lane` or one of the shortcut cards
5. Inspect:
   - execution mode
   - consumer threads
   - queue depth
   - failed entries
   - last error
6. Apply the smallest safe intervention
7. Refresh and confirm pressure is moving down

## Summary Endpoint

`GET /asl/api/summary` is the canonical machine-readable control-plane snapshot.

Key fields:

- `serviceCount`
- `methodCount`
- `runningMethodCount`
- `stoppedMethodCount`
- `asyncCapableMethodCount`
- `asyncActiveMethodCount`
- `methodsWithErrors`
- `totalQueueDepth`
- `pendingEntries`
- `failedEntries`
- `inProgressEntries`
- `totalInFlight`
- `totalProcessed`
- `totalRejected`
- `overallPressure`
- `attentionItems`

Use this endpoint for:

- dashboards
- alerting bridges
- operational health checks
- benchmark reports

## Suggested Alert Rules

Start with these simple rules:

- `overallPressure == HIGH`
- `failedEntries > 0`
- `totalRejected > 0`
- `attentionItems` contains any `HIGH`
- `totalQueueDepth` grows across consecutive snapshots
- async lane has backlog while consumer threads are `0`

## Operator Notes

- `ASYNC` mode without consumers is valid for controlled buffering, but only intentionally.
- `FAILED` buffer entries should not accumulate silently.
- a stopped method is not always an incident; it may be a controlled maintenance action.
- increasing concurrency is not a universal fix; confirm downstream capacity first.

## Follow-up Direction

The next logical layer above this guide is external observability:

- scrape `/asl/api/summary`
- ship snapshots into your telemetry platform
- correlate queue growth with application latency and downstream errors
