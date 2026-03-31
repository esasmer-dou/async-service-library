# Control Plane Benchmark Report

Generated: 2026-03-28 00:24:24 +03:00

Base URL: `http://localhost:18080`

Warmup iterations: 1

Measured iterations: 1

## Runtime Snapshot

| Field | Value |
| --- | --- |
| Service count | 14 |
| Method count | 40 |
| Running methods | 40 |
| Stopped methods | 0 |
| Async active | 0 |
| Queue depth | 0 |
| Failed entries | 0 |
| Overall pressure | LOW |

## Endpoint Latency

| Target | Status | Min ms | Avg ms | P95 ms | Max ms | Avg bytes |
| --- | --- | --- | --- | --- | --- | --- |
| Admin HTML | 200 | 817,7 | 817,7 | 817,7 | 817,7 | 462667 |
| Admin Summary | 200 | 45,67 | 45,67 | 45,67 | 45,67 | 3127 |
| Admin Services | 200 | 73,64 | 73,64 | 73,64 | 73,64 | 14663 |
| Health API | 200 | 32,93 | 32,93 | 32,93 | 32,93 | 15 |

## Attention Items

- [INFO] billing.service / publishInvoiceEvent: Async-capable lane is running inline | Keep sync if latency is acceptable, or move to async when buffering is required.
- [INFO] catalog.service / publishCatalogSnapshot: Async-capable lane is running inline | Keep sync if latency is acceptable, or move to async when buffering is required.
- [INFO] chaos.service / emit: Async-capable lane is running inline | Keep sync if latency is acceptable, or move to async when buffering is required.
- [INFO] customer.service / publishWelcome: Async-capable lane is running inline | Keep sync if latency is acceptable, or move to async when buffering is required.
- [INFO] fraud.service / publishFraudAlert: Async-capable lane is running inline | Keep sync if latency is acceptable, or move to async when buffering is required.
- [INFO] inventory.service / publishSnapshot: Async-capable lane is running inline | Keep sync if latency is acceptable, or move to async when buffering is required.
- [INFO] ledger.service / publishLedgerEvent: Async-capable lane is running inline | Keep sync if latency is acceptable, or move to async when buffering is required.
- [INFO] mail.service / publishAudit: Async-capable lane is running inline | Keep sync if latency is acceptable, or move to async when buffering is required.

## Notes

- This benchmark is a control-plane smoke benchmark, not a synthetic maximum-throughput test.
- Run it against the consumer sample or a staging deployment with representative queue state.
- Pair the report with GET /asl/api/summary snapshots before and after load.
