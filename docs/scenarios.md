# Scenarios

**Language:** English | [Türkçe](../tr/docs/scenarios.md) · [Documentation home](../README.md#documentation)

Common situations and the settings that handle them. Each scenario names the control to use, where to set it, and what to expect. The controls themselves are explained in [Concepts](concepts.md).

"Portal" means the admin portal's **Tune** dialog for the method (all replicas, or one replica). "yml" means `asl.runtime.services["service"].methods["method"]` (see [Getting started](getting-started.md#5-change-the-start-values-in-yml-optional)).

| Situation | Use |
|---|---|
| [A dependency is slow and ties up threads](#1-a-dependency-is-slow-and-ties-up-threads) | max concurrency |
| [A partner allows N requests per second](#2-a-partner-allows-n-requests-per-second) | rate limit |
| [Traffic spikes, but the work can wait](#3-traffic-spikes-but-the-work-can-wait) | ASYNC + consumers |
| [A dependency is down for a while](#4-a-dependency-is-down-for-a-while) | consumers 0, or stop |
| [The bug is fixed; failed calls must run again](#5-the-bug-is-fixed-failed-calls-must-run-again) | bulk replay |
| [A temporary change during an incident](#6-a-temporary-change-during-an-incident) | timed override |
| [Only one replica misbehaves](#7-only-one-replica-misbehaves) | replica override |
| [A `CompletableFuture` method should use the queue](#8-a-completablefuture-method-should-use-the-queue) | ASYNC |
| [One payload keeps failing or crashing the service](#9-one-payload-keeps-failing-or-crashing-the-service) | delete, max interruptions |
| [Work must not run twice](#10-work-must-not-run-twice) | idempotency key |

## 1. A dependency is slow and ties up threads

A report service slows down, and every request waits on it until the web server runs out of threads.

- **Set:** max concurrency to what the dependency can handle, for example 10. Do it in yml for the long term, in the portal for the moment.
- **Result:** at most 10 calls wait on the dependency per replica. Others are rejected at once with `CONCURRENCY_LIMIT`, and the rest of the application stays responsive.
- **Also:** return a clear error to the caller (for example HTTP 503 with `Retry-After`). If the calls must not be rejected, see scenario 3.

## 2. A partner allows N requests per second

An SMS provider allows 50 requests per second for the whole account. You run 2 replicas.

- **Set:** rate limit 25 (50 ÷ 2 replicas).
- **SYNC:** calls above the pace are rejected with `RATE_LIMIT`.
- **ASYNC:** nothing is rejected. Calls wait in the queue, and consumers start at most 25 per second on each replica. This is usually what you want for quotas.
- **Note:** the limit is per replica. If you scale from 2 to 4 replicas, halve the value.

## 3. Traffic spikes, but the work can wait

A campaign sends 20,000 welcome emails within minutes. The caller does not need the result.

- **Set:** the method is `asyncCapable`. Switch it to ASYNC and set consumers to 4 (and, if needed, a rate limit for the mail provider).
- **Result:** calls return as soon as they are stored. Four run in parallel per replica, and the queue absorbs the peak.
- **Check:** the queue limit per method is 10,000 entries by default. Raise it with a lane override if the peak is larger (see [configuration](configuration.md#admission-and-capacity)). Watch `asl.mapdb.lane.oldest.pending.age`: the age of the oldest waiting call tells you if consumers keep up.

## 4. A dependency is down for a while

The mail provider is down for 30 minutes.

| Option | Callers | Queued work | Use it when |
|---|---|---|---|
| ASYNC + **consumers 0** | keep working; calls are stored | waits, nothing fails | the work must not be lost |
| **Stop** the method | rejected with `DISABLED` | untouched | the calls are worthless while the dependency is down |

When the provider is back, set consumers to their normal value; the backlog drains on its own. With a timed override (scenario 6) it comes back on its own.

## 5. The bug is fixed; failed calls must run again

A bug made 3,000 calls fail with `BUSINESS` errors last night. The fix is deployed.

- **In the portal:** open the method's queue, choose **Bulk…**, select *Failed*, category `BUSINESS`, the time window, and at most N entries per replica. Then **Replay matching**.
- **Result:** each replica moves the matching failed entries back to the queue in one step and reports how many. Entries that are running are never touched.
- **Tip:** start with a small N (for example 100), check the result, then repeat with a larger N (at most 10,000 per command).

## 6. A temporary change during an incident

You lower max concurrency to 2 during an incident and do not want it to stay that way forever.

- **Set:** in Tune, choose **Keep for: 1 hour** next to the change.
- **Result:** after one hour the method returns to the setting it had before (or to its yml value). The return is recorded in the audit log as `override.expired`. The portal shows the time left next to the method.
- Making another timed change keeps the original "before" value. A permanent change or removing the override cancels the timer.

## 7. Only one replica misbehaves

One pod has a bad network path and its calls time out.

- **Set:** in the replica table, use **Tune this replica** and stop the method or lower its limits on that replica only.
- **Result:** other replicas keep their settings. A replica override is kept in memory and disappears when the replica goes away.

## 8. A `CompletableFuture` method should use the queue

`CompletableFuture<Quote> quote(String symbol)` is called by many services, and you want its calls to survive restarts and spikes.

- **Set:** `@GovernedMethod(asyncCapable = true)`, then switch it to ASYNC.
- **Result:** callers keep the same code and the same result. They now get a future of the queued run.
- **Plan for:** callers may wait up to `result-wait-timeout-millis` (10 min) by default. If they need an answer sooner, use `orTimeout(...)` on the future. Define an `AsyncResultHandler` to keep outcomes that arrive after a restart. Details: [Concepts](concepts.md#completablefuture-methods).

## 9. One payload keeps failing or crashing the service

- **Failing:** with the default `retry.max-attempts: 1` it lands in `FAILED` after one try. Look at it in the queue dialog (summary, error type, last error), then delete or replay it.
- **Crashing the JVM:** after `retry.max-interruptions` (3) interrupted runs it is marked `FAILED` and is no longer picked up.
- **Many at once:** **Bulk… → Delete matching** with a category and a time window.

## 10. Work must not run twice

- Add `@AsyncIdempotencyKey` to a `String` parameter (for example an event id). A repeated call with the same key is not queued again for 24 hours.
- Delivery is still at-least-once: after a crash at the wrong moment, a stored call can run twice. Make the side effect idempotent too, for example with a unique constraint on the event id in your table.
