# Async Service Library Usage Guide

## 1. Current Status

ASL is currently usable for real integration work.

What is ready:

- Compile-time governed wrapper generation
- Runtime enable/disable
- Runtime concurrency control
- Async queue dispatch for `void` methods
- Persistent async queue with MapDB
- Pluggable async payload codec SPI
- Replay, delete, clear, consumer thread resize
- Spring Boot admin UI and REST control plane
- Spring auto-wrapping for governed beans without manual `wrap(...)`

What is intentionally limited in the current version:

- Runtime `SYNC -> ASYNC` switching is supported only for `void` methods
- Non-`void` methods can be governed, but they must stay synchronous
- Spring auto-wrap currently expects a single Spring stereotype implementation per governed interface
- The implementation bean still exists in the Spring context; the governed wrapper is exposed as the `@Primary` bean
- There is no published Maven Central distribution yet in this repository state; use it as a workspace module or internal artifact first

## 2. Concept

ASL is not a proxy-heavy AOP library.

The model is:

1. You annotate a service interface.
2. An annotation processor generates a governed wrapper at compile time.
3. Calls go through generated direct Java code.
4. Runtime control happens through `GovernanceRegistry` and `MethodRuntime`.
5. If Spring Boot starter is present, governed service beans are auto-wrapped inside the application context.

This keeps the fast path simple:

- no reflection dispatch
- no dynamic proxy chain
- no runtime annotation scanning on each call
- no per-call policy parsing

## 3. Modules

- `asl-annotations`: public annotations
- `asl-core`: runtime governance model
- `asl-processor`: compile-time source generation
- `asl-mapdb`: persistent async queue engine
- `asl-spring-boot-starter`: Spring Boot auto-config, admin UI, admin REST
- `asl-sample`: reference usage

## Step-by-Step: Use ASL In Your Own Spring Boot Project

If you want to add ASL to an existing Spring Boot service, follow this order.

### 1. Add the dependencies

Add the starter and annotations to your application module:

```xml
<dependencies>
    <dependency>
        <groupId>com.reactor.asl</groupId>
        <artifactId>asl-spring-boot-starter</artifactId>
        <version>0.1.0</version>
    </dependency>
    <dependency>
        <groupId>com.reactor.asl</groupId>
        <artifactId>asl-annotations</artifactId>
        <version>0.1.0</version>
    </dependency>
</dependencies>
```

If you build against the modules from the same workspace, you can keep using `${project.version}` instead of a fixed version.

### 2. Add the annotation processor

ASL generates wrappers at compile time, so your compiler must run `asl-processor`.

```xml
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
                        <version>0.1.0</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

### 3. Put `@GovernedService` on the interface

Annotate the service interface, not the implementation.

```java
@GovernedService(id = "mail.service")
public interface MailService {
    @GovernedMethod(initialMaxConcurrency = 4, unavailableMessage = "mail lane closed")
    String send(String payload);

    @GovernedMethod(asyncCapable = true, initialConsumerThreads = 0, initialMaxConcurrency = 2)
    void publishAudit(String event);

    @Excluded
    String health();
}
```

### 4. Implement the interface normally

Your business code stays in the implementation.

```java
@Service
public class MailServiceImpl implements MailService {
    @Override
    public String send(String payload) {
        return "sent:" + payload;
    }

    @Override
    public void publishAudit(String event) {
        // background-friendly work
    }

    @Override
    public String health() {
        return "UP";
    }
}
```

### 5. Inject the interface type in the rest of the app

Inject `MailService`, not the generated wrapper class.

```java
@RestController
@RequestMapping("/api/mails")
public class MailController {
    private final MailService mailService;

    public MailController(MailService mailService) {
        this.mailService = mailService;
    }

    @PostMapping("/{id}/publish-audit")
    public void publishAudit(@PathVariable String id) {
        mailService.publishAudit(id);
    }
}
```

At runtime, Spring injects the governed wrapper as the primary bean.

### 6. Turn on the admin plane

Add the minimal ASL properties:

```yaml
asl:
  admin:
    enabled: true
    path: /asl
    api-path: /asl/api
