# ASL Consumer Sample

[English](README.md) | [Turkish](../tr/asl-consumer-sample/README.md)

This is a runnable Spring Boot sample that uses ASL in the service layer.

## What It Demonstrates

- governed services on one Spring Boot application
- governed Spring service injection
- direct REST API over service interfaces
- synchronous governed methods
- async-capable governed methods on a MapDB queue
- excluded methods
- management from the central admin server; the sample itself exposes no admin endpoint

## Run

From the repository root:

```powershell
mvn -pl asl-consumer-sample -am spring-boot:run
```

The application API starts on `http://localhost:8080`. On its own the sample runs with its yml settings; to change them at runtime, connect it to the central admin server.

## Manage It From the Central Admin Server

Start the admin server (local demo settings: no login, data kept in memory):

```powershell
mvn -pl asl-admin-server -am package -DskipTests
java -jar asl-admin-server/target/asl-admin-server-0.1.0-SNAPSHOT-exec.jar `
  --port=8090 --secret=local-demo-secret-0001 --security.mode=NONE --storage.mode=MEMORY
```

Start the sample connected to it:

```powershell
$env:ASL_ADMIN_CLIENT_ENABLED = "true"
$env:ASL_ADMIN_CLIENT_SERVER_URL = "http://localhost:8090"
$env:ASL_ADMIN_CLIENT_SECRET = "local-demo-secret-0001"
mvn -pl asl-consumer-sample -am spring-boot:run
```

Open the portal at `http://localhost:8090/asl-admin/`. The sample appears as `asl-consumer-sample`. `security.mode=NONE` is for a local demo only; production keeps the default `BASIC` login (or `PROXY_HEADER` behind an identity proxy) and puts the portal behind a TLS ingress.

## Runtime Configuration Surface

The sample's settings are in [`application.yml`](src/main/resources/application.yml):

```yaml
asl:
  runtime:
    default-unavailable-message: Method is disabled
    max-concurrency-exceeded-message-template: "Method reached max concurrency: %d"
    services:
      "mail.service":
        methods:
          "send(java.lang.String)":
            max-concurrency: 6
          "publishAudit(java.lang.String)":
            execution-mode: ASYNC
            consumer-threads: 2
  admin:
    client:
      enabled: false
      application: asl-consumer-sample
      queue-browse: true
  async:
    mapdb:
      enabled: true
      codec: jackson-json
      path: ./data/asl-consumer-sample-queue.db
      transactions-enabled: true
      memory-mapped-enabled: false
      corruption-policy: ARCHIVE_AND_RESET
```

`asl.runtime.services` are the startup values. Overrides set in the portal are applied on top while the sample is connected; removing an override returns the method to these values. `queue-browse: true` lets the portal list queue entries, which sends entry summaries and error texts to the admin server. Every supported property, its default, and the behavior when omitted are in [CONFIGURATION_REFERENCE.md](../CONFIGURATION_REFERENCE.md).

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

`customer.service -> publishWelcome(java.lang.String)` is async-capable and starts in `SYNC` mode.

To queue it, with the sample connected to the admin server:

1. Open `http://localhost:8090/asl-admin/` and select `asl-consumer-sample`.
2. Find `publishWelcome(java.lang.String)`, switch it to `ASYNC` and set consumer threads to `0`.
3. Call `POST /api/customers/{customerId}/publish-welcome` a few times; the pending count grows.
4. Browse the queue from the portal, then raise consumer threads to `1` and watch it drain.

The same works for `mail.service -> publishAudit(java.lang.String)` (already `ASYNC` from yml), `inventory.service -> publishSnapshot(java.lang.String)` and `chaos.service -> emit(java.lang.String)`.

