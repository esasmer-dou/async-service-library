package com.reactor.asl.mapdb;

/**
 * Queue depth of one async lane.
 *
 * @param oldestPendingAgeMillis age of the oldest pending invocation, {@code 0} when none is pending
 */
public record MapDbLaneDepth(
        String serviceId,
        String methodId,
        long pending,
        long inProgress,
        long retryWait,
        long failed,
        long oldestPendingAgeMillis
) {
}
