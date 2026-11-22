package com.reactor.asl.mapdb;

/**
 * Persisted async invocation.
 *
 * @param attempts      business execution attempts that were started and not given back
 * @param interruptions executions interrupted by shutdown, crash or an expired lease; bounded by
 *                      {@code maxInterruptions} so a payload that repeatedly kills the process
 *                      cannot loop forever
 */
record QueueRecord(
        long id,
        int laneId,
        String codecId,
        byte[] payload,
        String payloadSummary,
        String payloadType,
        String payloadVersion,
        String idempotencyKey,
        AsyncInvocationState state,
        long enqueuedAtEpochMillis,
        long availableAtEpochMillis,
        long leaseUntilEpochMillis,
        long leaseToken,
        int attempts,
        String lastError,
        String lastErrorType,
        String errorCategory,
        int interruptions
) {
    QueueRecord(
            long id,
            int laneId,
            String codecId,
            byte[] payload,
            String payloadSummary,
            String payloadType,
            String payloadVersion,
            String idempotencyKey,
            AsyncInvocationState state,
            long enqueuedAtEpochMillis,
            long availableAtEpochMillis,
            long leaseUntilEpochMillis,
            long leaseToken,
            int attempts,
            String lastError,
            String lastErrorType,
            String errorCategory
    ) {
        this(id, laneId, codecId, payload, payloadSummary, payloadType, payloadVersion, idempotencyKey, state,
                enqueuedAtEpochMillis, availableAtEpochMillis, leaseUntilEpochMillis, leaseToken, attempts,
                lastError, lastErrorType, errorCategory, 0);
    }

    QueueRecord claimed(long leaseUntilEpochMillis, long leaseToken) {
        return with(AsyncInvocationState.IN_PROGRESS, availableAtEpochMillis, leaseUntilEpochMillis, leaseToken,
                attempts + 1, lastError, lastErrorType, errorCategory, interruptions);
    }

    QueueRecord failed(String error, String errorType, String category) {
        return terminalOrRetry(AsyncInvocationState.FAILED, 0L, error, errorType, category);
    }

    QueueRecord retryAt(long retryAtEpochMillis, String error, String errorType, String category) {
        return terminalOrRetry(AsyncInvocationState.RETRY_WAIT, retryAtEpochMillis, error, errorType, category);
    }

    QueueRecord leaseRenewed(long renewedLeaseUntilEpochMillis) {
        if (state != AsyncInvocationState.IN_PROGRESS) {
            throw new IllegalStateException("Only in-progress queue records have renewable leases");
        }
        return with(state, availableAtEpochMillis, renewedLeaseUntilEpochMillis, leaseToken,
                attempts, lastError, lastErrorType, errorCategory, interruptions);
    }

    QueueRecord replayed() {
        return with(AsyncInvocationState.PENDING, 0L, 0L, 0L, 0, null, null, null, 0);
    }

    QueueRecord pendingAfterRecovery(String error, String errorType, String category) {
        return terminalOrRetry(AsyncInvocationState.PENDING, 0L, error, errorType, category);
    }

    /**
     * Gives the interrupted attempt back and returns the record to the queue. The execution may or
     * may not have produced its side effect, which is exactly the at-least-once window.
     */
    QueueRecord interrupted(String error, String errorType, String category) {
        return with(AsyncInvocationState.PENDING, 0L, 0L, 0L, Math.max(0, attempts - 1),
                error, errorType, category, interruptions + 1);
    }

    /** Terminal failure after too many interruptions; the interruption itself is still counted. */
    QueueRecord failedAfterInterruptions(String error, String errorType, String category) {
        return with(AsyncInvocationState.FAILED, 0L, 0L, 0L, attempts, error, errorType, category,
                interruptions + 1);
    }

    private QueueRecord terminalOrRetry(
            AsyncInvocationState targetState,
            long availableAt,
            String error,
            String errorType,
            String category
    ) {
        return with(targetState, availableAt, 0L, 0L, attempts, error, errorType, category, interruptions);
    }

    private QueueRecord with(
            AsyncInvocationState targetState,
            long availableAt,
            long leaseUntil,
            long token,
            int attemptCount,
            String error,
            String errorType,
            String category,
            int interruptionCount
    ) {
        return new QueueRecord(
                id,
                laneId,
                codecId,
                payload,
                payloadSummary,
                payloadType,
                payloadVersion,
                idempotencyKey,
                targetState,
                enqueuedAtEpochMillis,
                availableAt,
                leaseUntil,
                token,
                attemptCount,
                error,
                errorType,
                category,
                interruptionCount
        );
    }
}
