package com.reactor.asl.mapdb;

import com.reactor.asl.core.ExecutionMode;
import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.MethodDescriptor;
import com.reactor.asl.core.MethodRuntime;
import com.reactor.asl.core.QueueEntryFilter;
import com.reactor.asl.core.ServiceDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MapDbBulkQueueTest {
    private static final String SERVICE = "bulk.service";
    private static final String METHOD = "publish(java.lang.String)";

    @TempDir
    Path tempDir;

    @Test
    void replaysAndDeletesOnlyWhatTheFilterSelects() throws Exception {
        GovernanceRegistry registry = new GovernanceRegistry();
        MethodRuntime runtime = registry.register(new ServiceDescriptor(
                SERVICE, new MethodDescriptor[]{new MethodDescriptor(METHOD, "publish", true, 1, "disabled", true, 0)}
        )).methodById(METHOD);
        runtime.switchMode(ExecutionMode.ASYNC);

        try (MapDbAsyncExecutionEngine engine = new MapDbAsyncExecutionEngine(tempDir.resolve("bulk.db"))) {
            registry.attachAsyncExecutionEngine(engine);
            registry.registerAsyncMethod(runtime, arguments -> {
                throw new IllegalStateException("always fails");
            });
            long before = System.currentTimeMillis() - 1;
            for (int i = 0; i < 4; i++) {
                registry.enqueueAsync(runtime, new Object[]{"f" + i});
            }
            runtime.setConsumerThreads(1);
            engine.applyRuntime(runtime);
            waitFor(() -> engine.snapshot(SERVICE, METHOD, 0).failedCount() == 4);
            runtime.setConsumerThreads(0);
            engine.applyRuntime(runtime);
            for (int i = 0; i < 3; i++) {
                registry.enqueueAsync(runtime, new Object[]{"p" + i});
            }
            waitFor(() -> engine.snapshot(SERVICE, METHOD, 0).pendingCount() == 3);

            // Nothing queued before `before`, and no entry has this category.
            assertEquals(0, engine.deleteMatching(SERVICE, METHOD, QueueEntryFilter.parse("states=FAILED;to=" + before, 100)));
            assertEquals(0, engine.replayMatching(SERVICE, METHOD, QueueEntryFilter.parse("category=TIMEOUT", 100)));

            // The cap is respected and only the selected state is touched.
            assertEquals(2, engine.deleteMatching(SERVICE, METHOD, QueueEntryFilter.parse("states=PENDING", 2)));
            assertCounts(engine, 1, 4);

            // Replay only takes failed entries, even when the filter names other states too.
            assertEquals(3, engine.replayMatching(SERVICE, METHOD, QueueEntryFilter.parse("states=FAILED,PENDING;category=business", 3)));
            assertCounts(engine, 4, 1);

            assertEquals(5, engine.deleteMatching(SERVICE, METHOD,
                    QueueEntryFilter.parse("states=FAILED,PENDING,RETRY_WAIT;from=" + before, QueueEntryFilter.MAX_PER_COMMAND)));
            assertCounts(engine, 0, 0);
            assertEquals(0, engine.deleteMatching("other.service", METHOD, QueueEntryFilter.parse("", 10)), "unknown lane");
        }
    }

    private static void assertCounts(MapDbAsyncExecutionEngine engine, long pending, long failed) {
        var snapshot = engine.snapshot(SERVICE, METHOD, 0);
        assertEquals(pending, snapshot.pendingCount(), "pending");
        assertEquals(failed, snapshot.failedCount(), "failed");
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition was not met within timeout");
            }
            Thread.sleep(25);
        }
    }
}
