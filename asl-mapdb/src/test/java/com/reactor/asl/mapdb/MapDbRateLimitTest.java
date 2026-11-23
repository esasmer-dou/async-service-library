package com.reactor.asl.mapdb;

import com.reactor.asl.core.ExecutionMode;
import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.MethodDescriptor;
import com.reactor.asl.core.MethodRuntime;
import com.reactor.asl.core.ServiceDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapDbRateLimitTest {
    @TempDir
    Path tempDir;

    @Test
    void asyncConsumersSlowDownToTheRateInsteadOfRejecting() throws Exception {
        GovernanceRegistry registry = new GovernanceRegistry();
        MethodRuntime runtime = registry.register(new ServiceDescriptor(
                "rate.service",
                new MethodDescriptor[]{new MethodDescriptor("publish(java.lang.String)", "publish", true, 8, "disabled", true, 0)}
        )).methodById("publish(java.lang.String)");
        runtime.switchMode(ExecutionMode.ASYNC);
        runtime.setRateLimitPerSecond(10);

        List<Long> startedAt = new CopyOnWriteArrayList<>();
        try (MapDbAsyncExecutionEngine engine = new MapDbAsyncExecutionEngine(tempDir.resolve("rate.db"))) {
            registry.attachAsyncExecutionEngine(engine);
            registry.registerAsyncMethod(runtime, arguments -> startedAt.add(System.nanoTime()));
            for (int i = 0; i < 25; i++) {
                registry.enqueueAsync(runtime, new Object[]{"m" + i});
            }
            assertEquals(0, runtime.rejectedCount(), "ASYNC calls above the rate are queued, not rejected");

            long consumersOnAt = System.nanoTime();
            runtime.setConsumerThreads(4);
            engine.applyRuntime(runtime);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (runtime.stats().successCount() < 25 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(25, runtime.stats().successCount());
            assertTrue(engine.rateLimitedSchedules() > 0, "the coordinator waited for permits");

            // 10 may start at once (one second of burst), the other 15 come at 10/s: at least ~1.5 s in all.
            long first = startedAt.stream().mapToLong(Long::longValue).min().orElseThrow();
            long last = startedAt.stream().mapToLong(Long::longValue).max().orElseThrow();
            long spreadMillis = TimeUnit.NANOSECONDS.toMillis(last - first);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(first - consumersOnAt) < 2_000, "the burst starts right away");
            // Waiting consumers must not spin: a handful of dispatches per permit, not thousands.
            assertTrue(engine.rateLimitedSchedules() < 200, "rate-limited dispatches: " + engine.rateLimitedSchedules());
            assertTrue(spreadMillis >= 1_300, "25 calls at 10/s finished within " + spreadMillis + " ms");
            for (long windowStart : startedAt) {
                long inWindow = startedAt.stream().filter(t -> t >= windowStart && t - windowStart < 1_000_000_000L).count();
                assertTrue(inWindow <= 20, "at most burst + rate per second, saw " + inWindow);
            }
        }
    }
}