```

This gives you:

- admin UI at `/asl`
- admin REST at `/asl/api`

### 7. Turn on the async queue if you want runtime async lanes

If you want `asyncCapable = true` methods to be switchable to `ASYNC`, enable MapDB:

```yaml
asl:
  async:
    mapdb:
      enabled: true
      path: ./data/asl-queue.db
      codec: jackson-json
      transactions-enabled: true
      memory-mapped-enabled: false
      reset-if-corrupt: true
```

Without an async engine, `ASYNC` mode is not usable.

### 8. Start the application and compile once

Run a normal build first so the generated wrappers are produced:

```powershell
mvn clean package
```

Then start the application:

```powershell
mvn spring-boot:run
```

### 9. Open the control plane

After startup:

- open `http://localhost:8080/asl`
- verify your service appears in the Services list
- open the method detail panel

### 10. Use async mode the intended way

For an async-capable `void` method:

1. set execution mode to `ASYNC`
2. keep `consumerThreads = 0` if you want to accumulate backlog first
3. raise `consumerThreads` when you want the queue to drain
4. inspect failed entries in the buffer if execution fails
5. replay or delete failed entries from the admin UI or REST API

### 11. Keep these rules in mind

- only `void` methods are runtime-async-switchable
- result-returning methods must stay synchronous
- use `@Excluded` for health and always-open utility methods
- define explicit service and method ids if operators or external systems will target them
- protect `/asl` and `/asl/api` with your own security configuration in production

## 4. Public Annotations

### `@GovernedService`

Put this on the service interface.

```java
@GovernedService(id = "payment.service")
public interface PaymentService {
    Receipt pay(PaymentRequest request);
}
```

Rules:

- Must be placed on an interface
- Compile-time only
- If `id` is omitted, the interface fully qualified name becomes the service id

### `@GovernedMethod`

Put this on interface methods that need explicit governance settings.

Fields:

- `id`: explicit method id
- `initiallyEnabled`: initial availability
- `initialMaxConcurrency`: initial concurrency ceiling
- `unavailableMessage`: returned when method is unavailable
- `asyncCapable`: enables queue-based async mode
- `initialConsumerThreads`: initial worker count for async queue consumption

Example:

```java
@GovernedMethod(
    initialMaxConcurrency = 8,
    unavailableMessage = "payment lane closed"
)
Receipt pay(PaymentRequest request);
```

Async example:

```java
@GovernedMethod(
    asyncCapable = true,
    initialMaxConcurrency = 2,
    initialConsumerThreads = 0
)
void publish(OrderEvent event);
```

Important:

- `asyncCapable = true` is currently valid only for `void` methods
- Even for async-capable methods, execution starts in `SYNC` mode until you explicitly switch the method to `ASYNC`

### `@Excluded`

Methods marked with `@Excluded` bypass governance completely.

```java
@Excluded
String health();
```

Use this for:

- health checks
- lightweight metadata endpoints
- methods that must never be throttled or stopped by ASL

## 5. Generated Artifacts

For an interface like:

```java
@GovernedService
public interface SampleService {
    String echo(String value);
}
```

ASL generates:

- `SampleServiceAsl`
- `SampleServiceAslGoverned`

Typical generated entry point:

```java
SampleService wrapped = SampleServiceAsl.wrap(delegate, registry);
```

Generated wrapper responsibilities:

- register service descriptor
- register method runtimes
- enforce enable/disable
- enforce concurrency
- route async-capable methods to queue when mode is `ASYNC`
- track success/error/rejected counters

## 6. Non-Spring Usage

If you are not using Spring, wrap manually.

```java
GovernanceRegistry registry = new GovernanceRegistry();
SampleService delegate = new SampleServiceImpl();
SampleService service = SampleServiceAsl.wrap(delegate, registry);

String value = service.echo("test");
```

### Runtime Control

```java
ServiceRuntime serviceRuntime = registry.service(SampleServiceAsl.SERVICE_ID);
MethodRuntime echoRuntime = serviceRuntime.methodByName("echo");

echoRuntime.disable("faucet closed");
echoRuntime.enable();
echoRuntime.setMaxConcurrency(4);
```

