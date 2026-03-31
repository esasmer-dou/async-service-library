# ASL Consumer Sample

This is a runnable Spring Boot sample that uses ASL in the service layer.

## What It Demonstrates

- three governed services on one Spring Boot application
- governed Spring service injection
- direct REST API over service interfaces
- synchronous governed methods
- async-capable governed methods
- excluded methods
- admin UI and admin REST on the same application port

## Run

From the repository root:

```powershell
mvn -pl asl-consumer-sample -am spring-boot:run
```

The sample starts on:

- application API: `http://localhost:8080`
- admin UI: `http://localhost:8080/asl`
- admin REST: `http://localhost:8080/asl/api`
- admin summary snapshot: `http://localhost:8080/asl/api/summary`

## Runtime Configuration Surface

The sample ships with the main out-of-the-box runtime knobs already externalized in [`application.yml`](E:\ReactorRepository\async-service-library\asl-consumer-sample\src\main\resources\application.yml):

```yaml
asl:
  runtime:
    default-unavailable-message: Method is disabled
    max-concurrency-exceeded-message-template: "Method reached max concurrency: %d"
  admin:
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
  async:
    mapdb:
      worker-shutdown-await-millis: 10000
      registration-idle-sleep-millis: 100
      empty-queue-sleep-millis: 50
      requeue-delay-millis: 75
      recovered-in-progress-message: Recovered stale in-progress invocation after restart
      transactions-enabled: true
      memory-mapped-enabled: false
      reset-if-corrupt: true
```

This sample config is intentionally verbose so users can see which runtime defaults are now overridable without code changes.

## Control Plane Workflow

Use the admin surface in this order:

1. open `/asl`
2. read the global overview cards
3. inspect `Operations Digest`
4. open the service owning the highest-priority lane
5. apply the smallest runtime change that resolves pressure

Machine-readable control-plane snapshot:

```powershell
curl http://localhost:8080/asl/api/summary
```

## Main APIs

Create draft:

```powershell
curl -X POST http://localhost:8080/api/mails `
  -H "Content-Type: application/json" `
  -d "{\"recipient\":\"user@company.com\",\"subject\":\"Welcome\",\"body\":\"Hello from ASL\"}"
```

List mails:

```powershell
curl http://localhost:8080/api/mails
```

Get one mail:

```powershell
curl http://localhost:8080/api/mails/{mailId}
```

Send one mail:

```powershell
curl -X POST http://localhost:8080/api/mails/{mailId}/send
```

Publish audit event:

```powershell
curl -X POST http://localhost:8080/api/mails/{mailId}/publish-audit
```

See audit events:

```powershell
curl http://localhost:8080/api/mails/audit-events
```

Health:

```powershell
curl http://localhost:8080/api/health
```

Register customer:

```powershell
curl -X POST http://localhost:8080/api/customers `
  -H "Content-Type: application/json" `
  -d "{\"email\":\"mustafa@company.com\",\"fullName\":\"Mustafa Korkmaz\"}"
```

Upsert inventory item:

```powershell
curl -X POST http://localhost:8080/api/inventory `
  -H "Content-Type: application/json" `
  -d "{\"sku\":\"SKU-1\",\"title\":\"Thermal Printer\",\"available\":7}"
```

## Async Demo

`publishAudit(...)` is async-capable but starts in `SYNC` mode.

To force queue mode:

1. Open `http://localhost:8080/asl`
2. Find method `publishAudit(java.lang.String)`
3. Switch mode to `ASYNC`
4. Increase consumer threads from `0` to `1`
5. Call `/api/mails/{mailId}/publish-audit`
6. Inspect queue state from the admin screen or `/asl/api`

You can repeat the same pattern for:

- `customer.service -> publishWelcome(java.lang.String)`
- `inventory.service -> publishSnapshot(java.lang.String)`
- `chaos.service -> emit(java.lang.String)`

## Async Scenario Lab

The sample now exposes test-only scenario endpoints so you can simulate real async queue behavior against the MapDB-backed engine.

Scenario controls:

- mail audit: `POST /api/test/scenarios/mail/audit`
- customer welcome: `POST /api/test/scenarios/customer/welcome`
- inventory snapshot: `POST /api/test/scenarios/inventory/snapshot`
- chaos emit: `POST /api/test/scenarios/chaos/emit`
- reset all sample state: `POST /api/test/scenarios/reset`

Scenario request body:

```json
{
  "failuresRemaining": 1,
  "processingDelayMillis": 500
}
```

Example: force one async failure for mail audit:

```powershell
curl -X POST http://localhost:8080/api/test/scenarios/mail/audit `
  -H "Content-Type: application/json" `
  -d "{\"failuresRemaining\":1,\"processingDelayMillis\":0}"
```

Example: slow inventory snapshot processing to observe queue buildup and in-progress entries:

```powershell
curl -X POST http://localhost:8080/api/test/scenarios/inventory/snapshot `
  -H "Content-Type: application/json" `
  -d "{\"failuresRemaining\":0,\"processingDelayMillis\":400}"
```

## Recommended Async Test Matrix

