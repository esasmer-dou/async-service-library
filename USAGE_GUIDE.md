# Async Service Library Usage Guide

**Language:** English | [Türkçe](tr/USAGE_GUIDE.md)

This guide covers integration and runtime behavior. Every external property, default, and omission behavior is listed in the [configuration reference](CONFIGURATION_REFERENCE.md).

## 1. Requirements and Scope

- Java 21 or newer.
- Spring Boot 3.3.x for starter-based integration.
- Maven 3.9.x for the documented build.
- One concrete Spring stereotype implementation per governed interface.
- Async runtime switching only for `void` methods marked `asyncCapable=true`.

ASL is an in-process governance library, not a distributed workflow engine. The MapDB backend is single-process and must not be shared by multiple pods.

## 2. Select the Runtime Footprint

Use the smallest starter that satisfies the application:

| Need | Add |
|---|---|
| Synchronous stop/throttle/metrics only | `asl-spring-boot-core-starter` |
| Persistent async queue | `asl-spring-boot-mapdb-starter` |
| Management from the central admin server | `asl-spring-boot-admin-client-starter` |
| All-in-one installation | `asl-spring-boot-starter` |

`asl-spring-boot-mapdb-starter` already includes the core starter. The admin client adds no web dependency and stays off until `asl.admin.client.enabled=true`. The all-in-one starter includes core, MapDB and the admin client.

There is no admin portal inside the application any more; `asl-spring-boot-admin-starter` was removed. Operators use the central admin server (section 8).

## 3. Configure GitHub Packages

Add the repository to `pom.xml`:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/esasmer-dou/async-service-library</url>
    </repository>
</repositories>
```

Create or update `~/.m2/settings.xml`:

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">
    <servers>
        <server>
            <id>github</id>
            <username>${env.GITHUB_ACTOR}</username>
            <password>${env.GITHUB_TOKEN}</password>
        </server>
    </servers>
</settings>
```

The token needs `read:packages`; private repository access also needs repository permission. Keep credentials in environment variables or CI secrets, never in `pom.xml` or source control.

## 4. Add Dependencies and the Processor

Example for core + MapDB + admin client:

```xml
<properties>
    <asl.version>YOUR_RELEASE</asl.version>
</properties>

<dependencies>
    <dependency>
        <groupId>com.reactor.asl</groupId>
        <artifactId>asl-spring-boot-mapdb-starter</artifactId>
        <version>${asl.version}</version>
    </dependency>
    <dependency>
        <groupId>com.reactor.asl</groupId>
        <artifactId>asl-spring-boot-admin-client-starter</artifactId>
        <version>${asl.version}</version>
    </dependency>
    <dependency>
        <groupId>com.reactor.asl</groupId>
        <artifactId>asl-annotations</artifactId>
        <version>${asl.version}</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>com.reactor.asl</groupId>
                        <artifactId>asl-processor</artifactId>
                        <version>${asl.version}</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

The processor produces the descriptor, governed wrapper, and Spring wrapping configuration during compilation. No runtime classpath scanning is used for invocation dispatch.

The Spring wrapping configuration is generated only when the governed interface and its `@Service`/`@Component` implementation are compiled in the same module. If the interface lives in a separate API module, declare the wrapper bean yourself:

```java
@Bean
@Primary
MailService mailService(MailServiceImpl implementation, GovernanceRegistry registry) {
    return MailServiceAsl.wrap(implementation, registry);
}
```

At startup ASL checks for governed interfaces exposed without their wrapper and logs a warning (`asl.runtime.unwrapped-governed-bean-policy=WARN`). Use `FAIL` in production so a missing wrapper cannot silently bypass governance.

## 5. Define and Implement a Service

```java
@GovernedService(id = "mail.service")
public interface MailService {
    @GovernedMethod(initialMaxConcurrency = 6)
    String send(String mailId);

    @GovernedMethod(
            asyncCapable = true,
            initialMaxConcurrency = 4,
            initialConsumerThreads = 2
    )
    void publishAudit(@AsyncIdempotencyKey String eventId, String mailId);

    @Excluded
    String localHealth();
}
```

```java
@Service
public class MailServiceImpl implements MailService {
    @Override
    public String send(String mailId) {
        return "sent:" + mailId;
    }

    @Override
    public void publishAudit(String eventId, String mailId) {
        // The side effect must be idempotent when retries are enabled.
    }