Method lookup options:

- `methodByName("echo")`
- `methodById("echo(java.lang.String)")`
- `method(index)`

Use `methodById(...)` when:

- the interface has overloads
- you are calling control APIs from external systems

## 7. Spring Boot Usage

### Required Dependencies

If the modules are used from the same workspace:

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

You also need the annotation processor:

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

### Service Definition

```java
@GovernedService(id = "order.service")
public interface OrderService {
    String create(String payload);

    @GovernedMethod(asyncCapable = true, initialConsumerThreads = 0, initialMaxConcurrency = 2)
    void publish(String event);

    @Excluded
    String health();
}
```

Implementation:

```java
@Service
public class OrderServiceImpl implements OrderService {
    @Override
    public String create(String payload) {
        return "created:" + payload;
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

Injection:

```java
@RestController
public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }
}
```

Important Spring behavior:

- You inject the interface type
- The injected bean is the generated governed wrapper
- The original implementation bean remains in the context
- The wrapper is exposed as `@Primary`

### Auto-wrap Constraint

Currently supported cleanly:

- one Spring stereotype implementation per governed interface

If you create multiple implementations of the same governed interface with Spring stereotypes, the processor fails the compilation intentionally.

## 8. Async Queue Usage

### Enable MapDB Queue

```yaml
asl:
  async:
    mapdb:
      enabled: true
      path: ./data/asl-queue.db
      worker-shutdown-await-millis: 10000
```

What happens when this is enabled:

- starter creates `GovernanceRegistry`
- starter creates `MapDbAsyncExecutionEngine`
- registry attaches the engine
- async-capable generated wrappers can enqueue invocations when switched to `ASYNC`

### Async Execution Semantics

For a method like:

```java
@GovernedMethod(asyncCapable = true, initialConsumerThreads = 0, initialMaxConcurrency = 2)
void publish(String value);
```

Runtime behavior:

- `SYNC` mode: direct invocation
- `ASYNC` mode: request is written to MapDB and later consumed by workers

Important:

- `initialConsumerThreads = 0` means the queue can accept requests but no worker consumes them yet
- you can later raise the worker count to start draining the queue
- `initialMaxConcurrency` still applies to active async execution

### What You Must Not Do

Do not treat a synchronous result-bearing method as async-switchable.

Good:

```java
void publish(Event event);
```

Not supported for runtime async switching:

```java
Receipt createOrder(OrderRequest request);
```

Reason:

- queueing a result-bearing method changes the method contract
- the caller expects a direct return value

## 9. Admin UI and REST

### UI

Default path:

- `/asl`

Features:

- view services and methods
- enable / disable methods
- update max concurrency
- switch `SYNC` / `ASYNC`
- change consumer thread count
- inspect queue buffer preview
- inspect queue entry codec, payload type, payload version, and failure category
- replay failed entries
- delete entries
- clear queue buffer

### REST

Default base path:

- `/asl/api`

#### Service list

```http
GET /asl/api/services
```

#### Service detail

```http
GET /asl/api/services/{serviceId}
```

#### Enable method

```http
POST /asl/api/services/{serviceId}/methods/{methodId}/enable
```

#### Disable method

```http
POST /asl/api/services/{serviceId}/methods/{methodId}/disable?message=faucet-closed
```

#### Change concurrency

```http
POST /asl/api/services/{serviceId}/methods/{methodId}/concurrency
Content-Type: application/json

{
  "maxConcurrency": 4
}
```

#### Change mode

```http
POST /asl/api/services/{serviceId}/methods/{methodId}/mode
Content-Type: application/json

{
  "executionMode": "ASYNC"
}
```

#### Change consumer threads

```http
POST /asl/api/services/{serviceId}/methods/{methodId}/consumer-threads
Content-Type: application/json

