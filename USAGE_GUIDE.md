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

Meaning:

- `runtime.default-unavailable-message`: shared fallback disable message for governed methods
- `runtime.max-concurrency-exceeded-message-template`: shared rejected-call template used when concurrency is saturated
- `enabled`: enables admin controller registration
- `path`: Thymeleaf UI base path
- `api-path`: REST base path
- `buffer-preview-limit`: max number of queue items shown in preview
- `dashboard.attention-limit`: max number of attention items returned in the summary
- `dashboard.medium-utilization-percent` / `dashboard.high-utilization-percent`: pressure thresholds used by summary and UI
- `dashboard.refresh.*`: default live refresh behavior and UI timing values for the admin page

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

Meaning:

- `enabled`: creates MapDB async engine
- `path`: queue persistence file
- `codec`: selects `java-object-stream` or `jackson-json`
- `worker-shutdown-await-millis`: how long close/resize waits for running async workers to drain before failing shutdown
- `registration-idle-sleep-millis`: worker backoff when a lane has not been registered yet
- `empty-queue-sleep-millis`: worker backoff when the lane queue is empty
- `requeue-delay-millis`: worker backoff after re-queueing because max concurrency is currently full
- `recovered-in-progress-message`: startup recovery message written when stale `IN_PROGRESS` work is moved to failed state
- `transactions-enabled`: enables MapDB transactional writes
- `memory-mapped-enabled`: enables mmap access when explicitly desired
- `reset-if-corrupt`: allows startup recovery/reset instead of failing hard on recoverable store corruption

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
