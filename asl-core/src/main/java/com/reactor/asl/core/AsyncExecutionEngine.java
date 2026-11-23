package com.reactor.asl.core;

public interface AsyncExecutionEngine extends AsyncAdminProvider {
    void register(MethodRuntime runtime, AsyncMethodBinding binding);

    void enqueue(MethodRuntime runtime, Object[] arguments);

    /**
     * Enqueues with explicit invocation metadata. Engines that support idempotency must override
     * this. The default accepts invocations without an idempotency key and rejects keyed ones,
     * because silently dropping the key would turn a deduplicated admission into a duplicate.
     */
    default void enqueue(MethodRuntime runtime, Object[] arguments, AsyncInvocationContext context) {
        if (context != null && context.idempotencyKey() != null) {
            throw new UnsupportedOperationException(
                    getClass().getName() + " does not support @AsyncIdempotencyKey; override "
                            + "enqueue(MethodRuntime, Object[], AsyncInvocationContext)"
            );
        }
        enqueue(runtime, arguments);
    }

    /**
     * Enqueues an invocation of a method that returns {@code CompletableFuture<T>} and returns a future that
     * completes with the outcome of the queued execution in this process. The future completes exceptionally with
     * {@link AsyncResultUnavailableException} when the outcome cannot be handed over (shutdown, wait timeout,
     * duplicate); the invocation itself is not lost and its outcome then goes to the {@link AsyncResultHandler}.
     *
     * @throws UnsupportedOperationException when the engine cannot return results
     */
    default <T> java.util.concurrent.CompletableFuture<T> enqueueForResult(
            MethodRuntime runtime,
            Object[] arguments,
            AsyncInvocationContext context
    ) {
        throw new UnsupportedOperationException(getClass().getName()
                + " cannot return results of ASYNC calls; CompletableFuture methods must stay SYNC with this engine");
    }

    /** Receives outcomes nobody waits for; see {@link AsyncResultHandler}. Engines without results ignore it. */
    default void resultHandler(AsyncResultHandler handler) {
    }
}
