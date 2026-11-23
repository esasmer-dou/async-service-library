package com.reactor.asl.core;

/**
 * Receives outcomes of ASYNC {@code CompletableFuture<T>} methods that no caller is waiting for: the process
 * restarted between enqueue and completion, the caller's wait timed out, or the call was a duplicate of an
 * earlier one (same idempotency key). Without a handler those outcomes are only counted.
 *
 * <p>Called on an ASL worker thread after the outcome is durable; keep it short and do not block. Exceptions are
 * caught and counted; they never change the outcome.</p>
 */
@FunctionalInterface
public interface AsyncResultHandler {
    void onResult(AsyncResult result);
}