{
  "consumerThreads": 5
}
```

#### Buffer preview

```http
GET /asl/api/services/{serviceId}/methods/{methodId}/buffer
```

#### Clear buffer

```http
DELETE /asl/api/services/{serviceId}/methods/{methodId}/buffer
```

#### Delete one entry

```http
DELETE /asl/api/services/{serviceId}/methods/{methodId}/buffer/{entryId}
```

#### Replay failed entry

```http
POST /asl/api/services/{serviceId}/methods/{methodId}/buffer/{entryId}/replay
```

## 10. Configuration

### Configuration Precedence

When more than one source exists, ASL resolves startup behavior in this order:

1. `application.yml` / `application.properties`
2. annotation values on the governed interface
3. library defaults

That means:

- you do **not** need `asl.admin.services.*` just to see services in the UI
- you do **not** need per-method property overrides if the annotation values are already correct
- if a property is omitted, ASL falls back to the default listed below

### What Happens If You Configure Nothing

If you only add the dependencies, annotation processor, and governed annotations:

- governed wrappers are still generated
- services and methods still appear in the admin UI
- admin UI is enabled by default at `/asl`
- admin REST is enabled by default at `/asl/api`
- method startup values come from `@GovernedMethod`
- if a `@GovernedMethod` field is also omitted, the library default is used
- MapDB async execution is **not** enabled unless `asl.async.mapdb.enabled=true`

### `asl.runtime.*`

These are shared runtime fallbacks used when a method does not provide a more specific value.

| Property | Default | If omitted |
| --- | --- | --- |
| `asl.runtime.default-unavailable-message` | `Method is disabled` | Disabled methods fall back to this message when no method-specific message is present |
| `asl.runtime.max-concurrency-exceeded-message-template` | `Method reached max concurrency: %d` | Rejected calls caused by concurrency saturation use this template |

### Admin Properties

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
```

Core admin properties:

| Property | Default | If omitted |
| --- | --- | --- |
| `asl.admin.enabled` | `true` | Admin UI and REST controllers are still registered |
| `asl.admin.path` | `/asl` | UI stays at `/asl` |
| `asl.admin.api-path` | `/asl/api` | REST stays at `/asl/api` |
| `asl.admin.buffer-preview-limit` | `50` | Buffer preview shows up to 50 entries |

Dashboard summary properties:

| Property | Default | If omitted |
| --- | --- | --- |
| `asl.admin.dashboard.attention-limit` | `8` | Summary returns up to 8 attention items |
| `asl.admin.dashboard.medium-utilization-percent` | `40` | Medium pressure threshold remains 40% |
| `asl.admin.dashboard.high-utilization-percent` | `80` | High pressure threshold remains 80% |

Dashboard refresh properties:

| Property | Default | If omitted |
| --- | --- | --- |
| `asl.admin.dashboard.refresh.live-refresh-enabled` | `true` | Live refresh starts enabled |
| `asl.admin.dashboard.refresh.live-buffer-enabled` | `true` | Live buffer refresh starts enabled |
| `asl.admin.dashboard.refresh.default-interval-ms` | `5000` | Auto refresh interval starts at 5 seconds |
| `asl.admin.dashboard.refresh.interval-options-ms` | `[3000, 5000, 10000, 30000]` | The UI keeps these four interval choices |
| `asl.admin.dashboard.refresh.change-flash-ms` | `1400` | Change-highlight flash duration stays 1.4 seconds |
| `asl.admin.dashboard.refresh.success-message-auto-hide-ms` | `1600` | Success toasts auto-hide after 1.6 seconds |
| `asl.admin.dashboard.refresh.error-message-auto-hide-ms` | `3200` | Error toasts auto-hide after 3.2 seconds |

### `asl.admin.ui.*`

All admin page text is overrideable. If you omit a field, the built-in label remains in place.

