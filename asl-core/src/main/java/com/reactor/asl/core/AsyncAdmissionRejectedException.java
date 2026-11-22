package com.reactor.asl.core;

public final class AsyncAdmissionRejectedException extends RuntimeException {
    private final Reason reason;

    public AsyncAdmissionRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public AsyncAdmissionRejectedException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        PAYLOAD_TOO_LARGE,
        GLOBAL_CAPACITY,
        LANE_CAPACITY,
        ADMISSION_TIMEOUT,
        WRITER_SATURATED,
        IDEMPOTENCY_CAPACITY,
        ENGINE_CLOSED,
        STORAGE_FAILURE
    }
}
