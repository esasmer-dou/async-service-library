# ASL sample application

**Language:** English | [Türkçe](../tr/asl-consumer-sample/README.md) · [Documentation home](../README.md#documentation)

A runnable Spring Boot application that uses ASL in its service layer. It is for trying the library, not for production.

## What you can try

| Service | Method | Shows |
|---|---|---|
| `mail.service` | `send(java.lang.String)` | SYNC method with max concurrency 6 (from yml) |
| `mail.service` | `publishAudit(java.lang.String)` | method that starts in ASYNC with 2 consumers (from yml) |
| `customer.service` | `publishWelcome(java.lang.String)` | async-capable method that starts in SYNC |
| `quote.service` | `quote(java.lang.String)` | `CompletableFuture` method: same answer in SYNC and ASYNC |
| `chaos.service` | `emit(java.lang.String)` | method you can make fail or slow down on purpose |

The settings are in [`application.yml`](src/main/resources/application.yml).

## Run

From the repository root:

```powershell
mvn -pl asl-consumer-sample -am spring-boot:run
```

The API starts on `http://localhost:8080`.

## Run with the admin portal

Start a local admin server (demo settings: no login, data in memory only):

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

Open `http://localhost:8090/asl-admin/` and select `asl-consumer-sample`. `security.mode=NONE` is for a local demo only.

## Walkthroughs

**Queue work and release it later**

1. In the portal, Tune `customer.service › publishWelcome`: Execution mode `ASYNC`, Consumer threads `0`.
2. Create a customer and call publish a few times. The queue count grows; nothing runs yet.
   ```powershell
   $c = Invoke-RestMethod -Method Post http://localhost:8080/api/customers -ContentType application/json `
        -Body '{"email":"a@company.com","fullName":"A User"}'
   1..5 | % { Invoke-RestMethod -Method Post "http://localhost:8080/api/customers/$($c.id)/publish-welcome" }
   ```
3. Open the method's queue in the portal and look at the entries.
4. Set Consumer threads to `1` and watch the queue drain.

**Fail, then replay**

1. Make the next 3 `chaos.service › emit` calls fail:
   ```powershell
   Invoke-RestMethod -Method Post http://localhost:8080/api/test/scenarios/chaos/emit -ContentType application/json `
     -Body '{"failuresRemaining":3,"processingDelayMillis":0}'
   ```
2. Switch `emit` to ASYNC in the portal, then call it 3 times: `POST http://localhost:8080/api/chaos/emit/{id}`.
3. The portal shows 3 failed entries. Open the queue and use **Bulk… → Replay matching** (Failed, category `BUSINESS`).

**Same answer in SYNC and ASYNC**

```powershell
Invoke-RestMethod http://localhost:8080/api/quotes/ACME     # {"symbol":"ACME","quote":"ACME=…"}
```

Switch `quote.service › quote` to ASYNC in the portal and call it again: the answer is the same, but now it comes through the queue. `http://localhost:8080/api/quotes/FAIL-1` shows a failure, and `/api/quotes/unclaimed` shows outcomes that no caller waited for.

## Test endpoints

These exist only in the sample, to simulate queue behavior.

| Endpoint | Effect |
|---|---|
| `POST /api/test/scenarios/mail/audit` | next `publishAudit` runs fail or slow down |
| `POST /api/test/scenarios/customer/welcome` | the same for `publishWelcome` |
| `POST /api/test/scenarios/inventory/snapshot` | the same for `publishSnapshot` |
| `POST /api/test/scenarios/chaos/emit` | the same for `emit` |
| `POST /api/test/scenarios/reset` | resets all sample state |
| `GET /api/chaos/storage-recovery` | shows whether startup archived a damaged queue file |

Body: `{"failuresRemaining": 1, "processingDelayMillis": 500}`.

Business endpoints: `/api/mails`, `/api/customers`, `/api/inventory`, `/api/quotes/{symbol}`, `/api/health`.

## Tests

- [AslConsumerSampleIntegrationTest](src/test/java/com/reactor/asl/consumer/sample/AslConsumerSampleIntegrationTest.java): success, failure, replay, delete, clear, consumer resize, stop, SYNC vs ASYNC.
- [AslConsumerSampleChaosRecoveryTest](src/test/java/com/reactor/asl/consumer/sample/AslConsumerSampleChaosRecoveryTest.java): pending and failed work survive a restart; running work finishes on graceful shutdown.
- Hard-kill recovery suite: `pwsh -File .\scripts\run-mapdb-abuse-suite.ps1` (runs nightly in CI; see [reports](../reports/README.md)).
- Manual step-by-step recovery tests: [CHAOS_TEST_GUIDE.md](CHAOS_TEST_GUIDE.md).

## Notes

- Business data (mails, customers) is in memory. The queue is a MapDB file at `./data/asl-consumer-sample-queue.db`; delete it for a clean start.
- The sample sets `payload.summary-policy: CODEC` so the portal shows argument text. Production services keep the default `TYPE_ONLY` unless that text is safe to show.
- The sample sets `corruption-policy: ARCHIVE_AND_RESET`: a damaged queue file is moved aside and a new one is created. Production keeps the default `FAIL_FAST`.
