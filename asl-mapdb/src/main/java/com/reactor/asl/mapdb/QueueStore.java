package com.reactor.asl.mapdb;

import com.reactor.asl.core.MethodBufferSnapshot;

import java.util.Optional;

interface QueueStore {
    EnqueueResult enqueue(NewInvocation invocation);

    Optional<ClaimedInvocation> claimNext(String serviceId, String methodId, long nowEpochMillis);

    boolean complete(long invocationId, long leaseToken);

    boolean renewLease(long invocationId, long leaseToken, long leaseUntilEpochMillis);

    FailureResult fail(
            long invocationId,
            long leaseToken,
            String error,
            String errorType,
            String category,
            long retryAtEpochMillis
    );

    int activateDueRetries(long nowEpochMillis, int limit);

    /**
     * Returns an owned in-progress invocation to the queue without consuming an attempt, for example
     * when the engine stops while the business call is still running.
     */
    boolean release(long invocationId, long leaseToken, String reason);

    /** Renews all given leases in one durable write; returns ids whose lease is no longer owned. */
    java.util.Set<Long> renewLeases(java.util.Map<Long, Long> leaseTokensByInvocationId, long leaseUntilEpochMillis);

    /**
     * Returns in-progress invocations whose lease expired and that no live worker owns to the queue
     * (bounded by {@code maxInterruptions}).
     */
    int reclaimExpiredLeases(long nowEpochMillis, java.util.Set<Long> liveInvocationIds, int limit);

    long nextRetryAtEpochMillis();

    MethodBufferSnapshot snapshot(String serviceId, String methodId, int limit);

    int clear(String serviceId, String methodId);

    boolean delete(String serviceId, String methodId, long invocationId);

    boolean replay(String serviceId, String methodId, long invocationId);

    boolean hasPending(String serviceId, String methodId);

    long projectionVersion();

    boolean awaitProjectionChange(long observedVersion, long timeoutMillis);

    void stopAdmissions();

    record NewInvocation(
            String serviceId,
            String methodId,
            String codecId,
            byte[] payload,
            String payloadSummary,
            String payloadType,
            String payloadVersion,
            String idempotencyKey,
            long enqueuedAtEpochMillis
    ) {
    }

    record EnqueueResult(long invocationId, boolean deduplicated) {
    }

    record ClaimedInvocation(
            long invocationId,
            long leaseToken,
            String serviceId,
            String methodId,
            String codecId,
            byte[] payload,
            String payloadType,
            String payloadVersion,
            int attempt
    ) {
    }

    record FailureResult(boolean applied, boolean retryScheduled, long retryAtEpochMillis) {
        static FailureResult notApplied() {
            return new FailureResult(false, false, 0L);
        }
    }
}
