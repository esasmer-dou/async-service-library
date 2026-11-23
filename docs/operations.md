# Operations

**Language:** English | [Türkçe](../tr/docs/operations.md) · [Documentation home](../README.md#documentation)

What to check before production, what to monitor, and how ASL behaves when things go wrong.

## Safe defaults

ASL starts in its safest configuration. You opt in to everything else.

| Area | Default |
|---|---|
| Durable queue | off (`asl.async.mapdb.enabled=false`) |
| Admin client | off. When on, it needs a shared secret of at least 16 bytes and opens no port. |
| Admin portal | login required (`BASIC`), no TLS of its own: run it behind a TLS ingress |
| Queue size | bounded (100,000 entries, 512 MiB; 10,000 entries per method); excess calls are rejected |
| Retries | one attempt in total. Raise it only for idempotent methods. |
| Durability | MapDB transactions on. A damaged store stops startup (`FAIL_FAST`). |
| Error details | exception type only, no message; payload summaries hold the type only |
| Payload types | custom classes must be allowlisted or registered |

## Production checklist

- [ ] Queued (ASYNC) methods are idempotent. Delivery is at-least-once.
- [ ] The queue file is on a persistent volume that belongs to this replica (ReadWriteOnce), never shared.
- [ ] Queue limits fit the volume and the heap (see [Sizing rules](configuration.md#sizing-rules)).
- [ ] `asl.async.mapdb.workers.max-threads` fits the container's CPU limit.
- [ ] Graceful shutdown lasts at least `worker-shutdown-await-millis` (10 s) so running calls can finish.
- [ ] `asl.runtime.unwrapped-governed-bean-policy: FAIL`, so a missing wrapper cannot bypass governance.
- [ ] The admin portal is behind TLS and uses `BASIC` users with roles, or `PROXY_HEADER` with SSO.
- [ ] Alerts exist for the metrics marked *alert* below.

## Deployment limits

- **One owner per queue file.** A second process opening the same file fails with `MapDbStoreLockedException`. Network filesystems (NFS, SMB, EFS, Azure Files) may not enforce the lock; use a ReadWriteOnce volume per replica.
- **No cross-replica queue.** Each replica processes only what it accepted itself. Work that must be shared across replicas, or scaled by HPA on one logical queue, belongs on a message broker.
- **Limits are per replica.** Divide a global quota by the replica count.

## Metrics

Exported through Micrometer when a `MeterRegistry` is present (`asl.observability.enabled`, default on). Service and method are tags.

| Metric | Meaning | |
|---|---|---|
| `asl.method.success`, `.errors`, `.rejected` | calls by outcome | alert on a rising rejected rate |
| `asl.method.in.flight`, `.peak.in.flight` | calls running now / highest seen | |
| `asl.method.max.concurrency`, `.enabled`, `.async.mode`, `.consumer.threads` | current settings | |
| `asl.method.duration` | latency timer (enable with `asl.observability.method-latency-enabled`) | |
| `asl.mapdb.lane.pending`, `.in.progress`, `.retry.wait`, `.failed` | queue entries per method | alert on `failed` > 0 |
| `asl.mapdb.lane.oldest.pending.age` | age of the oldest waiting call (ms) | **alert**: better than queue depth |
| `asl.mapdb.engine.healthy`, `asl.mapdb.writer.accepting` | 1 = fine, 0 = the queue stopped after a storage failure | **alert** on 0; needs a restart |
| `asl.mapdb.execution.timeouts`, `.leases.reclaimed`, `.released.on.shutdown` | runs interrupted or recovered | |
| `asl.mapdb.writer.*`, `asl.mapdb.enqueue.*`, `asl.mapdb.worker.*` | writer, admission and worker internals | |

`asl.observability.max-method-meters` (2000) stops startup when there are more governed methods, so metric cardinality cannot grow by accident. Exported counters never reset; the portal's *Reset statistics* only changes what the portal shows.

## Health

With Spring Boot Actuator, the health contributor `aslMapDb` is:

- **DOWN** when the writer has stopped, the store failed, or the coordinator has not run for 60 s.
- **UP** otherwise. A queue backlog is shown as detail only, together with pending results, timeouts and rate-limited schedules.

Disable it with `management.health.aslmapdb.enabled=false`.

## Failure and recovery

| Event | What happens |
|---|---|
| Crash or kill while calls run | they return to the queue and run again after the restart (at-least-once); after `retry.max-interruptions` (3) they are marked `FAILED` |
| Graceful shutdown | running calls get `worker-shutdown-await-millis` to finish; the rest return to the queue |
| A run hangs | `workers.execution-timeout-millis` (off by default) interrupts it and records `TIMEOUT` |
| Stale worker after a long pause | its lease has expired, so its late result cannot overwrite the new owner's |
| Second process on the same file | refused with `MapDbStoreLockedException` |
| Damaged store | `FAIL_FAST` stops startup and keeps the file. `ARCHIVE_AND_RESET` moves the damaged files aside and starts empty: an explicit data-loss trade-off. |
| Disk full, permissions, I/O errors | treated as fatal, never as corruption |
| Admin server unreachable | applications keep their last settings; calls are not affected |

A nightly CI suite force-kills the sample application with pending, failed and running work and checks that nothing is lost (see [reports](../reports/README.md)).

## Kubernetes

- Set CPU and memory requests and limits; size `workers.max-threads` to the CPU limit.
- One ReadWriteOnce volume per replica for the queue (StatefulSet), or accept losing the queue with the pod.
- `terminationGracePeriodSeconds` longer than the shutdown wait.
- Run one admin server with a small ReadWriteOnce volume; see [Admin server and portal](admin-server.md#kubernetes).

## Release gates

```powershell
mvn -B -ntp clean verify                                          # tests and per-module coverage floor
.\scripts\run-api-compatibility-gate.ps1                          # public API vs the latest release tag
.\scripts\run-data-plane-performance-gate.ps1 -Threads 1,8,32,128 # JMH: governed vs direct calls
mvn -B -ntp -Psbom -DskipTests package                            # CycloneDX SBOM in target/bom.json|xml
```

CI runs on Windows and Linux with Temurin and Semeru (OpenJ9) Java 21. Pull requests run a dependency review (fails on high-severity advisories); Dependabot proposes updates weekly. The performance gate catches regressions. It does not claim zero overhead: a governed call always reads its policy and updates counters, but a successful SYNC call allocates nothing extra.