| Property | Default |
| --- | --- |
| `asl.admin.ui.page-title` | `ASL Control Plane` |
| `asl.admin.ui.hero-title` | `ASL Control Plane` |
| `asl.admin.ui.hero-description` | `Review governed methods, stop or resume traffic, change concurrency and async settings, and inspect queue state from the same Spring Boot port.` |
| `asl.admin.ui.rest-badge-prefix` | `REST:` |
| `asl.admin.ui.empty-title` | `No governed services registered` |
| `asl.admin.ui.empty-description` | `The admin UI is active, but the runtime registry is empty.` |
| `asl.admin.ui.services-title` | `Services` |
| `asl.admin.ui.service-search-placeholder` | `Search services` |
| `asl.admin.ui.service-tab-note` | `Open this service subform` |
| `asl.admin.ui.service-detail-note` | `Select a method from the left subform list to manage its full details.` |
| `asl.admin.ui.methods-title` | `Methods` |
| `asl.admin.ui.all-label` | `All` |
| `asl.admin.ui.no-parameters-label` | `No parameters` |
| `asl.admin.ui.running-label` | `RUNNING` |
| `asl.admin.ui.stopped-label` | `STOPPED` |
| `asl.admin.ui.sync-mode-label` | `SYNC` |
| `asl.admin.ui.async-label` | `ASYNC` |
| `asl.admin.ui.error-label` | `ERROR` |
| `asl.admin.ui.success-label` | `Success` |
| `asl.admin.ui.rejected-label` | `Rejected` |
| `asl.admin.ui.load-label` | `Load` |
| `asl.admin.ui.peak-in-flight-label` | `Peak In Flight` |
| `asl.admin.ui.execution-mode-label` | `Execution Mode` |
| `asl.admin.ui.consumer-threads-label` | `Consumer Threads` |
| `asl.admin.ui.last-error-label` | `Last Error` |
| `asl.admin.ui.none-label` | `none` |
| `asl.admin.ui.method-state-title` | `Method State` |
| `asl.admin.ui.start-method-label` | `Start Method` |
| `asl.admin.ui.stop-method-label` | `Stop Method` |
| `asl.admin.ui.disable-placeholder` | `Reason shown to callers` |
| `asl.admin.ui.method-state-hint` | `Stopping a method returns the configured message to incoming callers.` |
| `asl.admin.ui.sync-concurrency-title` | `Sync Concurrency` |
| `asl.admin.ui.update-limit-label` | `Update Limit` |
| `asl.admin.ui.sync-concurrency-hint` | `Defines how many concurrent executions are allowed for this method.` |
| `asl.admin.ui.async-controls-title` | `Async Controls` |
| `asl.admin.ui.apply-mode-label` | `Apply` |
| `asl.admin.ui.update-consumers-label` | `Update` |
| `asl.admin.ui.async-hint` | `Use async mode only for methods designed to be safely queued and consumed later.` |
| `asl.admin.ui.queue-buffer-title` | `Queue Buffer` |
| `asl.admin.ui.load-overview-title` | `Load Overview` |
| `asl.admin.ui.no-buffer-message` | `No buffer provider is currently attached to this method.` |
| `asl.admin.ui.clear-buffer-label` | `Clear Buffer` |
| `asl.admin.ui.replay-entry-label` | `Replay Entry` |
| `asl.admin.ui.delete-entry-label` | `Delete Entry` |
| `asl.admin.ui.processed-label` | `Processed` |
| `asl.admin.ui.active-work-label` | `Active Work` |
| `asl.admin.ui.queue-depth-label` | `Queue Depth` |
| `asl.admin.ui.utilization-label` | `Utilization` |
| `asl.admin.ui.work-pressure-label` | `Work Pressure` |
| `asl.admin.ui.worker-capacity-label` | `Worker Capacity` |
| `asl.admin.ui.live-refresh-label` | `Live Refresh` |
| `asl.admin.ui.refresh-now-label` | `Refresh Now` |
| `asl.admin.ui.refresh-interval-label` | `Refresh Interval` |
| `asl.admin.ui.refresh-buffer-label` | `Refresh Buffer` |
| `asl.admin.ui.live-buffer-label` | `Live Buffer` |
| `asl.admin.ui.scroll-top-label` | `Top` |
| `asl.admin.ui.scroll-bottom-label` | `Bottom` |
| `asl.admin.ui.ready-status-label` | `Ready` |
| `asl.admin.ui.applying-change-message` | `Applying change...` |
| `asl.admin.ui.change-applied-message` | `Change applied` |
| `asl.admin.ui.request-failed-message` | `Request failed` |
| `asl.admin.ui.refreshing-metrics-message` | `Refreshing live metrics...` |
| `asl.admin.ui.metrics-refreshed-message` | `Metrics refreshed` |
| `asl.admin.ui.refreshing-buffer-message` | `Refreshing buffer...` |
| `asl.admin.ui.buffer-refreshed-message` | `Buffer refreshed` |
| `asl.admin.ui.entry-id-label` | `Entry Id` |
| `asl.admin.ui.attempts-label` | `Attempts` |
| `asl.admin.ui.codec-label` | `Codec` |
| `asl.admin.ui.payload-type-label` | `Payload Type` |
| `asl.admin.ui.payload-version-label` | `Payload Version` |
| `asl.admin.ui.error-type-label` | `Error Type` |
| `asl.admin.ui.error-category-label` | `Error Category` |
| `asl.admin.ui.methods-count-suffix` | `methods` |
| `asl.admin.ui.async-capable-suffix` | `async-capable` |
| `asl.admin.ui.stopped-suffix` | `stopped` |
| `asl.admin.ui.methods-with-errors-suffix` | `with errors` |
| `asl.admin.ui.pending-label` | `Pending` |
| `asl.admin.ui.failed-label` | `Failed` |
| `asl.admin.ui.in-progress-label` | `In progress` |

