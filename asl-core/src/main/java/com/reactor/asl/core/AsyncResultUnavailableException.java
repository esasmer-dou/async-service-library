package com.reactor.asl.core;

/**
 * Completes a caller's future when the outcome of its ASYNC invocation cannot be handed to it. The invocation
 * itself is not lost: it stays queued (or keeps running) and its outcome goes to the {@link AsyncResultHandler}.
 */
public final class AsyncResultUnavailableException extends RuntimeException {
    private final Reason reason;
    private final String invocationId;

    public AsyncResultUnavailableException(Reason reason, String invocationId, String message) {
        super(message);
        this.reason = reason;
        this.invocationId = invocationId;
    }

    public Reason reason() {
        return reason;
    }

    /** Id of the queued invocation, to correlate with the result handler or the admin portal. */
    public String invocationId() {
        return invocationId;
    }

    public enum Reason {
        /** The engine is shutting down; the invocation stays queued and runs after the restart. */
        SHUTDOWN,
        /** Nothing completed within the result wait timeout; the invocation still runs. */
        WAIT_TIMEOUT,
        /** An invocation with the same idempotency key was accepted before; its outcome went to its first caller. */
        DUPLICATE
    }
}
