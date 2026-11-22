package com.reactor.asl.spring.boot;

import com.reactor.asl.core.ExecutionMode;
import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.MethodDescriptor;
import com.reactor.asl.core.MethodRuntime;
import com.reactor.asl.core.ServiceDescriptor;
import com.reactor.asl.mapdb.MapDbAsyncExecutionEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AslMapDbHealthIndicatorTest {
    @TempDir
    Path tempDir;

    @Test
    void reportsUpWithQueueDetailsAndDownAfterClose() throws Exception {
        MapDbAsyncExecutionEngine engine = new MapDbAsyncExecutionEngine(tempDir.resolve("health.db"));
        GovernanceRegistry registry = new GovernanceRegistry();
        MethodRuntime runtime = registry.register(new ServiceDescriptor("health.service", new MethodDescriptor[]{
                new MethodDescriptor("publish(java.lang.String)", "publish", true, 2, "", true, 0)
        })).methodById("publish(java.lang.String)");
        runtime.switchMode(ExecutionMode.ASYNC);
        registry.attachAsyncExecutionEngine(engine);
        registry.registerAsyncMethod(runtime, arguments -> { });
        registry.enqueueAsync(runtime, new Object[]{"queued"});

        AslMapDbHealthIndicator indicator = new AslMapDbHealthIndicator(engine, Duration.ofSeconds(60));
        Thread.sleep(150L); // let the coordinator publish a heartbeat
        Health up = indicator.health();
        assertEquals(Status.UP, up.getStatus(), up.toString());
        assertEquals(1L, up.getDetails().get("pending"));
        assertEquals(true, up.getDetails().get("writerAccepting"));

        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        new AslMapDbMetricsBinder(engine, meters, new AslObservabilityProperties());
        assertEquals(1.0d, meters.get("asl.mapdb.lane.pending")
                .tags("service", "health.service", "method", "publish(java.lang.String)").gauge().value());
        assertEquals(true, meters.get("asl.mapdb.lane.oldest.pending.age").gauge().value() >= 0.0d);
        meters.close();

        engine.close();
        assertEquals(Status.DOWN, indicator.health().getStatus());
    }
}
