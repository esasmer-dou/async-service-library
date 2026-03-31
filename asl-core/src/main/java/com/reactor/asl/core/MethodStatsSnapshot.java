package com.reactor.asl.core;

public record MethodStatsSnapshot(
        long successCount,
        long errorCount,
        long rejectedCount,
        int inFlight,
        int peakInFlight
) {
}