    @Override
    public String localHealth() {
        return "UP";
    }
}
```

Inject `MailService`, not the implementation. The generated `@Primary` wrapper delegates to the implementation.

### Annotation rules

- `@GovernedService.id` must be stable and unique.
- `@GovernedMethod.id`, when omitted, is the full Java signature such as `send(java.lang.String)`.
- `initialMaxConcurrency` must be positive; the annotation default is effectively unbounded.
- `initialConsumerThreads` must be zero or positive.
- `@AsyncIdempotencyKey` is optional, allowed once, and must annotate a `String` parameter of an async-capable method. A `null` or blank key means "no deduplication" in both SYNC and ASYNC mode.
- Inherited interface methods, generic methods, generic interfaces and interfaces nested in a class are supported. A nested `Outer.Inner` generates `Outer_InnerAsl` and `Outer_InnerAslGoverned`. Annotations on inherited methods are honoured only when the super-interface is compiled in the same module (they have source retention). Local interfaces (declared inside a method) are not visible to annotation processors and are not governed.
- Rejections throw `MethodUnavailableException` with `reason()` `DISABLED` or `CONCURRENCY_LIMIT` and without a stack trace, so throttling stays cheap under load.
- `@Excluded` bypasses governance and calls the delegate directly.
- The processor fails compilation when `asyncCapable=true` is used on a result-returning method.

## 6. Synchronous and Asynchronous Semantics

| Behavior | `SYNC` | `ASYNC` |
|---|---|---|
| Caller thread | Executes business code | Serializes and durably admits work |
| Return value | Returned normally | Only supported for `void` |
| Exception | Returned to caller | Stored as retry/failed state |
| Concurrency | `maxConcurrency` limits active calls | Limits active consumers for the lane |
| Durability | None added by ASL | MapDB commit completes before admission returns |

An async call returning from the wrapper means the invocation was durably accepted, not that the business action completed. Queue overflow, payload limits, writer saturation, timeout, or storage failure are reported immediately as an admission exception.

Delivery is at-least-once. Graceful shutdown, a crash or an expired lease interrupts an execution; the invocation returns to the queue without consuming a retry attempt, up to `retry.max-interruptions` times. A crash between the external side effect and queue completion can therefore execute the same invocation again. Use a stable `@AsyncIdempotencyKey` and enforce idempotency in the target database or external API.

## 7. Enable MapDB Safely

```yaml
asl:
  async:
    mapdb:
      enabled: true
      path: ./data/asl-queue.db
      codec: jackson-json
      transactions-enabled: true
      memory-mapped-enabled: false
      corruption-policy: FAIL_FAST
      admission:
        max-total-entries: 100000
        max-total-payload-bytes: 536870912
        default-lane-max-entries: 10000
        default-lane-max-payload-bytes: 67108864
        max-single-payload-bytes: 1048576
        max-concurrent-enqueues: 64
        overflow-policy: REJECT
      retry:
        max-attempts: 1
