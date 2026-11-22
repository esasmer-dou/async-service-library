package com.reactor.asl.spring.boot;

import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.MethodDescriptor;
import com.reactor.asl.core.MethodRuntime;
import com.reactor.asl.core.ServiceDescriptor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AslCoreMetricsBinderTest {
    @Test
    void publishesExistingRuntimeCountersWithoutHotPathInstrumentation() {
        GovernanceRegistry governanceRegistry = new GovernanceRegistry();
        MethodRuntime runtime = governanceRegistry.register(new ServiceDescriptor(
                "metric.service",
                new MethodDescriptor[]{
                        new MethodDescriptor("run()", "run", true, 4, "", false, 0)
                }
        )).methodById("run()");
        runtime.onSuccess();
        runtime.onRejected();
        assertEquals(1L, runtime.successCount());
        assertEquals(1L, runtime.rejectedCount());

        AslObservabilityProperties properties = new AslObservabilityProperties();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        try {
            new AslCoreMetricsBinder(governanceRegistry, meterRegistry, properties)
                    .afterSingletonsInstantiated();

            assertEquals(1.0d, meterRegistry.get("asl.method.success")
                    .tags("service", "metric.service", "method", "run()")
                    .functionCounter().count());
            assertEquals(1.0d, meterRegistry.get("asl.method.rejected")
                    .tags("service", "metric.service", "method", "run()")
                    .functionCounter().count());
            runtime.resetObservations();
            assertEquals(0L, runtime.successCount());
            assertEquals(0L, runtime.rejectedCount());
            assertEquals(1.0d, meterRegistry.get("asl.method.success")
                    .tags("service", "metric.service", "method", "run()")
                    .functionCounter().count());
            assertEquals(1.0d, meterRegistry.get("asl.method.rejected")
                    .tags("service", "metric.service", "method", "run()")
                    .functionCounter().count());
            assertEquals(4.0d, meterRegistry.get("asl.method.max.concurrency")
                    .tags("service", "metric.service", "method", "run()")
                    .gauge().value());
        } finally {
            meterRegistry.close();
        }
    }

    @Test
    void methodLatencyTimerIsOptInAndRecordsOnlyWhenEnabled() {
        GovernanceRegistry governanceRegistry = new GovernanceRegistry();
        MethodRuntime runtime = governanceRegistry.register(new ServiceDescriptor(
                "latency.service",
                new MethodDescriptor[]{new MethodDescriptor("run()", "run", true, 4, "", false, 0)}
        )).methodById("run()");

        assertEquals(0L, runtime.startTiming(), "no clock read without a recorder");

        AslObservabilityProperties properties = new AslObservabilityProperties();
        properties.setMethodLatencyEnabled(true);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        try {
            new AslCoreMetricsBinder(governanceRegistry, meterRegistry, properties).afterSingletonsInstantiated();

            assertTrue(runtime.tryAcquire());
            long started = runtime.startTiming();
            assertTrue(started != 0L);
            runtime.release(started);

            io.micrometer.core.instrument.Timer timer = meterRegistry.get("asl.method.duration")
                    .tags("service", "latency.service", "method", "run()")
                    .timer();
            assertEquals(1L, timer.count());
            assertEquals(0, runtime.inFlight());
        } finally {
            meterRegistry.close();
        }
    }
}
