package com.reactor.asl.core;

/**
 * The {@code CompletableFuture} returned by an ASYNC future method did not complete within the engine's completion
 * timeout. The invocation fails with error category {@code TIMEOUT} and is retried if attempts are left.
 */
public final class AsyncCompletionTimeoutException extends RuntimeException {
    public AsyncCompletionTimeoutException(String message) {
        super(message);
    }
}
