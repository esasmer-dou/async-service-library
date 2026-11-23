# Configuration reference

**Language:** English | [Türkçe](../tr/docs/configuration.md) · [Documentation home](../README.md#documentation)

Every ASL property, its default and its effect. The starters ship Spring configuration metadata, so IDEs complete these keys. What the controls mean is explained in [Concepts](concepts.md).

## Rules

- YAML keys are kebab-case. As environment variables they are upper case with underscores: `asl.async.mapdb.enabled` → `ASL_ASYNC_MAPDB_ENABLED`.
- Byte limits are plain bytes (`1048576`, not `1MB`); time values end in `-millis`. The admin server is the exception: it accepts `15s`, `5m`, `64MB`.
- Invalid values or combinations stop startup; nothing is corrected silently.
- Service and method keys are the exact `@GovernedService` id and method id, written **in quoted brackets**: `"[mail.service]"`, `"[send(java.lang.String)]"`.
- With no `asl.*` properties at all, annotation values apply, and MapDB and the admin client stay off.

## Most used settings

| Property | Default | Change it to |
|---|---|---|
| `asl.runtime.services[…].methods[…].max-concurrency` | annotation value | limit parallel calls of one method |
| `asl.runtime.services[…].methods[…].rate-limit` | `0` (none) | limit calls per second of one method |
| `asl.runtime.services[…].methods[…].execution-mode` | `SYNC` | start an async-capable method in `ASYNC` |
| `asl.runtime.services[…].methods[…].consumer-threads` | annotation value | run more queued calls in parallel, or `0` to hold them |
| `asl.async.mapdb.enabled` | `false` | turn on the durable queue |
| `asl.async.mapdb.path` | `./data/asl-queue.db` | put the queue on a persistent volume |
| `asl.async.mapdb.retry.max-attempts` | `1` | retry failed queued calls automatically |
| `asl.async.mapdb.workers.max-threads` | CPU-based, 2–32 | size the shared worker pool for your container |
| `asl.admin.client.enabled` | `false` | connect the application to the admin portal |
| `asl.admin.client.queue-browse` | `false` | let the portal list queue entries |

## Core runtime

| Property | Default | Effect |
|---|---:|---|
| `asl.runtime.default-unavailable-message` | `Method is disabled` | Message used when a method has no explicit annotation/admin disable message. |
| `asl.runtime.max-concurrency-exceeded-message-template` | `Method reached max concurrency: %d` | Rejection template; `%d` receives the active limit. |
| `asl.runtime.max-last-error-characters` | `2048` | Hard character cap for the last synchronous invocation error retained in memory and exposed to observation surfaces. |
| `asl.runtime.error-detail-policy` | `TYPE_ONLY` | `TYPE_ONLY` retains only the exception class. `TYPE_AND_MESSAGE` is opt-in and may expose sensitive message content. |
| `asl.runtime.unwrapped-governed-bean-policy` | `WARN` | Startup check for governed interfaces exposed as beans without their generated wrapper (for example interface and implementation in different modules). `WARN` logs, `FAIL` stops startup, `IGNORE` skips the check. |
| `asl.observability.enabled` | `true` | Registers ASL meters when Micrometer and a `MeterRegistry` are present. |
| `asl.observability.metric-prefix` | `asl` | Prefix for every ASL metric name. |
| `asl.observability.max-method-meters` | `2000` | Maximum governed method count allowed for per-method meters; startup fails above it. Also caps per-lane MapDB meters (extra lanes are logged and not exported). |
| `asl.observability.method-latency-enabled` | `false` | Publishes `asl.method.duration` Timer per method for synchronous governed calls. Off by default because it adds two clock reads per call. |

## Method start values

Module: core starter. Start values per method, applied on top of the annotation. Portal overrides are applied on top of these; removing an override returns the method to this value.

```yaml
asl:
  runtime:
    services:
      "[mail.service]":
        methods:
          "[publishAudit(java.lang.String,java.lang.String)]":
            enabled: true
            max-concurrency: 4
            rate-limit: 50
            execution-mode: ASYNC
            consumer-threads: 2
            unavailable-message: Temporarily unavailable
```

| Key | Default | Effect | Explained in |
|---|---|---|---|
| `.enabled` | `true` (annotation) | `false` rejects every call with `DISABLED`. | [Enabled](concepts.md#enabled) |
| `.max-concurrency` | unlimited (annotation) | Most calls running at once per replica; more are rejected with `CONCURRENCY_LIMIT`. | [Max concurrency](concepts.md#max-concurrency) |
| `.rate-limit` | `0` (none) | Most calls starting per second per replica. SYNC rejects with `RATE_LIMIT`; ASYNC queues and slows the consumers. Negative values stop startup. | [Rate limit](concepts.md#rate-limit) |
| `.execution-mode` | `SYNC` | `ASYNC` runs the method from the durable queue. Needs an async-capable method and the MapDB engine. | [SYNC and ASYNC](concepts.md#sync-and-async) |
| `.consumer-threads` | `1` for async-capable methods (annotation) | Most queued calls running at once per replica; `0` stores calls without running them. | [Consumers](concepts.md#consumers) |
| `.unavailable-message` | `asl.runtime.default-unavailable-message` | Text returned while the method is stopped. | [Unavailable message](concepts.md#unavailable-message) |

An unknown service or method key, or a key written without brackets, stops startup with a hint.

## Admin client

Module: admin client starter. Connects the application to the admin server; the application exposes no endpoint. See [Admin server and portal](admin-server.md).

| Property | Default | Effect |
|---|---:|---|
| `asl.admin.client.enabled` | `false` | Starts the heartbeat thread. |
| `asl.admin.client.server-url` | none | Base URL of the admin server. Required when enabled; startup fails without it. |
| `asl.admin.client.secret` | none | Shared HMAC secret, at least 16 bytes, same value as the server. Required when enabled. |
| `asl.admin.client.application` | `spring.application.name` | Application name shown in the server. Falls back to `application`. |
| `asl.admin.client.instance` | `HOSTNAME` env | Replica name. In Kubernetes this is the pod name; otherwise the host name. |
| `asl.admin.client.interval` | `15s` | Heartbeat interval until the server asks for another one (watch mode). |
| `asl.admin.client.max-backoff` | `60s` | Upper bound of the retry delay while the server is unreachable. Telemetry is dropped, not buffered. |
| `asl.admin.client.connect-timeout` | `2s` | TCP connect timeout. |
| `asl.admin.client.request-timeout` | `3s` | Timeout of one request. |
| `asl.admin.client.queue-browse` | `false` | Lets the server list queue entries. Entry summaries and error texts then leave the pod. |
| `asl.admin.client.browse-limit` | `50` | Maximum entries per browse command (1–500). |
| `asl.admin.client.max-error-chars` | `200` | Error texts sent to the server are cut to this length. |
| `asl.admin.client.max-summary-chars` | `200` | Payload summaries sent with browse answers are cut to this length (0–2000; `0` sends none). The summary is what `asl.async.mapdb.payload.summary-policy` stored: the payload type by default, a short text of the arguments with `CODEC`. The payload itself never leaves the pod. |

## Admin server

The admin server is not a Spring application. Settings come from, in increasing priority: built-in defaults, `asl-admin.properties` in the working directory (or `--config=<path>`), environment variables (`asl.admin.server.watch.idle-timeout` as `ASL_ADMIN_SERVER_WATCH_IDLE_TIMEOUT`), JVM system properties, and `--key=value` arguments (prefix optional). Durations: `500ms`, `15s`, `5m`, `1h`, `7d`. Sizes: `512KB`, `1MB` or bytes.

| Property | Default | Effect |
|---|---:|---|
| `asl.admin.server.secret` | env `ASL_ADMIN_SECRET` | Shared HMAC secret, same value as the clients. Startup fails when empty. |
| `asl.admin.server.port` | `8080` | Listening port (`0` picks a free one). |
| `asl.admin.server.bind-address` | `0.0.0.0` | Listening address. |
| `asl.admin.server.threads` | `2` | HTTP worker threads (1–64). Two are enough for hundreds of replicas; requests are short. |
| `asl.admin.server.interval` | `15s` | Heartbeat interval requested from applications nobody is watching. |
| `asl.admin.server.watch.interval` | `2s` | Interval requested while the application is open in the portal. |
| `asl.admin.server.watch.idle-timeout` | `60s` | Watch mode ends this long after the portal stops polling. |
| `asl.admin.server.instance.expire-after` | `3 × interval` | A replica without a heartbeat for this long is forgotten with its replica-level overrides and open commands. Must be greater than `interval`. |
| `asl.admin.server.instance.max-instances` | `2000` | Hard limit of tracked replicas; registration above it gets `503`. |
| `asl.admin.server.max-body-size` | `64KB` | Largest accepted request (`413` above it). |
| `asl.admin.server.signature-tolerance` | `60s` | Accepted clock difference between application and server. |
| `asl.admin.server.commands.ttl` | `5m` | Unanswered commands are reported as `EXPIRED`. |
| `asl.admin.server.commands.max-pending` | `32` | Open commands per replica (`429` above it). |
| `asl.admin.server.commands.max-results` | `50` | Completed command results kept per application (memory). |
| `asl.admin.server.storage.mode` | `FILE` | `FILE`: telemetry history, application-wide overrides and audit in one MapDB file. `MEMORY`: the same data on the heap; lost on restart while applications keep their current settings. No payload is stored in either mode. |
| `asl.admin.server.storage.path` | `data/asl-admin.db` | MapDB file (transactional). Use a ReadWriteOnce volume. |
| `asl.admin.server.storage.max-size` | `64MB` | When the file reaches it, no new history series are created; existing series keep rotating and overrides are always written. Minimum `2MB` (MapDB's smallest file). |
| `asl.admin.server.history.enabled` | `true` | Store the latest raw counters of each replica and method every step. |
| `asl.admin.server.history.resolution` | `1m` | One point per step (minimum `1s`). |
| `asl.admin.server.history.retention` | `1h` | Points per series = retention / resolution (1–100 000), kept as a ring overwritten in place. Changing resolution or retention clears the history. Plan about 150 bytes per point. |
| `asl.admin.server.history.max-series` | `2000` | A series is one replica × method. No new series beyond this; series without points for a whole retention are removed. |
| `asl.admin.server.audit.enabled` | `true` | Records override changes and queue commands (browse is not audited). |
| `asl.admin.server.audit.max-entries` | `1000` | Oldest entries are dropped above this. |
| `asl.admin.server.audit.retention` | `7d` | Older entries are removed every 10 minutes. |
| `asl.admin.server.security.mode` | `BASIC` | `BASIC`: users below. `PROXY_HEADER`: trust user/group headers from an authenticating reverse proxy (SSO). `NONE`: isolated demo only. |
| `asl.admin.server.security.users` | empty | `name:password:ROLE` entries separated by commas; password plain or `{sha256}<hex>`, without `,` or `:`. Roles: `VIEWER` reads, `OPERATOR` also changes. `ASL_ADMIN_USER` + `ASL_ADMIN_PASSWORD` add one `OPERATOR` without these restrictions. `BASIC` refuses to start without a user. |
| `asl.admin.server.security.proxy.user-header` | `X-Forwarded-User` | Header carrying the authenticated user in `PROXY_HEADER` mode. |
| `asl.admin.server.security.proxy.groups-header` | `X-Forwarded-Groups` | Comma or space separated groups. |
| `asl.admin.server.security.proxy.operator-group` | `asl-operator` | Members get `OPERATOR`; other authenticated users are `VIEWER`. |
| `asl.admin.server.security.allowed-origins` | empty | Extra origins allowed to send changes besides the server's own host. Changes from any other `Origin`/`Referer` get `403`. |

## Queue engine (MapDB)

Module: MapDB starter. Read at startup; changes need a restart.

### Store and lifecycle

| Property | Default | Effect |
|---|---:|---|
| `asl.async.mapdb.enabled` | `false` | Creates and attaches the MapDB async engine only when true. |
| `asl.async.mapdb.path` | `./data/asl-queue.db` | Database file path; the parent directory is created. |
| `asl.async.mapdb.codec` | `jackson-json` | `jackson-json` or `java-object-stream`; a custom `AsyncPayloadCodec` bean takes precedence. |
| `asl.async.mapdb.worker-shutdown-await-millis` | `10000` | Maximum wait per coordinator/worker/writer shutdown phase. |
| `asl.async.mapdb.registration-idle-sleep-millis` | `100` | Backoff used while registered lanes are not ready. |
| `asl.async.mapdb.empty-queue-sleep-millis` | `50` | Maximum coordinator idle polling interval. |
| `asl.async.mapdb.requeue-delay-millis` | `75` | Compatibility delay used for deferred work. |
| `asl.async.mapdb.recovered-in-progress-message` | `Recovered stale in-progress invocation after restart` | Error note attached when abandoned claims are recovered. |
| `asl.async.mapdb.transactions-enabled` | `true` | Enables commit/rollback durability and group commit. False logs a safety warning and is for disposable workloads only. |
| `asl.async.mapdb.memory-mapped-enabled` | `false` | Requests memory-mapped I/O when supported; disabled avoids platform-specific lock/unmap risk. |
| `asl.async.mapdb.corruption-policy` | `FAIL_FAST` | `FAIL_FAST` preserves the store for investigation; `ARCHIVE_AND_RESET` archives it and starts empty. |
| `asl.async.mapdb.reset-if-corrupt` | deprecated alias | Legacy alias for `corruption-policy`; do not use in new configuration. |

### Admission and capacity

| Property | Default | Effect |
|---|---:|---|
| `asl.async.mapdb.admission.max-total-entries` | `100000` | Global persisted record cap. |
| `asl.async.mapdb.admission.max-total-payload-bytes` | `536870912` | Global persisted payload-byte cap (512 MiB). |
| `asl.async.mapdb.admission.default-lane-max-entries` | `10000` | Per-method record cap without an override. |
| `asl.async.mapdb.admission.default-lane-max-payload-bytes` | `67108864` | Per-method payload cap without an override (64 MiB). |
| `asl.async.mapdb.admission.max-single-payload-bytes` | `1048576` | Maximum encoded payload size for one invocation (1 MiB). |
| `asl.async.mapdb.admission.max-concurrent-enqueues` | `64` | Bounds concurrent serialization plus durable admission, preventing request fan-out from retaining unbounded payload arrays. |
| `asl.async.mapdb.admission.overflow-policy` | `REJECT` | `REJECT` fails immediately; `BLOCK_WITH_TIMEOUT` waits for capacity. |
| `asl.async.mapdb.admission.enqueue-timeout-millis` | `0` | Wait budget for `BLOCK_WITH_TIMEOUT`; must be positive in that mode. |

**Per-method limits (lane override).** Give one method more (or less) room than the defaults above. Write both keys in quoted brackets.

```yaml
asl:
  async:
    mapdb:
      services:
        "[mail.service]":
          methods:
            "[publishAudit(java.lang.String,java.lang.String)]":
              max-entries: 20000
              max-payload-bytes: 134217728
```

### Writer

| Property | Default | Effect |
|---|---:|---|
| `asl.async.mapdb.writer.queue-capacity` | `4096` | Bounded command queue size; payload-bearing commands are additionally bounded by `max-concurrent-enqueues`. |
| `asl.async.mapdb.writer.commit-batch-size` | `64` | Maximum mutations grouped into one commit. |
| `asl.async.mapdb.writer.commit-batch-window-millis` | `2` | Maximum collection window for group commit; zero disables waiting. |
| `asl.async.mapdb.writer.operation-timeout-millis` | `30000` | Caller wait limit for a durable writer result; timeout reports an unknown outcome. |

### Workers

| Property | Default | Effect |
|---|---:|---|
| `asl.async.mapdb.workers.max-threads` | `max(2, min(availableProcessors, 32))` | Shared worker pool upper bound. Tune explicitly in containers. |
| `asl.async.mapdb.workers.task-queue-capacity` | `1024` | Bounded scheduler-to-worker task queue. |
| `asl.async.mapdb.workers.lease-duration-millis` | `60000` | Claim validity window. |
| `asl.async.mapdb.workers.lease-renewal-interval-millis` | `20000` | Renewal period; must be lower than lease duration. All active leases are renewed in one commit; in-progress invocations not owned by a live worker are returned to the queue once their lease expires. |
| `asl.async.mapdb.workers.drain-backlog-in-sync-mode` | `true` | After a lane is switched back to SYNC, keep consuming the already accepted backlog (needs consumer threads > 0). `false` leaves it queued until the lane is ASYNC again. |
| `management.health.aslmapdb.enabled` | `true` | Registers the `aslMapDb` Actuator health contributor when Actuator is present. |
| `asl.async.mapdb.workers.execution-timeout-millis` | `0` | Interrupts a business execution that runs longer than this and records a `TIMEOUT` failure (counts as an attempt). `0` disables it. Interruption is cooperative: code that ignores interrupts keeps its worker thread. |
| `asl.async.mapdb.workers.completion-timeout-millis` | `300000` | ASYNC `CompletableFuture` methods: how long a worker waits for the future the method returned. Past it the run fails with `AsyncCompletionTimeoutException`, category `TIMEOUT` (retried if attempts are left), and the method's future is cancelled. Must be positive. |
| `asl.async.mapdb.workers.result-wait-timeout-millis` | `600000` | ASYNC `CompletableFuture` methods: how long a caller's future may wait for the outcome. Past it the future fails with `AsyncResultUnavailableException(WAIT_TIMEOUT)`; the invocation still runs and its outcome goes to the `AsyncResultHandler`. Must be positive. |
| `asl.async.mapdb.workers.max-pending-results` | `10000` | ASYNC `CompletableFuture` methods: callers that may wait at the same time. Further calls are rejected with `AsyncAdmissionRejectedException(RESULT_CAPACITY)` before anything is queued. Must be positive. |

### Retry and idempotency

| Property | Default | Effect |
|---|---:|---|
| `asl.async.mapdb.retry.max-attempts` | `1` | Total business attempts including the first execution; default performs no automatic retry after a business failure. Interruptions do not consume attempts. |
| `asl.async.mapdb.retry.max-interruptions` | `3` | Times an execution interrupted by graceful shutdown, a crash or an expired lease is returned to the queue without consuming an attempt. The next interruption marks it `FAILED`, which stops a payload that repeatedly kills the process. |
| `asl.async.mapdb.retry.initial-backoff-millis` | `250` | First retry delay. |
| `asl.async.mapdb.retry.max-backoff-millis` | `30000` | Exponential backoff cap. |
| `asl.async.mapdb.retry.jitter-ratio` | `0.20` | Random ± ratio applied to backoff; range is 0.0-1.0. |
| `asl.async.mapdb.idempotency.retention-millis` | `86400000` | Completed-key tombstone retention (24 hours). |
| `asl.async.mapdb.idempotency.max-entries` | `100000` | Hard cap for active keys and retained tombstones. |
| `asl.async.mapdb.idempotency.max-key-characters` | `256` | Maximum idempotency key length. |

### Payload safety

| Property | Default | Effect |
|---|---:|---|
| `asl.async.mapdb.payload.max-summary-characters` | `512` | Stored/admin payload summary length cap. |
| `asl.async.mapdb.payload.max-error-characters` | `2048` | Stored error/type length cap. |
| `asl.async.mapdb.payload.summary-policy` | `TYPE_ONLY` | `NONE`, `TYPE_ONLY`, or `CODEC`; codec summaries may contain business data. |
| `asl.async.mapdb.payload.error-detail-policy` | `TYPE_ONLY` | `TYPE_ONLY` or `TYPE_AND_MESSAGE`; messages may contain sensitive values. |
| `asl.async.mapdb.payload.allowed-type-names` | empty | Additional exact Java type names accepted by codecs. |
| `asl.async.mapdb.payload.allowed-type-prefixes` | empty | Additional package prefixes; keep narrow to avoid deserialization exposure. |
| `asl.async.mapdb.payload.max-deserialization-depth` | `32` | Java object-filter depth cap. |
| `asl.async.mapdb.payload.max-deserialization-references` | `10000` | Java object-filter reference cap. |
| `asl.async.mapdb.payload.max-deserialization-array-length` | `100000` | Java object-filter array-length cap. |
| `asl.async.mapdb.payload.legacy-java-migration-enabled` | `false` | One-time opt-in to migrate legacy Java-serialized queue records; back up first and disable afterward. |

Built-in safe Jackson types include primitive wrappers, `String`, common number/UUID types, and `java.time.*`. Registered argument schemas are validated separately. An empty custom allowlist does not allow arbitrary application classes.

## Sizing rules

- **Real parallelism** of a queued method is the smallest of: consumer threads, max concurrency, and `workers.max-threads` (the shared pool).
- **Memory during admission** can reach `max-concurrent-enqueues × max-single-payload-bytes` (64 × 1 MiB by default). Keep it well below the heap.
- **Disk.** The global and per-method byte limits cover stored payloads, not MapDB's own overhead. Keep them below the volume size.
- **Writer timeout** means the caller cannot know whether the call was stored. Retry only with an idempotency key.
- **Unsafe trade-offs.** `transactions-enabled: false` and `corruption-policy: ARCHIVE_AND_RESET` are explicit availability-over-durability choices, not performance defaults.
