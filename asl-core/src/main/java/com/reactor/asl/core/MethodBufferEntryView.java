package com.reactor.asl.core;

import java.time.Instant;

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
        String payloadVersion
) {
}
