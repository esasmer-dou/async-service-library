# Async Service Library

ASL is a compile-time governed service library for Java microservices.

It provides:

- annotation-driven service governance
- no runtime reflection dispatch
- no dynamic proxy chain
- generated wrappers via annotation processing
- runtime enable/disable and concurrency control
- persistent async queueing for `void` methods
- pluggable async payload codec support
- Spring Boot auto-wrap for governed beans
- admin UI and REST control plane

## Current State

The repository is currently usable for internal rollout and controlled production integration.

Ready now:

- governed wrappers
- Spring auto-wrap
- runtime throttling and stop/start
- MapDB-backed async queue
- pluggable async payload codec SPI with default Java object stream codec
- built-in Jackson JSON codec option for Spring Boot environments
- method-specific payload schema registry and migration hooks for Jackson codec
- replay, delete, clear, consumer resize
- admin UI at `/asl`
- admin REST at `/asl/api`
- summary snapshot at `/asl/api/summary`

Intentional current limits:

- runtime async switching is only supported for `void` methods
- one Spring stereotype implementation per governed interface
- admin security is not auto-configured by the library

## Modules

- `asl-annotations`
- `asl-core`
- `asl-processor`
- `asl-mapdb`
- `asl-spring-boot-starter`
- `asl-sample`
- `asl-consumer-sample`
- `asl-coverage`

## Quick Start

Annotate the service interface:

```java
@GovernedService(id = "mail.service")
public interface MailService {
    @GovernedMethod(initialMaxConcurrency = 4, unavailableMessage = "mail lane closed")
    String send(String payload);

    @GovernedMethod(asyncCapable = true, initialMaxConcurrency = 2, initialConsumerThreads = 0)
    void publish(String event);

    @Excluded
    String health();
}
```

Provide one Spring stereotype implementation:

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

    @Override
    public String health() {
        return "UP";
    }
}
```

Enable the starter and processor in Maven:

```xml
<dependency>
    <groupId>com.reactor.asl</groupId>
    <artifactId>asl-spring-boot-starter</artifactId>
    <version>${project.version}</version>
</dependency>

<dependency>
    <groupId>com.reactor.asl</groupId>
    <artifactId>asl-annotations</artifactId>
    <version>${project.version}</version>
</dependency>
```

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>com.reactor.asl</groupId>
                <artifactId>asl-processor</artifactId>
                <version>${project.version}</version>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

Enable admin and async queue:

```yaml
asl:
  runtime:
    default-unavailable-message: Method is disabled
    max-concurrency-exceeded-message-template: "Method reached max concurrency: %d"
  admin:
    enabled: true
    path: /asl
    api-path: /asl/api
    buffer-preview-limit: 50
    dashboard:
      attention-limit: 8
      medium-utilization-percent: 40
      high-utilization-percent: 80
      refresh:
        live-refresh-enabled: true
        live-buffer-enabled: true
        default-interval-ms: 5000
        interval-options-ms: [3000, 5000, 10000, 30000]
        change-flash-ms: 1400
        success-message-auto-hide-ms: 1600
        error-message-auto-hide-ms: 3200
  async:
    mapdb:
      enabled: true
      path: ./data/asl-queue.db
      codec: jackson-json
      worker-shutdown-await-millis: 10000
      registration-idle-sleep-millis: 100
      empty-queue-sleep-millis: 50
      requeue-delay-millis: 75
      recovered-in-progress-message: Recovered stale in-progress invocation after restart
      transactions-enabled: true
      memory-mapped-enabled: false
      reset-if-corrupt: true
```

These are now fully externalized runtime knobs. The library still ships opinionated defaults, but queue worker pacing, startup recovery behavior, admin dashboard thresholds, live-refresh timings, and default runtime messages can all be overridden through application properties.

## Sample Projects

- Minimal generated-wrapper sample: [asl-sample](E:\ReactorRepository\async-service-library\asl-sample)
- Spring Boot consumer sample: [asl-consumer-sample](E:\ReactorRepository\async-service-library\asl-consumer-sample)

The consumer sample includes:

- governed interface
- Spring implementation
- controller usage
- starter-based auto-wrap
- admin plane configuration

## Documentation

- Full usage guide: [USAGE_GUIDE.md](E:\ReactorRepository\async-service-library\USAGE_GUIDE.md)
- Turkish documentation index: [tr/README.md](E:\ReactorRepository\async-service-library\tr\README.md)
- Sample application guide: [asl-consumer-sample/README.md](E:\ReactorRepository\async-service-library\asl-consumer-sample\README.md)

## Public Distribution

Public repository:

- [github.com/esasmer-dou/async-service-library](https://github.com/esasmer-dou/async-service-library)

Packaging and release flow:

- `CI` verifies pushes and pull requests
- `Publish Package` deploys Maven artifacts to GitHub Packages
- `Release` publishes versioned Maven artifacts and attaches release assets on `v*` tags

GitHub Packages repository:

- `https://maven.pkg.github.com/esasmer-dou/async-service-library`

## Build

Run all tests:

```powershell
mvn verify
```

Coverage report:

- [jacoco aggregate](E:\ReactorRepository\async-service-library\asl-coverage\target\site\jacoco-aggregate\index.html)
