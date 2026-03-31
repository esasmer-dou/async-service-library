# Production Hardening Checklist

## Runtime Controls

- Define explicit `serviceId` for every governed service
- Define explicit `methodId` for methods that will be controlled externally
- Exclude health and liveness endpoints with `@Excluded`
- Set deliberate `initialMaxConcurrency` instead of relying on defaults for hot methods
- For async methods, start with `initialConsumerThreads = 0` if you want operator-controlled drain start
- Do not mark non-`void` methods as async-capable

## Deployment

- Persist MapDB queue on durable storage
- Put queue files on a dedicated data path, not ephemeral temp storage
- Monitor disk usage for the queue path
- Ensure pod or VM restart policy matches your replay expectations
- Validate file permissions on the queue directory
- Set `worker-shutdown-await-millis` to match real in-flight drain expectations
- Do not rely on the default payload codec if you need long-lived schema compatibility across versions
- If you use the Jackson codec for long-lived queues, register method-specific schemas and migration hooks for every durable business payload

## Spring Boot

- Keep exactly one Spring stereotype implementation per governed interface
- Inject governed services by interface, not by implementation
- Do not bypass the generated wrapper by wiring implementation classes directly into business flow
- If admin UI is enabled, decide whether it should be exposed only on internal networks

## Operations

- Establish a runbook for stop, resume, replay, delete, and clear actions
- Establish an upgrade runbook for codec, schema, and queue compatibility changes
- Record who is allowed to disable methods or resize consumers
- Define which methods are allowed to switch to `ASYNC`
- Track queue depth, failed count, rejected count, and last error
- Track queue entry `codec`, `payload type`, `payload version`, and `error category` during upgrades
- Add alerts for permanently growing pending queues
- Add alerts for repeated replay failures

## Performance

- Benchmark hot methods in both governed and ungoverned mode
- Load test async-capable methods under queue pressure
- Validate `maxConcurrency` values under real workload, not intuition
- Validate worker thread counts under CPU and IO contention
- Measure tail latency before and after enabling governance on critical methods

## Testing

- Add service-specific integration tests for each governed method
- Add failure-path tests for disabled mode and max-concurrency rejection
- Add replay tests for every async business pipeline
- Add codec compatibility tests if you provide a custom `AsyncPayloadCodec`
- Add startup tests that ensure the generated wrapper is injected as the primary bean
- Add smoke tests for admin endpoints if they are enabled in production