```

Production rules:

- Keep `transactions-enabled=true` for durable workloads.
- Keep `corruption-policy=FAIL_FAST` when preserving evidence and queue data is more important than automatic availability.
- `ARCHIVE_AND_RESET` archives the complete damaged store set and starts an empty queue. It is an explicit data-loss/availability trade-off.
- `BLOCK_WITH_TIMEOUT` requires a positive `enqueue-timeout-millis` and blocks caller threads; use it only with a bounded upstream request pool.
- Set lane overrides for high-volume methods instead of allowing one lane to consume the global budget.
- Put the queue on local durable storage or a pod-specific PVC. Do not use a shared RWX MapDB file.

Custom Jackson DTO packages must be allowlisted, or the method must use a registered `JacksonAsyncPayloadArgumentSchema`. Java object stream compatibility mode has allowlist and object-filter limits but is not the preferred long-lived schema format.

The MapDB starter reuses the host application's `ObjectMapper` when one exists. In a non-web application without an `ObjectMapper` bean, it creates a private module-aware `JsonMapper` for the ASL codec only; it does not publish that mapper into the application context.

## 8. Central Management

ASL has no admin endpoint inside the application. Runtime changes (start/stop, max concurrency, async mode, consumer threads) and queue operations go through the central admin server described below. Startup values come from `asl.runtime.services.*` (section 10). The in-application portal (`asl-spring-boot-admin-starter`, `/asl` and `/asl/api`) was removed; replace its `asl.admin.services.*` keys with `asl.runtime.services.*` and drop the other `asl.admin.*` keys except `asl.admin.client.*`.

## Central Admin Server for Multiple Replicas

One central admin server manages every application and replica. It works like a config server: it serves the web portal and relays settings and commands, and holds no business logic.

| Part | Where | Contains |
|---|---|---|
| `asl-admin-server` | One small separate process (`java -jar asl-admin-server-<version>-exec.jar`) | JDK built-in HTTP server and the portal page; in memory the connected replicas, their latest raw values and open commands; in one MapDB file the telemetry history, application-wide overrides and the audit trail. No web framework |
| `asl-spring-boot-admin-client-starter` | Every application | One daemon thread. No UI, no inbound endpoint, no extra dependency (JDK `HttpClient`) |

**How it talks.** Only the pod opens connections: `POST /asl/v1/register` once at startup, then `POST /asl/v1/beat` every interval. The body is a compact binary message (protocol module `asl-admin-protocol`, `java.base` only) signed with HMAC-SHA256 (`X-ASL-Auth` header, ±60 s clock window). The beat carries only the methods whose values changed; an idle beat is 10–15 bytes and the server answers `204` without a body. Settings and commands travel back in the reply. While someone has the application open in the portal, the server asks its replicas for a faster interval (2 s by default); when the portal is closed it returns to the normal interval (15 s).

**Who does what.** The server stores and relays; it does not compute. Every `history.resolution` step it writes the latest raw counters of each replica and method into a fixed ring in MapDB. The pod applies application-wide overrides and then replica-specific ones on top. The browser computes totals, rates, "replicas differ" markers and the trend lines from the stored points. Long-term metrics and alerts stay in Prometheus/Grafana.

**No payload leaves the pod.** The admin server stores telemetry counters only. Queue entries, their arguments and payloads stay in the replica's own MapDB queue; browse results are shown in the portal and never written to the server's store.

**Failure behavior.** If the server is unreachable the client backs off (up to 60 s), drops telemetry instead of buffering it, and the application keeps its last applied settings. Governed calls never wait on the admin connection.

**Settings model.** The server keeps only overrides that differ from the application's own `application.yml`: application-wide (persisted) or per replica (memory only, removed with the replica). A new replica receives the current overrides when it registers. Removing an override returns the method to its yml value. Limits stay per replica: `max-concurrency: 20` on three replicas allows 60 concurrent calls in total.

**Queue operations.** Each replica owns its MapDB queue, so clear / replay / delete / browse target one replica. Browsing sends entry summaries and error texts to the server, so it is off unless the replica sets `asl.admin.client.queue-browse=true`.

Application side:

```yaml
asl:
  admin:
    client:
      enabled: true
      server-url: http://asl-admin-server:8080
      secret: ${ASL_ADMIN_SECRET}
