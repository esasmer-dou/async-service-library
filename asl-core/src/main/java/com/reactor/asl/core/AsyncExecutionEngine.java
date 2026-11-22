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
}
