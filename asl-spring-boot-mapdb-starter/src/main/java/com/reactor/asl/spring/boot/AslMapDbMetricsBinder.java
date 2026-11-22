package com.reactor.asl.spring.boot;

import com.reactor.asl.mapdb.MapDbAsyncExecutionEngine;
import com.reactor.asl.mapdb.MapDbLaneDepth;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToLongFunction;

final class AslMapDbMetricsBinder {
    private static final Logger log = LoggerFactory.getLogger(AslMapDbMetricsBinder.class);

    AslMapDbMetricsBinder(
            MapDbAsyncExecutionEngine engine,
            MeterRegistry registry,
            AslObservabilityProperties properties
    ) {
        properties.validate();
        String prefix = properties.getMetricPrefix() + ".mapdb";

        FunctionCounter.builder(prefix + ".writer.accepted", engine,
                        value -> value.writerMetrics().acceptedCommands())
                .description("Store commands accepted by the bounded writer")
                .register(registry);
        FunctionCounter.builder(prefix + ".writer.committed", engine,
                        value -> value.writerMetrics().committedCommands())
                .description("Store commands durably committed")
                .register(registry);
        FunctionCounter.builder(prefix + ".writer.failed", engine,
                        value -> value.writerMetrics().failedCommands())
                .description("Store commands failed by the bounded writer")
                .register(registry);
        FunctionCounter.builder(prefix + ".writer.commits", engine,
                        value -> value.writerMetrics().commits())
                .description("Durable MapDB commit operations")
                .register(registry);
        Gauge.builder(prefix + ".engine.healthy", engine,
                        value -> value.workerHealth().healthy() ? 1.0d : 0.0d)
                .description("1 when the async engine can admit and execute work; 0 after a fatal storage failure or stop")
                .register(registry);
        Gauge.builder(prefix + ".writer.accepting", engine,
                        value -> value.workerHealth().writerAccepting() ? 1.0d : 0.0d)
                .description("1 while the bounded MapDB writer accepts commands")
                .register(registry);
        Gauge.builder(prefix + ".writer.queue.depth", engine,
                        value -> value.writerMetrics().queueDepth())
                .description("Commands waiting in the bounded writer queue")
                .register(registry);
        Gauge.builder(prefix + ".enqueue.active", engine, MapDbAsyncExecutionEngine::activeEnqueues)
                .description("Async invocations currently serializing or awaiting durable admission")
                .register(registry);
        Gauge.builder(prefix + ".enqueue.max", engine, MapDbAsyncExecutionEngine::maxConcurrentEnqueues)
                .description("Configured concurrent async enqueue limit")
                .register(registry);
        Gauge.builder(prefix + ".writer.commit.last.nanos", engine,
                        value -> value.writerMetrics().lastCommitNanos())
                .description("Duration of the most recent durable commit")
                .baseUnit("nanoseconds")
                .register(registry);
        Gauge.builder(prefix + ".writer.commit.max.nanos", engine,
                        value -> value.writerMetrics().maxCommitNanos())
                .description("Maximum observed durable commit duration")
                .baseUnit("nanoseconds")
                .register(registry);
        Gauge.builder(prefix + ".writer.commit.average.nanos", engine,
                        value -> value.writerMetrics().averageCommitNanos())
                .description("Average durable commit duration")
                .baseUnit("nanoseconds")
                .register(registry);
        Gauge.builder(prefix + ".worker.active", engine,
                        value -> value.workerHealth().activeTasks())
                .description("Active async worker tasks")
                .register(registry);
        Gauge.builder(prefix + ".worker.pool.size", engine,
                        value -> value.workerHealth().poolSize())
                .description("Current shared worker pool size")
                .register(registry);
        Gauge.builder(prefix + ".worker.queue.depth", engine,
                        value -> value.workerHealth().queuedTasks())
                .description("Tasks waiting in the shared worker executor")
                .register(registry);
        FunctionCounter.builder(prefix + ".worker.failures", engine,
                        value -> value.workerHealth().workerFailures())
                .description("Uncaught coordinator or worker failures")
                .register(registry);
        FunctionCounter.builder(prefix + ".worker.rejected.schedules", engine,
                        value -> value.workerHealth().rejectedSchedules())
                .description("Worker tasks rejected by the bounded executor")
                .register(registry);
        Gauge.builder(prefix + ".projection.version", engine,
                        MapDbAsyncExecutionEngine::projectionVersion)
                .description("Monotonic queue projection version")
                .register(registry);
        FunctionCounter.builder(prefix + ".execution.timeouts", engine,
                        MapDbAsyncExecutionEngine::executionTimeouts)
                .description("Async executions interrupted by the execution timeout")
                .register(registry);
        FunctionCounter.builder(prefix + ".leases.reclaimed", engine,
                        MapDbAsyncExecutionEngine::reclaimedLeases)
                .description("Orphaned in-progress invocations returned to the queue after lease expiry")
                .register(registry);
        FunctionCounter.builder(prefix + ".released.on.shutdown", engine,
                        MapDbAsyncExecutionEngine::releasedOnShutdown)
                .description("In-progress invocations returned to the queue because the engine stopped")
                .register(registry);

        int maxLanes = properties.getMaxMethodMeters();
        AtomicInteger laneMeters = new AtomicInteger();
        engine.onLaneRegistered((service, method) -> {
            if (laneMeters.incrementAndGet() > maxLanes) {
                if (laneMeters.get() == maxLanes + 1) {
                    log.warn("ASL lane metric limit {} reached; further async lanes are not exported", maxLanes);
                }
                return;
            }
            registerLane(registry, prefix, engine, service, method);
        });
    }

    private static void registerLane(
            MeterRegistry registry,
            String prefix,
            MapDbAsyncExecutionEngine engine,
            String service,
            String method
    ) {
        laneGauge(registry, prefix + ".lane.pending", "Pending invocations in the lane",
                engine, service, method, MapDbLaneDepth::pending);
        laneGauge(registry, prefix + ".lane.in.progress", "Invocations currently executing",
                engine, service, method, MapDbLaneDepth::inProgress);
        laneGauge(registry, prefix + ".lane.retry.wait", "Invocations waiting for a retry",
                engine, service, method, MapDbLaneDepth::retryWait);
        laneGauge(registry, prefix + ".lane.failed", "Terminally failed invocations retained for replay",
                engine, service, method, MapDbLaneDepth::failed);
        Gauge.builder(prefix + ".lane.oldest.pending.age", engine,
                        value -> laneValue(value, service, method, MapDbLaneDepth::oldestPendingAgeMillis))
                .description("Age of the oldest pending invocation; 0 when the lane is empty")
                .baseUnit("milliseconds")
                .tag("service", service)
                .tag("method", method)
                .register(registry);
    }

    private static void laneGauge(
            MeterRegistry registry,
            String name,
            String description,
            MapDbAsyncExecutionEngine engine,
            String service,
            String method,
            ToLongFunction<MapDbLaneDepth> value
    ) {
        Gauge.builder(name, engine, current -> laneValue(current, service, method, value))
                .description(description)
                .tag("service", service)
                .tag("method", method)
                .register(registry);
    }

    private static double laneValue(
            MapDbAsyncExecutionEngine engine,
            String service,
            String method,
            ToLongFunction<MapDbLaneDepth> value
    ) {
        try {
            return value.applyAsLong(engine.laneDepth(service, method));
        } catch (RuntimeException unavailable) {
            // Store failed; health reports it. A gauge must not throw on every scrape.
            return Double.NaN;
        }
    }
}