### `asl.admin.services.*`

This block is optional. It exists to override startup runtime values from configuration.

Example:

```yaml
asl:
  admin:
    services:
      "mail.service":
        methods:
          "send(java.lang.String)":
            max-concurrency: 6
          "publishAudit(java.lang.String)":
            execution-mode: ASYNC
            consumer-threads: 2
```

If you omit this entire block:

- services and methods still appear in the UI
- annotation-defined startup values are used
- if the annotation also omits a setting, the library default is used

Supported per-method overrides:

| Property | If omitted |
| --- | --- |
| `asl.admin.services.<serviceId>.methods.<methodId>.enabled` | Uses `@GovernedMethod(initiallyEnabled)`; default `true` |
| `asl.admin.services.<serviceId>.methods.<methodId>.max-concurrency` | Uses `@GovernedMethod(initialMaxConcurrency)`; default `Integer.MAX_VALUE` |
| `asl.admin.services.<serviceId>.methods.<methodId>.unavailable-message` | Uses `@GovernedMethod(unavailableMessage)`; if blank, falls back to `asl.runtime.default-unavailable-message` when disabled |
| `asl.admin.services.<serviceId>.methods.<methodId>.execution-mode` | Starts in `SYNC` unless explicitly overridden or changed at runtime |
| `asl.admin.services.<serviceId>.methods.<methodId>.consumer-threads` | Uses `@GovernedMethod(initialConsumerThreads)`; default `1` |

### MapDB Async Properties

