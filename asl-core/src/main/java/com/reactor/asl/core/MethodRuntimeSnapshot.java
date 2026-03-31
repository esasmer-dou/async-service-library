package com.reactor.asl.core;

public record MethodRuntimeSnapshot(
        String serviceId,
        String methodId,
        String methodName,
        boolean enabled,
        int maxConcurrency,
        boolean asyncCapable,
        ExecutionMode executionMode,
        int consumerThreads,
        String unavailableMessage,
        long successCount,
        long errorCount,
        long rejectedCount,
        int inFlight,
        int peakInFlight,
        String lastError
) {
}
