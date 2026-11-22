# Async Service Library

**Language:** English | [Türkçe](tr/README.md)

Async Service Library (ASL) is an in-process governance layer for Java 21 and Spring Boot service methods. It generates wrappers at compile time and lets operators stop, throttle, queue, retry, and observe selected methods without reflection-based dispatch or a separate workflow platform.

ASL is designed around explicit limits. Async admission, payload size, writer backlog, worker execution, retry state, idempotency retention, and admin access are bounded or fail-closed by default.

## What It Provides

- Compile-time generated service wrappers.
- Runtime start/stop and max-concurrency control.
- Persistent async execution for explicitly marked `void` methods.
- Bounded MapDB queue with FIFO indexes, leases, retries, idempotency, and durable acknowledgements.
- Micrometer metrics with a configurable cardinality cap.
- Optional AJAX control plane with pagination, ETag polling, and audit hooks.
- JMH, API compatibility, SBOM, JVM/OS matrix, and destructive recovery gates.

## Choose Only the Modules You Need

| Requirement | Artifact | Pulls MapDB | Pulls Web/Thymeleaf |
|---|---|---:|---:|
| Synchronous governance only | `asl-spring-boot-core-starter` | No | No |
| Persistent async lanes | `asl-spring-boot-mapdb-starter` | Yes | No |
| Admin UI and REST control plane inside the application | `asl-spring-boot-admin-starter` | No | Yes |
| Connect the application to the central admin server | `asl-spring-boot-admin-client-starter` | No | No |
| Central portal for every application and replica (separate jar, no web framework) | `asl-admin-server` | Yes (telemetry history) | No |
| Backward-compatible all-in-one setup | `asl-spring-boot-starter` | Yes | Yes |

The annotation processor remains a compile-time dependency. Applications that only need synchronous governance no longer pay for MapDB, Spring MVC, or Thymeleaf.

## Minimal Integration

```xml
<properties>
    <asl.version>YOUR_RELEASE</asl.version>
</properties>

<dependency>
    <groupId>com.reactor.asl</groupId>
    <artifactId>asl-spring-boot-core-starter</artifactId>
    <version>${asl.version}</version>
</dependency>
<dependency>
    <groupId>com.reactor.asl</groupId>
    <artifactId>asl-annotations</artifactId>
    <version>${asl.version}</version>
</dependency>
```

Configure `asl-processor` under `maven-compiler-plugin.annotationProcessorPaths`, then define the governed contract:

```java
@GovernedService(id = "mail.service")
public interface MailService {
    @GovernedMethod(initialMaxConcurrency = 4)
    String send(String payload);

    @GovernedMethod(asyncCapable = true, initialConsumerThreads = 2)
    void publish(@AsyncIdempotencyKey String eventId, String payload);
}
```

Result-returning methods stay synchronous because queueing would break their return contract. Async switching is available only for `void` methods explicitly marked `asyncCapable=true`.

## Production Defaults

- Admin is disabled by default.
- Enabling admin requires an `AslAdminAccessPolicy` bean. Anonymous access must be explicitly enabled and is intended only for isolated demos.
- MapDB is disabled by default and corruption policy is `FAIL_FAST`.
- Queue and payload limits reject excess work by default instead of growing without bound.
- MapDB transactions are enabled; disabling them weakens rollback and crash-durability guarantees and logs a warning.
- Payload summaries and synchronous/async errors are redacted to type-only output by default and retained under explicit length caps.
- Custom Jackson/Java payload types must be allowlisted or registered with an explicit schema.
- Retry count defaults to one total attempt. Increasing retries requires idempotent business behavior.

## Deployment Boundary

The MapDB engine is process-local. Use one application replica with a local/PVC-backed queue, or give every replica an independent queue. Never mount the same MapDB file into multiple pods. The single-owner `.asl.lock` uses an OS file lock, which network filesystems (NFS, SMB, ReadWriteMany volumes such as EFS or Azure Files) may not enforce reliably; use a ReadWriteOnce volume per replica. Cluster-wide delivery requires an external broker adapter such as Kafka, RabbitMQ, or Redis Streams.

With several replicas, manage them from one place with the central admin server: each replica runs `asl-spring-boot-admin-client-starter` (no UI and no inbound endpoint on the pod) and opens the only connection itself. See [Central admin server](USAGE_GUIDE.md#central-admin-server-for-multiple-replicas).

Async delivery is at-least-once. An execution interrupted by graceful shutdown, a crash or an expired lease returns to the queue without consuming a retry attempt (bounded by `retry.max-interruptions`). A process can fail after the business side effect but before durable completion, so consumers must be idempotent. `@AsyncIdempotencyKey` prevents duplicate queue admissions; it cannot make an external side effect transactional.

## Documentation

- Usage guide: [English](USAGE_GUIDE.md) | [Türkçe](tr/USAGE_GUIDE.md)
- Complete configuration reference: [English](CONFIGURATION_REFERENCE.md) | [Türkçe](tr/CONFIGURATION_REFERENCE.md)
- Runnable sample: [English](asl-consumer-sample/README.md) | [Türkçe](tr/asl-consumer-sample/README.md)
- Recovery and abuse tests: [English](asl-consumer-sample/CHAOS_TEST_GUIDE.md) | [Türkçe](tr/asl-consumer-sample/CHAOS_TEST_GUIDE.md)
- Benchmark reports: [English](reports/README.md) | [Türkçe](tr/reports/README.md)

## Verification

```powershell
mvn -B -ntp clean verify
.\scripts\run-api-compatibility-gate.ps1
.\scripts\run-data-plane-performance-gate.ps1 -Threads 1,8,32,128
mvn -B -ntp -Psbom -DskipTests package
```

The release workflow publishes source/Javadoc artifacts and CycloneDX JSON/XML SBOMs. CI checks Temurin and Semeru/OpenJ9 on Windows and Linux.

## Distribution

- [GitHub repository](https://github.com/esasmer-dou/async-service-library)
- [GitHub Packages](https://maven.pkg.github.com/esasmer-dou/async-service-library)
- [Releases](https://github.com/esasmer-dou/async-service-library/releases)

GitHub Packages authentication and complete Maven setup are documented in the [usage guide](USAGE_GUIDE.md).
