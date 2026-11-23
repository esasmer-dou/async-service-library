package com.reactor.asl.core;

public final class MethodUnavailableException extends RuntimeException {
    private final String serviceId;
    private final String methodId;
    private final Reason reason;

    public MethodUnavailableException(String serviceId, String methodId, String message) {
        this(serviceId, methodId, message, Reason.DISABLED, true);
    }

    /**
     * @param stackTrace {@code false} for the governed hot path: a rejection is an expected control
     *                   decision, and capturing a stack trace made it ~40x more expensive than a
     *                   successful governed call
     */
    public MethodUnavailableException(
            String serviceId,
            String methodId,
            String message,
            Reason reason,
            boolean stackTrace
    ) {
        super(message, null, stackTrace, stackTrace);
        this.serviceId = serviceId;
        this.methodId = methodId;
        this.reason = reason == null ? Reason.DISABLED : reason;
    }

    public String serviceId() {
        return serviceId;
    }

    public String methodId() {
        return methodId;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        /** The method was stopped by an operator or by its initial configuration. */
        DISABLED,
        /** The method reached its concurrency limit. */
        CONCURRENCY_LIMIT,
        /** The method reached its calls-per-second limit (SYNC mode; ASYNC calls are queued instead). */
        RATE_LIMIT
    }
}