The portal uses a small JSON API that scripts can call too ([USAGE_GUIDE.md section 9](../USAGE_GUIDE.md#9-admin-server-portal-api)):

```powershell
$api = "http://localhost:8090/asl-admin/api/apps/asl-consumer-sample"
Invoke-RestMethod -Method Post "$api/overrides" -Body @{
  serviceId = "customer.service"; methodId = "publishWelcome(java.lang.String)"; async = "true"; consumerThreads = "1" }
Invoke-RestMethod "$api"
```

## Async Scenario Lab

The sample exposes test-only scenario endpoints so you can simulate real async queue behavior against the MapDB-backed engine.

Scenario controls:

- mail audit: `POST /api/test/scenarios/mail/audit`
- customer welcome: `POST /api/test/scenarios/customer/welcome`
- inventory snapshot: `POST /api/test/scenarios/inventory/snapshot`
- chaos emit: `POST /api/test/scenarios/chaos/emit`
- reset all sample state: `POST /api/test/scenarios/reset`
- startup store recovery status: `GET /api/chaos/storage-recovery`

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

- async success path
- async failure moves the invocation into the failed buffer
- replay restores a failed entry and executes it successfully
- consumer threads `0` builds up a pending queue
- delete removes one queued entry
- clear removes all entries that are not in progress
- resizing consumers drains pending work
- a disabled async method rejects before queueing
- `SYNC` mode on an async-capable method executes inline and fails inline

The integration suite covers these flows against the application's own beans:

- [AslConsumerSampleIntegrationTest.java](src/test/java/com/reactor/asl/consumer/sample/AslConsumerSampleIntegrationTest.java)

## Recovery Stress Suite

From the repository root (PowerShell 7):

```powershell
pwsh -File .\scripts\run-mapdb-abuse-suite.ps1
```

The suite builds and starts the central admin server and the sample with the admin client enabled. Mode, consumer, browse and replay operations go through the admin server. It intentionally:

- force-kills the JVM while work is pending
- force-kills the JVM after a failed async entry is persisted
- force-kills the JVM while work is `IN_PROGRESS`
- repeats several pending kill / restart loops against the same queue file
- starts the sample on a deliberately header-corrupted queue file

Reports are written locally / as CI artifacts under `reports/mapdb-abuse/` and `tr/reports/mapdb-abuse/`. The suite runs nightly in CI: [mapdb-abuse-nightly.yml](../.github/workflows/mapdb-abuse-nightly.yml). See [reports/README.md](../reports/README.md).

## Chaos And Recovery

The sample also includes restart-based recovery tests around a self-contained async lab service:

- pending queue survives application restart
- failed queue survives restart and can be replayed
- in-flight work drains on graceful shutdown
- stale `IN_PROGRESS` recovery is validated at engine level

Relevant tests:

- [AslConsumerSampleChaosRecoveryTest.java](src/test/java/com/reactor/asl/consumer/sample/AslConsumerSampleChaosRecoveryTest.java)
- [MapDbAsyncExecutionEngineCoverageTest.java](../asl-mapdb/src/test/java/com/reactor/asl/mapdb/MapDbAsyncExecutionEngineCoverageTest.java)

Manual walkthrough:

- [CHAOS_TEST_GUIDE.md](CHAOS_TEST_GUIDE.md)

## Notes

- Mail storage is in-memory
- Async queue storage is MapDB-backed on `./data/asl-consumer-sample-queue.db`
- The sample keeps MapDB in safety-first mode: `transactions-enabled=true` and `memory-mapped-enabled=false`
- The sample uses `corruption-policy=ARCHIVE_AND_RESET`: confirmed header-corrupted files are archived as `*.corrupt-*` before a fresh store is created
- Lock, permission, disk, and generic I/O failures are never treated as corruption. Startup fails rather than silently switching stores
- If a damaged store cannot be archived cleanly, startup fails. ASL does not hide the damaged store behind a fallback queue path
- When startup recovery archived a store, `GET /api/chaos/storage-recovery` returns the original and archive paths
- For a fully clean manual reset, delete `./data/asl-consumer-sample-queue.db` before starting the sample
- The sample uses the built-in Jackson async payload codec
- This project is for trying the library, not for production business persistence