Use the sample to validate these operational flows:

- async success path
- async failure moves invocation into failed buffer
- replay restores a failed entry and executes it successfully
- consumer threads `0` causes pending queue buildup
- delete removes one queued entry
- clear removes all non in-progress entries
- resizing consumers drains pending work
- disabled async method rejects before queueing
- `SYNC` mode on an async-capable method executes inline and fails inline

The integration suite covers these flows in:

- [AslConsumerSampleIntegrationTest.java](E:\ReactorRepository\async-service-library\asl-consumer-sample\src\test\java\com\reactor\asl\consumer\sample\AslConsumerSampleIntegrationTest.java)

## Benchmarking The Control Plane

From the repository root, generate a Markdown benchmark report against the running sample:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-control-plane-benchmark.ps1
```

Generate the full idle + backlog benchmark suite:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\generate-sample-benchmark-suite.ps1
```

Generate the suite and fail fast if the threshold gate does not pass:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-control-plane-benchmark-gate.ps1 `
  -StartSample `
  -Profile local `
  -Warmup 1 `
  -Iterations 3
```

If you want the script to boot the sample itself without competing for a busy local `8080`, it will default to `http://localhost:18080` unless you override `-BaseUrl`.
The suite now performs an automatic baseline reset before the idle report.
When `-StartSample` is used, the benchmark runner boots the sample with an isolated runtime queue under `reports/real-sample/sample-runtime/benchmark-queue.db`, so benchmark force-stop behavior does not corrupt the main demo queue at `./data/asl-consumer-sample-queue.db`.
The suite only stops the sample process when it started that process itself.
Available gate profiles are `local`, `ci`, and `staging`.
You can also inject profile overrides via `ASL_BENCHMARK_*` environment variables or load extra profile files through `ASL_BENCHMARK_EXTRA_THRESHOLDS_PATHS`.
If the sample boots slowly in CI or staging, raise `ASL_BENCHMARK_READY_TIMEOUT_SECONDS`.
If no profile is given, the gate can now resolve one from release tags and branch names via `control-plane-benchmark-profile-resolution.json`.

Run the destructive MapDB abuse suite against an isolated sample runtime:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-mapdb-abuse-suite.ps1
```

This suite intentionally:

- force-kills the JVM while work is pending
- force-kills the JVM after a failed async entry is persisted
- force-kills the JVM while work is `IN_PROGRESS`
- repeats multiple pending kill / restart loops against the same queue file
- starts the app on a deliberately header-corrupted queue file

Generated reports are written locally / as CI artifacts under:

- `reports/mapdb-abuse/`
- `tr/reports/mapdb-abuse/`

The same destructive suite now runs in nightly CI:

- [mapdb-abuse-nightly.yml](E:\ReactorRepository\async-service-library\.github\workflows\mapdb-abuse-nightly.yml)

Related references:

- [BENCHMARK_RUNBOOK.md](E:\ReactorRepository\async-service-library\BENCHMARK_RUNBOOK.md)
- [ADMIN_CONTROL_PLANE_GUIDE.md](E:\ReactorRepository\async-service-library\ADMIN_CONTROL_PLANE_GUIDE.md)
- [reports/README.md](E:\ReactorRepository\async-service-library\reports\README.md)

## Chaos And Recovery

The sample also includes restart-based recovery tests around a self-contained async lab service:

- pending queue survives application restart
- failed queue survives restart and can be replayed
- in-flight work drains on graceful shutdown
- stale `IN_PROGRESS` recovery is validated at engine level

Relevant tests:

- [AslConsumerSampleChaosRecoveryTest.java](E:\ReactorRepository\async-service-library\asl-consumer-sample\src\test\java\com\reactor\asl\consumer\sample\AslConsumerSampleChaosRecoveryTest.java)
- [MapDbAsyncExecutionEngineCoverageTest.java](E:\ReactorRepository\async-service-library\asl-mapdb\src\test\java\com\reactor\asl\mapdb\MapDbAsyncExecutionEngineCoverageTest.java)

Manual walkthrough:

- [CHAOS_TEST_GUIDE.md](E:\ReactorRepository\async-service-library\asl-consumer-sample\CHAOS_TEST_GUIDE.md)

## Notes

- Mail storage is in-memory
- Async queue storage is MapDB-backed on `./data/asl-consumer-sample-queue.db`
- The sample keeps MapDB in safety-first mode: `transactions-enabled=true` and `memory-mapped-enabled=false`
- The sample enables `asl.async.mapdb.reset-if-corrupt=true`, so a corrupted demo queue file is archived and replaced automatically on startup
- On startup, header-corrupted queue files are moved aside as `*.corrupt-*`; if the file cannot be moved cleanly, the application falls back to a fresh recovery queue file
- When startup recovery happens, the admin UI shows an operational banner with the recovery status, the archived or recovery file path, and the active queue store path
- If you want a fully clean manual reset, delete `./data/asl-consumer-sample-queue.db` before starting the sample
- The sample uses the built-in Jackson async payload codec
- This project is for trying the library, not for production business persistence
