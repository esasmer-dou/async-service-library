# Troubleshooting

**Language:** English | [Türkçe](../tr/docs/troubleshooting.md) · [Documentation home](../README.md#documentation)

| Symptom | Likely cause | What to do |
|---|---|---|
| Startup fails: *"configures unknown governed service/method"*, *"does not reach any governed method"* or *"is not a method signature"* | a yml key under `asl.runtime.services` or `asl.async.mapdb.services` has a typo, or is written without quoted brackets so Spring split it at the dots | use the exact ids and write `"[mail.service]"`, `"[send(java.lang.String)]"` ([rules](configuration.md#rules)) |
| Startup fails: *"…to ASYNC but no async execution engine is attached"* | `execution-mode: ASYNC` without the queue | add the MapDB starter and set `asl.async.mapdb.enabled: true` |
| A method is not governed: no metrics, limits ignored | the implementation is injected instead of the interface, or the interface is in another module and no wrapper bean exists (ASL logs a warning) | inject the interface; register `XxxAsl.wrap(...)` as `@Primary` ([getting started](getting-started.md#4-inject-the-interface-and-run)) |
| Compile error: *asyncCapable … supported only for void methods and … CompletableFuture* | `asyncCapable = true` on a method returning another type | only `void` or `CompletableFuture<T>` can be queued |
| `MethodUnavailableException`, reason `DISABLED` | the method is stopped (portal, yml or annotation) | start it; check the portal label for an override |
| … reason `CONCURRENCY_LIMIT` | more calls at once than `max-concurrency` | raise the limit, retry later, or use ASYNC ([scenario 1](scenarios.md#1-a-dependency-is-slow-and-ties-up-threads)) |
| … reason `RATE_LIMIT` | more calls per second than `rate-limit` in SYNC mode | raise the limit or use ASYNC, which queues instead ([scenario 2](scenarios.md#2-a-partner-allows-n-requests-per-second)) |
| `AsyncAdmissionRejectedException` | the queue is full (`LANE_CAPACITY`, `GLOBAL_CAPACITY`), the payload is too large (`PAYLOAD_TOO_LARGE`), or too many callers wait for results (`RESULT_CAPACITY`) | find why consumers are behind; raise the limit for that method ([capacity](configuration.md#admission-and-capacity)) |
| Calls pile up in `PENDING` | consumers are `0`, the method is rate-limited, or the worker pool is busy | check the *Consumers* and *Rate limit* columns; real parallelism is the smallest of consumers, max concurrency and `workers.max-threads` |
| Calls land in `FAILED` at once | `retry.max-attempts` is `1` (default) | fix the cause, then replay ([scenario 5](scenarios.md#5-the-bug-is-fixed-failed-calls-must-run-again)); raise attempts only for idempotent methods |
| Category `DECODE` | the stored arguments cannot be read, for example a class was renamed or not allowlisted | allowlist or register the type, or delete the entries |
| Category `TIMEOUT` | `execution-timeout-millis` or `completion-timeout-millis` passed | make the call faster or raise the timeout |
| A `CompletableFuture` caller gets `AsyncResultUnavailableException` | `WAIT_TIMEOUT`: no consumer finished in time. `SHUTDOWN`: the application stopped. `DUPLICATE`: same idempotency key. | the call is not lost; its outcome goes to your `AsyncResultHandler` ([concepts](concepts.md#completablefuture-methods)) |
| Startup fails with `MapDbStoreLockedException` | another process owns the queue file | give every replica its own file and volume |
| Startup fails with a corruption error | the store is damaged and the policy is `FAIL_FAST` | keep the file for analysis; `corruption-policy: ARCHIVE_AND_RESET` starts empty (data loss) |
| Health `aslMapDb` DOWN, `asl.mapdb.engine.healthy` = 0 | the queue stopped after a storage failure | check disk and permissions, then restart |
| Replica not *in sync* in the portal | it has not picked up the latest settings yet (next heartbeat), or it reports a setting error | wait a few seconds; the replica row shows the error text |
| A replica disappears from the portal | no heartbeat for `3 × interval` (45 s) | check that the pod runs and reaches the server; replica overrides are dropped with it |
| The portal cannot list queue entries | `asl.admin.client.queue-browse` is `false` on that replica | enable it if sending entry summaries to the server is acceptable |
| Admin server rejects heartbeats (`401`) | different secrets, or clocks more than 60 s apart | use the same secret; sync clocks (NTP) |
| Portal changes rejected with `403` | the request came from another origin, or the user is a `VIEWER` | use the portal's own address or `security.allowed-origins`; give the user `OPERATOR` |
