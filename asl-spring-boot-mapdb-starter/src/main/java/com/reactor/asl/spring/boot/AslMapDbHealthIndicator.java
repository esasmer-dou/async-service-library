package com.reactor.asl.spring.boot;

import com.reactor.asl.mapdb.MapDbAsyncExecutionEngine;
import com.reactor.asl.mapdb.MapDbWorkerHealth;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;

import java.time.Duration;
import java.util.Objects;

/**
 * Actuator health for the MapDB async engine (contributor name {@code aslMapDb}).
 *
 * <p>DOWN when the engine is closed, the writer stopped after a fatal storage failure, or the
 * coordinator heartbeat is older than {@code staleHeartbeat}. Queue depth is reported as detail
 * but never makes the engine DOWN: backlog is a capacity signal, not a liveness failure.</p>
 */
public class AslMapDbHealthIndicator extends AbstractHealthIndicator {
    private final MapDbAsyncExecutionEngine engine;
    private final Duration staleHeartbeat;

    public AslMapDbHealthIndicator(MapDbAsyncExecutionEngine engine, Duration staleHeartbeat) {
        super("ASL MapDB async engine health check failed");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.staleHeartbeat = Objects.requireNonNull(staleHeartbeat, "staleHeartbeat");
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        MapDbWorkerHealth health = engine.workerHealth();
        long heartbeatAgeMillis = health.lastCoordinatorHeartbeatEpochMillis() == 0L
                ? -1L
                : Math.max(0L, System.currentTimeMillis() - health.lastCoordinatorHeartbeatEpochMillis());
        boolean heartbeatStale = heartbeatAgeMillis > staleHeartbeat.toMillis();

        if (health.healthy() && !heartbeatStale) {
            builder.up();
        } else {
            builder.down();
        }
        builder.withDetail("running", health.running())
                .withDetail("writerAccepting", health.writerAccepting())
                .withDetail("coordinatorHeartbeatAgeMillis", heartbeatAgeMillis)
                .withDetail("registeredLanes", health.registeredLanes())
                .withDetail("activeTasks", health.activeTasks())
                .withDetail("workerFailures", health.workerFailures())
                .withDetail("executionTimeouts", engine.executionTimeouts())
                .withDetail("reclaimedLeases", engine.reclaimedLeases());
        if (health.storeFailureType() != null) {
            builder.withDetail("storeFailureType", health.storeFailureType());
        }
        if (heartbeatStale) {
            builder.withDetail("reason", "coordinator heartbeat older than " + staleHeartbeat.toMillis() + " ms");
        }
        try {
            long pending = 0L;
            long failed = 0L;
            long oldestPendingAgeMillis = 0L;
            for (var lane : engine.laneDepths()) {
                pending += lane.pending() + lane.retryWait();
                failed += lane.failed();
                oldestPendingAgeMillis = Math.max(oldestPendingAgeMillis, lane.oldestPendingAgeMillis());
            }
            builder.withDetail("pending", pending)
                    .withDetail("failed", failed)
                    .withDetail("oldestPendingAgeMillis", oldestPendingAgeMillis);
        } catch (RuntimeException unavailable) {
            builder.down().withDetail("queue", "unreadable");
        }
    }
}
