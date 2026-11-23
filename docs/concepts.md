# Concepts

**Language:** English | [Türkçe](../tr/docs/concepts.md) · [Documentation home](../README.md#documentation)

This page explains every control ASL gives you over a method: what it means, a small example, and when to use it. For ready-made combinations see [Scenarios](scenarios.md).

## How a call is governed

```
caller ──> generated wrapper ──> [enabled?] ──> [rate limit] ──> [max concurrency] ──> your implementation
                                    │
                                    └── ASYNC mode: the call is written to the durable queue instead,
                                        and a consumer runs it later
```

Each check either lets the call through or rejects it at once with a `MethodUnavailableException`. Rejections carry no stack trace, so they stay cheap under load. Its `reason()` tells why: `DISABLED`, `RATE_LIMIT` or `CONCURRENCY_LIMIT`.

## The controls at a glance

| Control | Default | In yml (`asl.runtime.services…`) | In the portal (Tune) | Counts per |
|---|---|---|---|---|
| [Enabled](#enabled) | on | `enabled` | Method: Running / Stopped | replica |
| [Max concurrency](#max-concurrency) | unlimited | `max-concurrency` | Max concurrency | replica |
| [Rate limit](#rate-limit) | none | `rate-limit` | Rate limit (calls/s) | replica |
| [Execution mode](#sync-and-async) | SYNC | `execution-mode` | Execution mode | replica |
| [Consumers](#consumers) | 1 (async-capable methods) | `consumer-threads` | Consumer threads | replica |
| [Unavailable message](#unavailable-message) | `Method is disabled` | `unavailable-message` | – | – |

Every limit applies **per replica**: `max-concurrency: 20` on three replicas allows 60 calls at once in total.

## Enabled

**What it is.** Whether the method accepts calls. A stopped method rejects every call with reason `DISABLED` and the [unavailable message](#unavailable-message). In ASYNC mode a stopped method rejects before anything is queued.

**Use it when** a dependency is down and calls only make it worse, during maintenance, or to switch a feature off in an emergency.

**Watch out.** Stopping does not touch work already in the queue. To pause queued work but keep accepting calls, set [consumers](#consumers) to 0 instead.

## Max concurrency

**What it is.** The most calls of this method that may run at the same time on one replica. A call over the limit is rejected at once with reason `CONCURRENCY_LIMIT`; it does not wait. In ASYNC mode it is also the most queued calls that run at the same time.

**Example.** `max-concurrency: 10`. The 11th simultaneous call is rejected; the next call after one of the ten finishes is accepted.

**Use it when** the method uses a limited resource, such as a connection pool of 20 or a partner that allows 10 parallel requests. It keeps a slow dependency from taking every request thread (bulkhead).

**Watch out.** Callers must handle the rejection (retry later or return 503). If they cannot, use ASYNC mode, where calls wait in the queue instead of being rejected.

## Rate limit

**What it is.** The most calls of this method that may **start per second** on one replica. `0` means no limit, and it also removes a limit set in yml. Up to one second's worth of calls may start at once (burst); after that, calls start at the configured pace.

| Mode | A call over the rate… |
|---|---|
| SYNC | is rejected at once with reason `RATE_LIMIT` |
| ASYNC | is still queued; consumers start at most N runs per second, so the queue absorbs the excess |

**Example.** `rate-limit: 5` and 30 calls arrive within one second. In SYNC mode about 10 are accepted (5 burst + 5 for the elapsed second) and the rest rejected. In ASYNC mode all 30 are queued and finish in about 5 seconds.

**Use it when** a partner API or a database has a quota per second. Max concurrency limits parallel work; rate limit limits pace. You can use both.

## SYNC and ASYNC

**What it is.** How an `asyncCapable` method runs. Methods start in SYNC; you switch them in yml (`execution-mode: ASYNC`) or in the portal, without changing callers.

| | SYNC | ASYNC |
|---|---|---|
| Who runs the method | the caller's thread, now | a consumer, later, from the durable queue |
| The caller gets | the result or the exception | `void`: returns once the call is stored. `CompletableFuture<T>`: a future of the queued run (see [below](#completablefuture-methods)) |
| A failure | goes to the caller | is stored as a failed queue entry; it can be retried, replayed or deleted |
| Too many calls | rejected (max concurrency, rate limit) | queued, up to the queue limits |
| Survives a restart | no | yes, the queue is a local MapDB file |

**Use ASYNC when** the caller does not need the result right away and calls must not be lost or rejected during a spike or a short outage: emails, audit events, notifications, exports.

**Requirements.**

- The method is `asyncCapable` and returns `void` or `CompletableFuture<T>`.
- The MapDB engine is enabled (`asl.async.mapdb.enabled: true`).
- The method is idempotent: a queued call can run again after a crash (see [Delivery guarantee](#delivery-guarantee)).

When you switch back to SYNC, new calls run immediately. Calls already queued keep being processed by the consumers (`drain-backlog-in-sync-mode`, on by default).

## Consumers

**What it is.** How many of this method's queued calls may run at the same time on one replica. It only matters while the method has queued work: in ASYNC mode, or while a backlog drains after switching back to SYNC.

**Example.** `consumer-threads: 2` on two replicas means at most 4 queued calls running at once in total.

| Value | Effect |
|---|---|
| `0` | Calls are accepted and stored, but nothing runs. Use it to hold work during an outage and release it later. |
| `1` | One at a time, in order. |
| `N` | Up to N in parallel. |

**Watch out.** Consumers do not get their own threads. They take work from the shared worker pool (`asl.async.mapdb.workers.max-threads`). The real parallelism is the smallest of three values: consumers, [max concurrency](#max-concurrency), and the pool size. A [rate limit](#rate-limit) also caps how many runs start per second. For `CompletableFuture` methods a consumer stays busy until the method's future completes.

## Unavailable message

The text a caller receives when the method is stopped. Set it per method (`@GovernedMethod(unavailableMessage = …)` or `unavailable-message` in yml), or for the whole application (`asl.runtime.default-unavailable-message`).

## Where a setting comes from

When the same control is set in several places, the highest one wins:

1. Portal override for **this replica**.
2. Portal override for **the whole application**.
3. yml (`asl.runtime.services…`).
4. Annotation (`@GovernedMethod`).
5. Library default.

Removing a portal override returns the method to its yml or annotation value. A portal override can also be **timed** (for example 1 hour); when the time is up, the previous value comes back. See [Admin server and portal](admin-server.md#timed-overrides).

Queue, worker and retry settings (`asl.async.mapdb.*`) are read at startup only. Changing them needs a restart.

## The queue

### Entry states

| State | Meaning |
|---|---|
| `PENDING` | stored, waiting for a consumer |
| `IN_PROGRESS` | a consumer is running it |
| `RETRY_WAIT` | failed, will be retried after a back-off |
| `FAILED` | failed for good; stays until you replay or delete it |

### Failures and retries

- `asl.async.mapdb.retry.max-attempts` (default `1`) is the total number of tries. With the default, a failed run goes straight to `FAILED`.
- More attempts wait with a growing back-off (`retry.initial-backoff-millis`, `retry.max-backoff-millis`).
- Every failure gets a category you can filter on:

| Category | Cause |
|---|---|
| `BUSINESS` | your method threw an exception |
| `TIMEOUT` | the run took longer than `execution-timeout-millis`, or a `CompletableFuture` did not complete in time |
| `DECODE` | the stored arguments could not be read back (for example the class changed) |
| `MIGRATION` | a payload migration failed |

From the portal you can replay a failed entry, delete it, or do either for every entry that matches a filter (see [Bulk actions](admin-server.md#queue-actions)).

### Delivery guarantee

Delivery is **at-least-once**. If the process stops (crash, kill, expired lease) while a call is running, the call goes back to the queue and runs again. The interrupted run does not use up a retry attempt, but after `retry.max-interruptions` (default 3) interruptions the call is marked `FAILED`. This stops one bad payload from crashing the service forever.

A crash after your side effect but before ASL records completion makes the call run twice. Make the method idempotent, for example with a unique key in the target table.

`@AsyncIdempotencyKey` stops the same key from being **queued** twice (kept for 24 hours by default). It cannot make an external side effect transactional.

### Capacity

The queue never grows without bound. By default it holds at most:

| Limit | Default |
|---|---|
| entries in total | 100,000 |
| payload bytes in total | 512 MiB |
| entries per method | 10,000 |
| payload bytes per method | 64 MiB |
| bytes per call | 1 MiB |

A call over a limit is rejected with `AsyncAdmissionRejectedException` and a reason (for example `LANE_CAPACITY`). Raise a limit for one method only with a lane override; see [configuration](configuration.md#admission-and-capacity).

## CompletableFuture methods

A method that returns `CompletableFuture<T>` can be `asyncCapable`. The caller sees the same result in both modes, so the method can be switched without touching its callers.

- **SYNC.** You get the method's own future. Success and errors are counted when the future completes.
- **ASYNC.** The call is queued and you get a future of the queued run. A consumer runs the method, waits for its future, then completes yours with the value or the failure.

Safeguards:

| If… | then… |
|---|---|
| the method's future never completes | after `workers.completion-timeout-millis` (5 min) the run fails as `TIMEOUT` |
| nobody processes the call (consumers 0, long backlog) | after `workers.result-wait-timeout-millis` (10 min) your future fails with `AsyncResultUnavailableException(WAIT_TIMEOUT)`. The call stays queued and still runs. |
| the application shuts down while you wait | your future fails with `AsyncResultUnavailableException(SHUTDOWN)`; the call runs after the restart |
| more than `workers.max-pending-results` (10,000) callers are waiting | new calls are rejected before anything is queued |

Outcomes that nobody waits for any more (after a restart or a wait timeout) go to an `AsyncResultHandler` bean if you define one:

```java
@Bean
AsyncResultHandler quoteResults(QuoteStore store) {
    return result -> store.save(result.invocationId(), result.value(), result.failure());
}
```

The value is kept in memory only. Store anything that must survive a restart yourself.

## Reference

- Every property: [Configuration reference](configuration.md)
- What to set for a given situation: [Scenarios](scenarios.md)
