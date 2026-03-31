package com.reactor.asl.consumer.sample;

public record AsyncScenarioSnapshot(
        String scenarioId,
        int failuresRemaining,
        long processingDelayMillis,
        long startedCount,
        long successCount,
        long failureCount
) {
}
