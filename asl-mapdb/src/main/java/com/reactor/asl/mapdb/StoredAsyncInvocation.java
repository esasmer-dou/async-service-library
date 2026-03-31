package com.reactor.asl.mapdb;

import java.io.Serial;
import java.io.Serializable;

final class StoredAsyncInvocation implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final long id;
    private final String serviceId;
    private final String methodId;
    private final String codecId;
    private final byte[] payload;
    private final String payloadSummary;
    private final String payloadType;
    private final String payloadVersion;
    private final AsyncInvocationState state;
    private final long enqueuedAtEpochMillis;
    private final int attempts;
    private final String lastError;
    private final String lastErrorType;
    private final String errorCategory;

    StoredAsyncInvocation(
            long id,
            String serviceId,
            String methodId,
            String codecId,
            byte[] payload,
            String payloadSummary,
            String payloadType,
            String payloadVersion,
            AsyncInvocationState state,
            long enqueuedAtEpochMillis,
            int attempts,
            String lastError,
            String lastErrorType,
            String errorCategory
    ) {
        this.id = id;
        this.serviceId = serviceId;
        this.methodId = methodId;
        this.codecId = codecId;
        this.payload = payload;
        this.payloadSummary = payloadSummary;
        this.payloadType = payloadType;
        this.payloadVersion = payloadVersion;
        this.state = state;
        this.enqueuedAtEpochMillis = enqueuedAtEpochMillis;
        this.attempts = attempts;
        this.lastError = lastError;
        this.lastErrorType = lastErrorType;
        this.errorCategory = errorCategory;
    }

    long id() {
        return id;
    }

    String serviceId() {
        return serviceId;
    }

    String methodId() {
        return methodId;
    }

    String codecId() {
        return codecId;
    }

    byte[] payload() {
        return payload;
    }

    String payloadSummary() {
        return payloadSummary;
    }

    String payloadType() {
        return payloadType;
    }

    String payloadVersion() {
        return payloadVersion;
    }

    AsyncInvocationState state() {
        return state;
    }

    long enqueuedAtEpochMillis() {
        return enqueuedAtEpochMillis;
    }

    int attempts() {
        return attempts;
    }

    String lastError() {
        return lastError;
    }

    String lastErrorType() {
        return lastErrorType;
    }

    String errorCategory() {
        return errorCategory;
    }

    StoredAsyncInvocation withState(AsyncInvocationState state) {
        return new StoredAsyncInvocation(
                id,
                serviceId,
                methodId,
                codecId,
                payload,
                payloadSummary,
                payloadType,
                payloadVersion,
                state,
                enqueuedAtEpochMillis,
                attempts,
                lastError,
                lastErrorType,
                errorCategory
        );
    }

    StoredAsyncInvocation failed(String lastError, String lastErrorType, String errorCategory) {
        return new StoredAsyncInvocation(
                id,
                serviceId,
                methodId,
                codecId,
                payload,
                payloadSummary,
                payloadType,
                payloadVersion,
                AsyncInvocationState.FAILED,
                enqueuedAtEpochMillis,
                attempts + 1,
                lastError,
                lastErrorType,
                errorCategory
        );
    }

    StoredAsyncInvocation replay() {
        return new StoredAsyncInvocation(
                id,
                serviceId,
                methodId,
                codecId,
                payload,
                payloadSummary,
                payloadType,
                payloadVersion,
                AsyncInvocationState.PENDING,
                enqueuedAtEpochMillis,
                attempts,
                null,
                null,
                null
        );
    }
}
