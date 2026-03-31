# Async Service Library

Async Service Library (ASL) gives Java and Spring Boot applications a controlled way to run, pause, throttle, queue, and observe service methods.

It sits between plain in-process service calls and a full external workflow platform: lightweight enough to embed in an application, but structured enough to operate from a control plane.

## Core Capabilities

- compile-time generated wrappers instead of reflection-heavy interception
- runtime method stop/start and concurrency limits
- queue-backed async execution for `void` methods
- replay, delete, clear, and consumer-thread controls
- Spring Boot admin UI at `/asl`
- REST control plane at `/asl/api`

## Good Fit

ASL works well when you need to:

- throttle or pause specific service methods without redeploying
- turn selected `void` methods into managed async lanes
- inspect queue depth, failed entries, and pressure from a single admin surface
- keep async behavior inside the application boundary

## Current Boundaries

- runtime async switching is supported for `void` methods only
- one Spring stereotype implementation is expected per governed interface
- admin security is not auto-configured by the library

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

- `asl-annotations`
- `asl-core`
- `asl-processor`
- `asl-mapdb`
- `asl-spring-boot-starter`

Sample and validation modules are also included for reference and verification:

- `asl-sample`
- `asl-consumer-sample`
- `asl-coverage`

## Start Here

- usage guide: [USAGE_GUIDE.md](E:\ReactorRepository\async-service-library\USAGE_GUIDE.md)
- Spring Boot sample: [asl-consumer-sample/README.md](E:\ReactorRepository\async-service-library\asl-consumer-sample\README.md)
- Turkish docs: [tr/README.md](E:\ReactorRepository\async-service-library\tr\README.md)

## Distribution

- GitHub repository: [github.com/esasmer-dou/async-service-library](https://github.com/esasmer-dou/async-service-library)
- GitHub Packages: [maven.pkg.github.com/esasmer-dou/async-service-library](https://maven.pkg.github.com/esasmer-dou/async-service-library)
- Releases: [github.com/esasmer-dou/async-service-library/releases](https://github.com/esasmer-dou/async-service-library/releases)

## Build

```powershell
mvn verify
```
