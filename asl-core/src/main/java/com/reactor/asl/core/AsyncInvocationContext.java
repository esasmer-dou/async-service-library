package com.reactor.asl.core;

import java.util.Objects;

/**
 * Explicit metadata for one asynchronous admission. The context is intentionally
 * passed by the caller instead of being hidden in a thread-local.
 */
public record AsyncInvocationContext(String idempotencyKey) {
    private static final AsyncInvocationContext NONE = new AsyncInvocationContext(null);

    public AsyncInvocationContext {
        if (idempotencyKey != null && idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must be null or non-blank");
        }
    }

    public static AsyncInvocationContext none() {
        return NONE;
    }

    /**
     * Context for a value taken from an {@code @AsyncIdempotencyKey} parameter. {@code null} or blank
     * means "no idempotency", matching the synchronous path where the key has no effect, so a
     * runtime switch to ASYNC never turns an accepted call into an exception.
     */
    public static AsyncInvocationContext ofNullable(String idempotencyKey) {
        return idempotencyKey == null || idempotencyKey.isBlank()
                ? NONE
                : new AsyncInvocationContext(idempotencyKey);
    }

    public static AsyncInvocationContext idempotent(String idempotencyKey) {
        return new AsyncInvocationContext(Objects.requireNonNull(idempotencyKey, "idempotencyKey"));
    }
}