```yaml
asl:
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

| Property | Default | If omitted |
| --- | --- | --- |
| `asl.async.mapdb.enabled` | `false` | No MapDB async engine is created |
| `asl.async.mapdb.path` | `./data/asl-queue.db` | Queue file stays under `./data/asl-queue.db` when enabled |
| `asl.async.mapdb.codec` | `java-object-stream` | Java object stream codec is used |
| `asl.async.mapdb.worker-shutdown-await-millis` | `10000` | Worker shutdown waits up to 10 seconds |
| `asl.async.mapdb.registration-idle-sleep-millis` | `100` | Workers back off 100 ms when a lane is not yet registered |
| `asl.async.mapdb.empty-queue-sleep-millis` | `50` | Workers back off 50 ms when the queue is empty |
| `asl.async.mapdb.requeue-delay-millis` | `75` | Workers back off 75 ms after requeue due to full concurrency |
| `asl.async.mapdb.recovered-in-progress-message` | `Recovered stale in-progress invocation after restart` | This message is written when stale in-progress work is recovered |
| `asl.async.mapdb.transactions-enabled` | `true` | Transactional writes remain enabled |
| `asl.async.mapdb.memory-mapped-enabled` | `false` | Memory-mapped IO stays off |
| `asl.async.mapdb.reset-if-corrupt` | `false` | Recoverable store corruption fails startup instead of resetting/falling back |

### Payload Codec

The async engine now persists encoded payload bytes instead of storing raw arguments through MapDB serializer wiring.

Default:

- `JavaObjectStreamAsyncPayloadCodec`

Built-in Spring option:

- `JacksonJsonAsyncPayloadCodec`

Operational guidance:

- For durable queues, prefer the Jackson codec plus method-specific schema registration.
- For schema or codec changes, roll them out in a staged manner and validate replay/restart compatibility before production rollout.

Spring Boot behavior:

- if no `AsyncPayloadCodec` bean exists, starter registers the default codec
- if you provide your own `AsyncPayloadCodec` bean, MapDB uses it
- if `asl.async.mapdb.codec=jackson-json` and `ObjectMapper` is present, starter wires the Jackson codec automatically

Typical custom codec use cases:

- avoiding Java serialization constraints
- versioned business payload formats
- schema-controlled queue compatibility across deployments
- compact binary or JSON payload storage

### Jackson Schema Registry And Migration Hooks

If you use the built-in Jackson codec, you can register payload schemas with a stable logical type identity.

This is useful when:

- payload classes evolve across deployments
- you want stable logical type ids instead of raw class names
- old queued payloads must be migrated to the current shape before execution
- method signatures may change across deployments

Core starter types:

- `JacksonAsyncPayloadSchemaRegistry`
- `JacksonAsyncPayloadArgumentSchema`
- `AbstractJacksonAsyncPayloadArgumentSchema`
- `MapBackedJacksonAsyncPayloadSchemaRegistry`

Example:

```java
@Bean
JacksonAsyncPayloadSchemaRegistry jacksonAsyncPayloadSchemaRegistry() {
    MailEventSchema mailEventSchema = new MailEventSchema();
    return MapBackedJacksonAsyncPayloadSchemaRegistry.builder()
            .registerType(mailEventSchema)
            .bind("mail.service", "publish(com.acme.MailEvent)", 0, "mail-event")
            .build();
}
```

```java
public final class MailEventSchema extends AbstractJacksonAsyncPayloadArgumentSchema<MailEvent> {
    public MailEventSchema() {
        super("mail-event", MailEvent.class, 2);
    }

    @Override
    public JsonNode migrate(ObjectMapper objectMapper, int fromVersion, JsonNode payload) {
        if (fromVersion == 1 && payload instanceof ObjectNode objectNode) {
            JsonNode oldField = objectNode.remove("text");
            objectNode.set("message", oldField);
            return objectNode;
        }
        return super.migrate(objectMapper, fromVersion, payload);
    }
}
```

Behavior:

- newly queued payloads are stored with schema type id and schema version
- encode uses the current method binding, but decode resolves by stable `schemaType` first
- old queued payloads are migrated during decode before method invocation
- if no schema is registered for a schema-encoded payload, decode fails loudly

Compatibility note:

- Jackson codec still supports legacy class-name based payloads already written by the previous `jackson-json-v1` envelope
- new payloads written after this change use the versioned envelope with `codecVersion = 2`
- keeping `typeId()` stable makes queued payloads less dependent on the current method signature

Minimal Spring example:

```java
@Bean
AsyncPayloadCodec asyncPayloadCodec() {
    return new MyPayloadCodec();
}
```

Built-in Jackson activation:

```yaml
asl:
  async:
    mapdb:
      enabled: true
      codec: jackson-json
      path: ./data/asl-queue.db
```

## 11. Runtime API Details

Core types you will interact with:

- `GovernanceRegistry`
- `ServiceRuntime`
- `MethodRuntime`
- `MethodRuntimeSnapshot`
- `MethodStatsSnapshot`
- `MethodBufferSnapshot`
- `MethodUnavailableException`

Typical runtime flow:

```java
ServiceRuntime serviceRuntime = registry.service("order.service");
MethodRuntime methodRuntime = serviceRuntime.methodById("publish(java.lang.String)");

