package com.reactor.asl.core;

import java.time.Instant;

/**
 * @param interruptions times the execution was interrupted (shutdown, crash, expired lease) and
 *                      returned to the queue without consuming an attempt
 */
public record MethodBufferEntryView(
        String entryId,
        String state,
        String summary,
        Instant enqueuedAt,
        int attempts,
        String lastError,
        String lastErrorType,
        String errorCategory,
        String codecId,
        String payloadType,
        String payloadVersion,
        int interruptions
) {
    /** Source-compatible constructor for providers built against the original component list. */
    public MethodBufferEntryView(
            String entryId,
            String state,
            String summary,
            Instant enqueuedAt,
            int attempts,
            String lastError,
            String lastErrorType,
            String errorCategory,
            String codecId,
            String payloadType,
            String payloadVersion
    ) {
        this(entryId, state, summary, enqueuedAt, attempts, lastError, lastErrorType, errorCategory,
                codecId, payloadType, payloadVersion, 0);
    }
}
