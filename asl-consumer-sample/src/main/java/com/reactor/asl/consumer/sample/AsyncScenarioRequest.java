package com.reactor.asl.consumer.sample;

public record AsyncScenarioRequest(
        Integer failuresRemaining,
        Long processingDelayMillis
) {
}
