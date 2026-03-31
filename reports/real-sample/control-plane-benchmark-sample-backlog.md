# Control Plane Benchmark Report

Generated: 2026-03-28 00:24:33 +03:00

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
| Async active | 2 |
| Queue depth | 4 |
| Failed entries | 1 |
| Overall pressure | HIGH |

## Endpoint Latency

| Target | Status | Min ms | Avg ms | P95 ms | Max ms | Avg bytes |
| --- | --- | --- | --- | --- | --- | --- |
| Admin HTML | 200 | 1134,52 | 1134,52 | 1134,52 | 1134,52 | 475078 |
| Admin Summary | 200 | 76,8 | 76,8 | 76,8 | 76,8 | 3024 |
| Admin Services | 200 | 67,11 | 67,11 | 67,11 | 67,11 | 14773 |
| Health API | 200 | 76,04 | 76,04 | 76,04 | 76,04 | 15 |

## Attention Items

- [HIGH] customer.service / publishWelcome: Queued work is blocked | Raise consumer threads or switch the lane back to sync mode.
- [HIGH] mail.service / publishAudit: Failures detected | Inspect the last error, then replay or clear failed buffer entries.
- [INFO] billing.service / publishInvoiceEvent: Async-capable lane is running inline | Keep sync if latency is acceptable, or move to async when buffering is required.
- [INFO] catalog.service / publishCatalogSnapshot: Async-capable lane is running inline | Keep sync if latency is acceptable, or move to async when buffering is required.
- [INFO] chaos.service / emit: Async-capable lane is running inline | Keep sync if latency is acceptable, or move to async when buffering is required.
- [INFO] fraud.service / publishFraudAlert: Async-capable lane is running inline | Keep sync if latency is acceptable, or move to async when buffering is required.
- [INFO] inventory.service / publishSnapshot: Async-capable lane is running inline | Keep sync if latency is acceptable, or move to async when buffering is required.
- [INFO] ledger.service / publishLedgerEvent: Async-capable lane is running inline | Keep sync if latency is acceptable, or move to async when buffering is required.

## Notes

- This benchmark is a control-plane smoke benchmark, not a synthetic maximum-throughput test.
- Run it against the consumer sample or a staging deployment with representative queue state.
- Pair the report with GET /asl/api/summary snapshots before and after load.