methodRuntime.disable("temporarily stopped");
methodRuntime.enable();
methodRuntime.setMaxConcurrency(8);
methodRuntime.switchMode(ExecutionMode.ASYNC);
methodRuntime.setConsumerThreads(3);
```

Snapshot example:

```java
MethodRuntimeSnapshot snapshot = methodRuntime.snapshot();
```

Snapshot gives you:

- enabled flag
- max concurrency
- async capability
- execution mode
- consumer thread count
- unavailable message
- success count
- error count
- rejected count
- in-flight
- peak in-flight
- last error

## 12. Method IDs

If you do not supply `@GovernedMethod(id = "...")`, the generated id format is:

```text
methodName(java.lang.String,int,...)
```

Examples:

- `echo(java.lang.String)`
- `slow(java.lang.String)`
- `publish(java.lang.String)`

If there are overloads, do not rely on method name alone in external control systems.

Use the exact method id.

## 13. Error Behavior

### Method disabled

Callers receive `MethodUnavailableException`.

Typical reasons:

- explicitly disabled
- max concurrency reached

### Async engine missing

If a method is switched to `ASYNC` and no async engine is attached, ASL raises an async execution error instead of silently degrading.

### Failed queued item

When async execution fails:

- method error stats increase
- the queue item moves to failed state
- the item can be inspected, deleted, or replayed later

## 14. Recommended Usage Patterns

Use governance for:

- expensive external calls
- high-contention service methods
- operationally sensitive pipelines
- bursty event publication

Do not govern everything blindly.

Good candidates:

- payment authorization
- notification publish
- settlement dispatch
- batch write endpoints

Usually exclude:

- health
- metadata lookup
- static config reads

## 15. Operational Notes

- If a method should queue but not consume yet, start with `initialConsumerThreads = 0`
- If a method must not accept traffic, disable it instead of setting concurrency to zero; zero is invalid
- If you want deterministic operator control, define explicit `serviceId` and `methodId`
- If URLs are built externally, remember that `methodId` contains parentheses and commas; encode path segments correctly
- If you need async mode, always enable MapDB or another async engine before switching methods to `ASYNC`

## 16. Readiness Verdict

Yes, the repository is currently usable.

Practical readiness level:

- ready for internal integration
- ready for controlled service rollout
- ready for admin-driven operational throttling
- ready for persistent async queue use on `void` methods

Still not the final shape if you want broader semantics:

- no async switching for non-`void` methods
- no multi-implementation Spring strategy yet
- no external auth/security layer on admin endpoints in this repo by default
- default codec is still Java object stream unless you override it with a custom codec

## 17. Minimal End-to-End Example

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
```

Runtime:

1. App starts
2. Spring injects governed `MailService`
3. Operator opens `/asl`
4. `send(...)` can be stopped or throttled
5. `publish(...)` can be switched to `ASYNC`
6. Consumer threads can be raised from `0` to `N`
7. Failed async entries can be replayed or deleted

## 18. Reference Files

- Sample interface: [SampleService.java](/E:/ReactorRepository/async-service-library/asl-sample/src/main/java/com/reactor/asl/sample/SampleService.java)
- Sample manual wrapping test: [GeneratedWrapperTest.java](/E:/ReactorRepository/async-service-library/asl-sample/src/test/java/com/reactor/asl/sample/GeneratedWrapperTest.java)
- Spring auto-wrap test: [AutoWrapIntegrationTest.java](/E:/ReactorRepository/async-service-library/asl-spring-boot-starter/src/test/java/com/reactor/asl/spring/boot/autowrap/AutoWrapIntegrationTest.java)
- Admin REST controller: [AslAdminRestController.java](/E:/ReactorRepository/async-service-library/asl-spring-boot-starter/src/main/java/com/reactor/asl/spring/boot/AslAdminRestController.java)
- Admin UI controller: [AslAdminPageController.java](/E:/ReactorRepository/async-service-library/asl-spring-boot-starter/src/main/java/com/reactor/asl/spring/boot/AslAdminPageController.java)
- Core auto-config: [AslCoreAutoConfiguration.java](/E:/ReactorRepository/async-service-library/asl-spring-boot-starter/src/main/java/com/reactor/asl/spring/boot/AslCoreAutoConfiguration.java)
