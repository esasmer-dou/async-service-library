package com.reactor.asl.consumer.sample;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class AsyncScenarioControl {
    private final String scenarioId;
    private final AtomicInteger failuresRemaining = new AtomicInteger();
    private final AtomicLong processingDelayMillis = new AtomicLong();
    private final AtomicLong startedCount = new AtomicLong();
    private final AtomicLong successCount = new AtomicLong();
    private final AtomicLong failureCount = new AtomicLong();

    AsyncScenarioControl(String scenarioId) {
        this.scenarioId = scenarioId;
    }

    void configure(Integer failuresRemaining, Long processingDelayMillis) {
        if (failuresRemaining != null) {
            if (failuresRemaining < 0) {
                throw new IllegalArgumentException("failuresRemaining must be zero or positive");
            }
            this.failuresRemaining.set(failuresRemaining);
        }
        if (processingDelayMillis != null) {
            if (processingDelayMillis < 0) {
                throw new IllegalArgumentException("processingDelayMillis must be zero or positive");
            }
            this.processingDelayMillis.set(processingDelayMillis);
        }
    }

    void beforeInvocation(String failureMessage) {
        startedCount.incrementAndGet();
        long delay = processingDelayMillis.get();
        if (delay > 0) {
            sleep(delay);
        }
        while (true) {
            int current = failuresRemaining.get();
            if (current <= 0) {
                successCount.incrementAndGet();
                return;
            }
            if (failuresRemaining.compareAndSet(current, current - 1)) {
                failureCount.incrementAndGet();
                throw new IllegalStateException(failureMessage);
            }
        }
    }

    void reset() {
        failuresRemaining.set(0);
        processingDelayMillis.set(0);
        startedCount.set(0);
        successCount.set(0);
        failureCount.set(0);
    }

    AsyncScenarioSnapshot snapshot() {
        return new AsyncScenarioSnapshot(
                scenarioId,
                failuresRemaining.get(),
                processingDelayMillis.get(),
                startedCount.get(),
                successCount.get(),
                failureCount.get()
        );
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Scenario delay interrupted", exception);
        }
    }
}
