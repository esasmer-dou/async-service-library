# Control Plane Benchmark Report

Generated: 2026-03-27 12:18:00 +03:00

Base URL: `http://localhost:8080`

Warmup iterations: 3

Measured iterations: 12

## Runtime Snapshot

| Field | Value |
| --- | --- |
| Service count | 14 |
| Method count | 41 |
| Running methods | 40 |
| Stopped methods | 1 |
| Async active | 3 |
| Queue depth | 9 |
| Failed entries | 2 |
| Overall pressure | HIGH |

## Endpoint Latency

| Target | Status | Min ms | Avg ms | P95 ms | Max ms | Avg bytes |
| --- | --- | --- | --- | --- | --- | --- |
| Admin HTML | 200 | 24.67 | 31.58 | 39.64 | 42.01 | 96542 |
| Admin Summary | 200 | 4.95 | 6.78 | 8.60 | 9.12 | 1437 |
| Admin Services | 200 | 11.71 | 16.48 | 20.36 | 22.04 | 46993 |
| Health API | 200 | 2.94 | 4.11 | 5.10 | 5.64 | 15 |

## Attention Items

- [HIGH] mail.service / publishAudit: Failures detected | Inspect the last error, then replay or clear failed buffer entries.
- [HIGH] customer.service / publishWelcome: Queued work is blocked | Raise consumer threads or switch the lane back to sync mode.
- [WARN] inventory.service / publishSnapshot: Queue pressure | Watch backlog growth and confirm consumers drain work at the expected rate.
- [WARN] chaos.service / triggerFailure: Method is stopped | Re-enable the method when traffic can be resumed safely.

## Notes

- This is an example backlog/incident snapshot for the consumer sample.
- The important operator outcome is not raw latency alone; it is whether `attentionItems` and `overallPressure` accurately reflect blocked or failing lanes.
- After replaying failures and restoring consumers, a follow-up recovery report should show queue depth moving back toward zero and high-severity items clearing.