```

Server side, with environment variables only:

```bash
ASL_ADMIN_SECRET=... ASL_ADMIN_USER=admin ASL_ADMIN_PASSWORD=... \
./asl-admin-server.sh          # or: java @asl-admin-server.jvmopts -jar asl-admin-server-<version>-exec.jar
```

`asl-admin-server.sh`, `asl-admin-server.cmd` and `asl-admin-server.jvmopts` are in `asl-admin-server/src/main/dist`; keep them next to the jar. The options file holds the low-footprint JVM settings (`-Xmx16m -Xms4m`, Serial GC, C1-only JIT with a 12 MB code cache, 256 KB thread stacks, no perf data file) and the script also sets `MALLOC_ARENA_MAX=2`. An out-of-memory error stops the process (`-XX:+ExitOnOutOfMemoryError`) so Kubernetes restarts it; replicas re-register and application-wide overrides are reloaded from MapDB.

Heap follows the number of replica × method pairs the server tracks, not the request rate. Measured with 50 methods per replica (2026-11-22, Windows, JDK 21, heartbeats every 2 s, portal open on one application):

| Replicas × methods | Live heap after GC | `-Xmx` | Heartbeats/s | Server CPU | Heartbeat p99 |
|---|---:|---:|---:|---:|---:|
| 300 × 50 | ~7 MB | `16m` (default) | 150 | ~47 % of one core | 5 ms |
| 1,000 × 50 | ~19 MB | `48m` | 500 | ~66–71 % of one core | 5–8 ms |
| 1,000 × 50, 1 s heartbeats | — | `96m` | 1,000 | ~95 % of one core | 14 ms |

The default two HTTP threads handled every case. With `-Xmx16m` and 1,000 × 50 the heap runs out, so raise `-Xmx` before the fleet grows past about 300 × 50. CPU figures come from a shared workstation and are upper bounds.

Instead of environment variables the server can read an `asl-admin.properties` file (`--config=<path>` to use another location):

```properties
asl.admin.server.port=8080
asl.admin.server.storage.mode=FILE
asl.admin.server.storage.path=/data/asl-admin.db
asl.admin.server.storage.max-size=64MB
asl.admin.server.history.resolution=1m
asl.admin.server.history.retention=1h
asl.admin.server.history.max-series=2000
asl.admin.server.audit.max-entries=1000
asl.admin.server.audit.retention=7d
asl.admin.server.security.mode=BASIC
```

History is a fixed ring per replica and method (`retention / resolution` points, overwritten in place), so the file reaches a stable size and stays there. Plan roughly 150 bytes per point: 1,000 series × 60 points is about 9 MB; MapDB's smallest file is 2 MB. When `storage.max-size` is reached no new series are created and existing ones keep rotating. Changing `resolution` or `retention` starts the history afresh. Measured on the reference build with those options (Linux, JDK 21):

| Scenario | RSS | CPU |
|---|---:|---:|
| Idle, ready in about 0.6 s | ~59 MB | ~0 |
| Stress: 40 replicas × 50 methods, 1 s heartbeats, 2,000 history series written every 5 s, portal open | ~70 MB | ~10 % of one core |
| Same stress with plain `java -Xmx32m -jar` | ~101 MB | ~17 % of one core |

At the default 15 s heartbeat and 1 min history step the load is about fifteen times lower. The live heap stays around 6–7 MB in the stress case; the rest is the JVM itself (code, class metadata, shared class archive). The executable jar is about 17 MB, mostly MapDB and its libraries.

In Kubernetes run the server as a single-replica Deployment with a small ReadWriteOnce volume (or `storage.mode=MEMORY`, which keeps the same data on the heap, and no volume), expose the portal through your ingress with TLS, and keep `/asl/v1/*` reachable only from inside the cluster. The server speaks plain HTTP; TLS belongs to the ingress.

## 9. Admin Server Portal API

The portal page calls a small JSON API under `/asl-admin/api`. Scripts can use the same API; it sits behind the portal login (`security.mode`). Mutating calls need the operator role and are rejected when they carry a foreign `Origin`.

| Endpoint | Purpose |
|---|---|
| `GET /server` | Server counters: applications, instances, storage size, history series |
| `GET /apps` | Connected applications |
| `GET /apps/{app}` | Methods, replicas with their latest raw counters, overrides and command results; also marks the application as watched (faster heartbeats) |
| `GET /apps/{app}/history?serviceId=&methodId=` | Stored telemetry points per replica |
| `POST /apps/{app}/overrides` | Form: `serviceId`, `methodId`, optional `instance`, `enabled`, `async`, `maxConcurrency`, `consumerThreads` |
| `DELETE /apps/{app}/overrides?serviceId=&methodId=[&instance=]` | Remove an override; the method returns to its yml value |
| `POST /apps/{app}/commands` | Form: `type` (`BROWSE_QUEUE`, `REPLAY_ENTRY`, `DELETE_ENTRY`, `CLEAR_QUEUE`, `RESET_OBSERVATIONS`), `serviceId`, `methodId`, optional `instance`, `entryId`, `limit`; returns `commandIds` |
| `GET /audit?limit=100` | Recent audit entries |

A replica picks up overrides and commands with its next heartbeat. A replica has applied the current settings when its `appliedConfigVersion` equals the application's `configVersion` (`inSync` is true). Command results appear in the `commands` list of `GET /apps/{app}`.

## 10. Configuration Precedence

1. Overrides from the central admin server while the replica is connected: replica-specific over application-wide.
2. `asl.runtime.services.<service>.methods.<signature>.*` startup overrides (core starter).
3. Annotation values.
4. Library defaults.

MapDB capacity, codec, writer, worker, retry, payload, and corruption settings are startup settings. Changing YAML requires restart. Application-wide overrides are applied by every replica of the application, including replicas that start later; replica-specific overrides only by that replica.

If the application defines no `asl.*` properties:

- Generated wrappers still enforce annotation defaults.
- The admin client and MapDB remain disabled.
- No Web/Thymeleaf dependency is present when only the core starter is used.
- Micrometer meters are registered only when a `MeterRegistry` is present.

See [CONFIGURATION_REFERENCE.md](CONFIGURATION_REFERENCE.md) for every property and default.

## 11. Metrics

With Micrometer present and `asl.observability.enabled=true`, ASL exports:

- `asl.method.success`, `errors`, `rejected`.
- Observation reset (the `RESET_OBSERVATIONS` command) changes the portal window only; exported counters remain monotonic for correct rate calculations.
- `asl.method.in.flight`, `peak.in.flight`, `max.concurrency`, `enabled`, `async.mode`, `consumer.threads`.
- `asl.mapdb.writer.*` command, queue, and commit metrics.
- `asl.mapdb.enqueue.active` and `asl.mapdb.enqueue.max`.
- `asl.mapdb.worker.*` health, failure, and scheduling metrics.
- `asl.mapdb.projection.version`.
- `asl.mapdb.lane.pending`, `.lane.in.progress`, `.lane.retry.wait`, `.lane.failed` and `.lane.oldest.pending.age` (ms) per lane; alert on queue age rather than depth.
- `asl.mapdb.execution.timeouts`, `asl.mapdb.leases.reclaimed`, `asl.mapdb.released.on.shutdown`.
- `asl.method.duration` Timer when `asl.observability.method-latency-enabled=true`.
- With Spring Boot Actuator, health contributor `aslMapDb` is DOWN when the writer stopped, the store failed or the coordinator heartbeat is older than 60 s; queue backlog is reported as detail only. Disable with `management.health.aslmapdb.enabled=false`.
- `asl.mapdb.engine.healthy` (1/0) and `asl.mapdb.writer.accepting` (1/0). Alert when either is `0`: the engine stopped after a fatal storage failure and needs a restart.

Service and method are metric tags. `asl.observability.max-method-meters` fails startup when the governed method count exceeds its cap, preventing accidental cardinality growth.

## 12. Failure and Recovery Model

- A second process opening the same queue receives `MapDbStoreLockedException`; ASL does not silently open another file.
- Permission, lock, unsupported format, disk, and generic I/O failures are fatal and never classified as corruption reset candidates.
- Validated header corruption produces `MapDbStoreCorruptionException` under `FAIL_FAST`.
- Under `ARCHIVE_AND_RESET`, the database and companion WAL files are moved as one rollback-protected set before an empty store starts.
- Claimed work uses lease tokens. Stale completion/failure writes cannot overwrite a newer owner.
- Restarted in-progress records return to pending state and may execute again.
- Clear/delete operations do not remove in-progress records.

The destructive nightly suite force-kills the sample during pending, failed, and in-progress states. Filesystem loss, disk-full behavior, and storage hardware guarantees remain deployment responsibilities.

## 13. Build and Release Gates

```powershell
mvn -B -ntp clean verify
.\scripts\run-api-compatibility-gate.ps1
.\scripts\run-data-plane-performance-gate.ps1 -Threads 1,8,32,128
mvn -B -ntp -Psbom -DskipTests package
```

- JMH compares direct, excluded, governed, rejected, and exception paths.
- The API gate compares public annotation/core/MapDB binaries with the latest reachable release tag.
- CycloneDX aggregate SBOM files are written to `target/bom.json` and `target/bom.xml`.
- CI covers Windows/Linux and Temurin/Semeru Java 21.
- `mvn verify` enforces a per-module line-coverage floor from each module's own tests (`jacoco.line.minimum`); the aggregate report is in `asl-coverage`.
- Pull requests run GitHub dependency review (fails on high-severity advisories); Dependabot proposes weekly Maven and Actions updates.

Performance gates protect against regression; they do not prove zero overhead. Successful governed calls necessarily perform policy reads and counters. The target is bounded, measured overhead with no extra allocation on successful sync paths.

## 14. Kubernetes Checklist

- Use explicit CPU and memory requests/limits.
- Tune worker threads to workload type and CPU quota.
- Keep queue limits below PVC and memory budgets.
- Use one MapDB owner per file.
- Use graceful termination long enough for worker shutdown.
- Alert on writer failures, rejected admissions, queue depth, lease health, and commit latency.
- Use an external broker when HPA replicas must consume one shared logical queue.
- Manage multiple replicas from the central admin server, not from per-pod portals; give the server one small ReadWriteOnce volume.

## 15. Runnable Reference

The [consumer sample](asl-consumer-sample/README.md) includes real service endpoints, backlog/failure/replay scenarios, and crash-recovery tests driven through the central admin server.
