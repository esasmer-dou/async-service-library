package com.reactor.asl.mapdb;

import com.reactor.asl.core.ExecutionMode;
import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.MethodDescriptor;
import com.reactor.asl.core.MethodRuntime;
import com.reactor.asl.core.ServiceDescriptor;
import com.reactor.asl.core.ServiceRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapDbAsyncExecutionEngineTest {
    @TempDir
    Path tempDir;

    @Test
    void queuesUntilConsumersAreEnabledThenProcesses() throws Exception {
        GovernanceRegistry registry = new GovernanceRegistry();
        ServiceRuntime service = registry.register(new ServiceDescriptor(
                "demo.service",
                new MethodDescriptor[]{
                        new MethodDescriptor("publish(java.lang.String)", "publish", true, 1, "disabled", true, 0)
                }
        ));
        MethodRuntime runtime = service.methodById("publish(java.lang.String)");
        runtime.switchMode(ExecutionMode.ASYNC);

        CountDownLatch consumed = new CountDownLatch(1);

        try (MapDbAsyncExecutionEngine engine = new MapDbAsyncExecutionEngine(tempDir.resolve("queue.db"))) {
            registry.attachAsyncExecutionEngine(engine);
            registry.registerAsyncMethod(runtime, arguments -> consumed.countDown());

            registry.enqueueAsync(runtime, new Object[]{"first"});
            waitFor(() -> engine.snapshot("demo.service", "publish(java.lang.String)", 10).pendingCount() == 1, 2);
            assertEquals(0, runtime.stats().successCount());

            runtime.setConsumerThreads(1);
            engine.applyRuntime(runtime);

            assertTrue(consumed.await(5, TimeUnit.SECONDS));
            waitFor(() -> engine.snapshot("demo.service", "publish(java.lang.String)", 10).pendingCount() == 0, 5);
            assertEquals(1, runtime.stats().successCount());
        }
    }

    @Test
    void capturesFailuresSupportsReplayAndConsumerResize() throws Exception {
        GovernanceRegistry registry = new GovernanceRegistry();
        ServiceRuntime service = registry.register(new ServiceDescriptor(
                "demo.service",
                new MethodDescriptor[]{
                        new MethodDescriptor("publish(java.lang.String)", "publish", true, 1, "disabled", true, 0)
                }
        ));
        MethodRuntime runtime = service.methodById("publish(java.lang.String)");
        runtime.switchMode(ExecutionMode.ASYNC);

        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch replaySucceeded = new CountDownLatch(1);

        try (MapDbAsyncExecutionEngine engine = new MapDbAsyncExecutionEngine(tempDir.resolve("queue-replay.db"))) {
            registry.attachAsyncExecutionEngine(engine);
            registry.registerAsyncMethod(runtime, arguments -> {
                if (attempts.getAndIncrement() == 0) {
                    throw new IllegalStateException("boom");
                }
                replaySucceeded.countDown();
            });

            registry.enqueueAsync(runtime, new Object[]{"first"});
            waitFor(() -> engine.snapshot("demo.service", "publish(java.lang.String)", 10).pendingCount() == 1, 2);

            runtime.setConsumerThreads(1);
            engine.applyRuntime(runtime);

            waitFor(() -> engine.snapshot("demo.service", "publish(java.lang.String)", 10).failedCount() == 1, 5);
            var failedSnapshot = engine.snapshot("demo.service", "publish(java.lang.String)", 10);
            assertEquals(1, runtime.stats().errorCount());
            assertEquals("FAILED", failedSnapshot.entries().getFirst().state());
            assertEquals("BUSINESS", failedSnapshot.entries().getFirst().errorCategory());
            assertEquals("java-object-stream-v1", failedSnapshot.entries().getFirst().codecId());

            runtime.setConsumerThreads(0);
            engine.applyRuntime(runtime);
            assertTrue(engine.replay("demo.service", "publish(java.lang.String)", failedSnapshot.entries().getFirst().entryId()));
            waitFor(() -> engine.snapshot("demo.service", "publish(java.lang.String)", 10).pendingCount() == 1, 2);

            runtime.setConsumerThreads(1);
            engine.applyRuntime(runtime);

            assertTrue(replaySucceeded.await(5, TimeUnit.SECONDS));
            waitFor(() -> {
                var snapshot = engine.snapshot("demo.service", "publish(java.lang.String)", 10);
                return snapshot.pendingCount() == 0 && snapshot.failedCount() == 0;
            }, 5);
            assertEquals(1, runtime.stats().successCount());
            assertEquals(1, runtime.stats().errorCount());
        }
    }

    private static void waitFor(Condition condition, int timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (condition.matches()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Condition was not met within timeout");
    }

    @FunctionalInterface
    private interface Condition {
        boolean matches();
    }
}
