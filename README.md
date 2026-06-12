# Async Service Library

**Language:** English | [Turkish](tr/README.md)

Async Service Library (ASL) gives Java and Spring Boot applications a controlled way to run, pause, throttle, queue, and observe service methods at runtime.

ASL is not a workflow engine. It is a lightweight in-process control layer for service methods that need operational guardrails without introducing a separate orchestration platform.

## Core Capabilities

- Compile-time generated wrappers instead of reflection-heavy dispatch.
- Runtime method start/stop and max-concurrency control.
- Queue-backed async execution for selected `void` methods.
- Replay, delete, clear, and consumer-thread controls for queued work.
- Spring Boot admin UI at `/asl`.
- REST control plane at `/asl/api`.

## When ASL Fits

Use ASL when you need to:

- Pause or throttle specific service methods without redeploying.
- Protect expensive or failure-prone integrations with runtime limits.
- Turn selected `void` methods into managed async lanes.
- Inspect queue depth, failed entries, in-progress work, and pressure from one admin surface.
- Keep async behavior inside the application boundary before adopting a heavier workflow platform.

## Current Boundaries

- Runtime `SYNC -> ASYNC` switching is supported for `void` methods only.
- Result-returning methods can be governed, stopped, and throttled, but they must remain synchronous.
- One Spring stereotype implementation is expected per governed interface.
- Admin security is not auto-configured. Protect `/asl` and `/asl/api` with your application security configuration in production.

## Quick Example

```java
@GovernedService(id = "mail.service")
public interface MailService {
    @GovernedMethod(initialMaxConcurrency = 4)
    String send(String payload);

    @GovernedMethod(asyncCapable = true, initialMaxConcurrency = 2, initialConsumerThreads = 0)
    void publish(String event);
}
```

```java
@Service
public class MailServiceImpl implements MailService {
    @Override
    public String send(String payload) {
        return "sent:" + payload;
    }

    @Override
    public void publish(String event) {
        // Work that is safe to queue and consume later.
    }
}
```

```yaml
asl:
  admin:
    enabled: true
    path: /asl
    api-path: /asl/api
  async:
    mapdb:
      enabled: true
      path: ./data/asl-queue.db
      codec: jackson-json
```

## Modules

- `asl-annotations`: Public annotations.
- `asl-core`: Runtime governance model.
- `asl-processor`: Compile-time wrapper generation.
- `asl-mapdb`: Persistent async queue engine.
- `asl-spring-boot-starter`: Spring Boot auto-configuration, admin UI, and REST control plane.

Sample and validation modules:

- `asl-sample`
- `asl-consumer-sample`
- `asl-coverage`

## Start Here

- Project overview: English | [Turkish](tr/README.md)
- Usage guide: [English](USAGE_GUIDE.md) | [Turkish](tr/USAGE_GUIDE.md)
- Spring Boot sample: [English](asl-consumer-sample/README.md) | [Turkish](tr/asl-consumer-sample/README.md)
- Benchmark reports: [English](reports/README.md) | [Turkish](tr/reports/README.md)

The usage guide includes dependency setup, GitHub Packages configuration, annotation processor setup, runtime configuration, every overrideable property, default values, and omitted-property behavior.

## Distribution

- GitHub repository: [github.com/esasmer-dou/async-service-library](https://github.com/esasmer-dou/async-service-library)
- GitHub Packages: [maven.pkg.github.com/esasmer-dou/async-service-library](https://maven.pkg.github.com/esasmer-dou/async-service-library)
- Releases: [github.com/esasmer-dou/async-service-library/releases](https://github.com/esasmer-dou/async-service-library/releases)

## Build

```powershell
mvn verify
```
