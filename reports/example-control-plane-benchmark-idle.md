# Control Plane Benchmark Report

Generated: 2026-03-27 12:05:00 +03:00

Base URL: `http://localhost:8080`

Warmup iterations: 3

Measured iterations: 12

## Runtime Snapshot

| Field | Value |
| --- | --- |
| Service count | 14 |
| Method count | 41 |
| Running methods | 41 |
| Stopped methods | 0 |
| Async active | 0 |
| Queue depth | 0 |
| Failed entries | 0 |
| Overall pressure | LOW |

## Endpoint Latency

| Target | Status | Min ms | Avg ms | P95 ms | Max ms | Avg bytes |
| --- | --- | --- | --- | --- | --- | --- |
| Admin HTML | 200 | 19.41 | 24.83 | 31.10 | 33.42 | 94126 |
| Admin Summary | 200 | 3.84 | 5.19 | 6.72 | 7.13 | 1022 |
| Admin Services | 200 | 9.36 | 12.74 | 15.02 | 16.49 | 45188 |
| Health API | 200 | 2.81 | 3.65 | 4.20 | 4.77 | 15 |

## Attention Items

- [INFO] billing.service / issueInvoice: Async-capable lane is running inline | Keep sync if latency is acceptable, or move to async when buffering is required.
- [INFO] catalog.service / rebuildIndex: Async-capable lane is running inline | Keep sync if latency is acceptable, or move to async when buffering is required.
- [INFO] customer.service / publishWelcome: Async-capable lane is running inline | Keep sync if latency is acceptable, or move to async when buffering is required.

## Notes

- This is an example idle-baseline report for the consumer sample.
- Pressure is low, queue depth is zero and the summary endpoint is expected to stay stable across repeated runs.
- Minor `INFO` items are acceptable here because they simply describe async-capable lanes that are intentionally still running in sync mode.
