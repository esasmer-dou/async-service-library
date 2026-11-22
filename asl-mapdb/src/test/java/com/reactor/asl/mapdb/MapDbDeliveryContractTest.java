package com.reactor.asl.mapdb;

import com.reactor.asl.core.ExecutionMode;
import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.MethodBufferSnapshot;
import com.reactor.asl.core.MethodDescriptor;
import com.reactor.asl.core.MethodRuntime;
import com.reactor.asl.core.ServiceDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mapdb.DB;
import org.mapdb.DBMaker;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** At-least-once delivery contract: interruptions return work to the queue, bounded. */
class MapDbDeliveryContractTest {
    private static final String SERVICE_ID = "delivery.service";
    private static final String METHOD_ID = "publish(java.lang.String)";

    @TempDir
    Path tempDir;

    @Test
    void gracefulShutdownDuringExecutionReturnsWorkToQueueAndItRunsAfterRestart() throws Exception {
        Path path = tempDir.resolve("shutdown.db");
        MapDbAsyncExecutionOptions options = MapDbAsyncExecutionOptions.builder()
                .workerShutdownAwaitMillis(200L)
                .build();
        CountDownLatch started = new CountDownLatch(1);
        MapDbAsyncExecutionEngine engine = new MapDbAsyncExecutionEngine(path, options);
        GovernanceRegistry registry = new GovernanceRegistry();
        MethodRuntime runtime = asyncRuntime(1);
        registry.attachAsyncExecutionEngine(engine);
        registry.registerAsyncMethod(runtime, arguments -> {
            started.countDown();
            Thread.sleep(60_000L);
        });
        registry.enqueueAsync(runtime, new Object[]{"long-running"});
        assertTrue(started.await(5, TimeUnit.SECONDS));
        try {
            engine.close();
        } catch (RuntimeException expectedTimeoutReport) {
            // close reports that workers had to be interrupted
        }
        assertEquals(1L, engine.releasedOnShutdown());

        List<String> processed = new CopyOnWriteArrayList<>();
        MethodRuntime restartedRuntime = asyncRuntime(1);
        GovernanceRegistry restartedRegistry = new GovernanceRegistry();
        try (MapDbAsyncExecutionEngine reopened = new MapDbAsyncExecutionEngine(path, options)) {
            MethodBufferSnapshot before = reopened.snapshot(SERVICE_ID, METHOD_ID, 10);
            assertEquals(1L, before.pendingCount());
            assertEquals(0, before.entries().getFirst().attempts());
            assertEquals("INTERRUPTED", before.entries().getFirst().errorCategory());
            assertEquals(1, before.entries().getFirst().interruptions());

            restartedRegistry.attachAsyncExecutionEngine(reopened);
            restartedRegistry.registerAsyncMethod(restartedRuntime, arguments -> processed.add((String) arguments[0]));
            waitFor(() -> processed.contains("long-running"));
            waitFor(() -> reopened.snapshot(SERVICE_ID, METHOD_ID, 10).entries().isEmpty());
        }
    }

    @Test
    void crashLoopIsBoundedByMaxInterruptions() {
        Path path = tempDir.resolve("crash-loop.db");
        MapDbAsyncExecutionOptions options = MapDbAsyncExecutionOptions.builder().maxInterruptions(2).build();
        for (int crash = 1; crash <= 3; crash++) {
            try (DB db = DBMaker.fileDB(path.toFile()).transactionEnable().make()) {
                MapDbQueueStore store = new MapDbQueueStore(db, options);
                if (crash == 1) {
                    store.enqueue(invocation());
                }
                // Claim and "crash" (close without completing).
                assertTrue(store.claimNext(SERVICE_ID, METHOD_ID, System.currentTimeMillis()).isPresent(),
                        "crash " + crash + " should still find the invocation pending");
            }
        }
        try (DB db = DBMaker.fileDB(path.toFile()).transactionEnable().make()) {
            MethodBufferSnapshot snapshot = new MapDbQueueStore(db, options).snapshot(SERVICE_ID, METHOD_ID, 10);
            assertEquals(0L, snapshot.pendingCount());
            assertEquals(1L, snapshot.failedCount());
            assertEquals("RECOVERY", snapshot.entries().getFirst().errorCategory());
        }
    }

    @Test
    void executionTimeoutInterruptsAndRecordsTimeoutFailure() throws Exception {
        MapDbAsyncExecutionOptions options = MapDbAsyncExecutionOptions.builder()
                .executionTimeoutMillis(150L)
                .build();
        AtomicInteger interrupted = new AtomicInteger();
        GovernanceRegistry registry = new GovernanceRegistry();
        MethodRuntime runtime = asyncRuntime(1);
        try (MapDbAsyncExecutionEngine engine = new MapDbAsyncExecutionEngine(tempDir.resolve("timeout.db"), options)) {
            registry.attachAsyncExecutionEngine(engine);
            registry.registerAsyncMethod(runtime, arguments -> {
                try {
                    Thread.sleep(30_000L);
                } catch (InterruptedException exception) {
                    interrupted.incrementAndGet();
                    throw exception;
                }
            });
            registry.enqueueAsync(runtime, new Object[]{"slow"});

            waitFor(() -> engine.snapshot(SERVICE_ID, METHOD_ID, 10).failedCount() == 1L);
            MethodBufferSnapshot snapshot = engine.snapshot(SERVICE_ID, METHOD_ID, 10);
            assertEquals("TIMEOUT", snapshot.entries().getFirst().errorCategory());
            assertEquals(1, interrupted.get());
            assertEquals(1L, engine.executionTimeouts());
            assertEquals(0, runtime.inFlight(), "the worker slot must be released");
            assertTrue(engine.workerHealth().healthy());
        }
    }

    @Test
    void orphanedClaimIsReclaimedAfterLeaseExpiryWithoutRestart() throws Exception {
        MapDbAsyncExecutionOptions options = MapDbAsyncExecutionOptions.builder()
                .leaseDurationMillis(200L)
                .leaseRenewalIntervalMillis(50L)
                .build();
        List<String> processed = new CopyOnWriteArrayList<>();
        GovernanceRegistry registry = new GovernanceRegistry();
        MethodRuntime runtime = asyncRuntime(0); // no consumers while we create the orphan
        try (MapDbAsyncExecutionEngine engine = new MapDbAsyncExecutionEngine(tempDir.resolve("orphan.db"), options)) {
            registry.attachAsyncExecutionEngine(engine);
            registry.registerAsyncMethod(runtime, arguments -> processed.add((String) arguments[0]));
            registry.enqueueAsync(runtime, new Object[]{"orphan"});

            // Simulate a claim whose caller gave up (e.g. writer operation timeout): nobody owns it.
            BoundedStoreWriter writer = field(engine, "writer", BoundedStoreWriter.class);
            QueueStore store = field(engine, "queueStore", QueueStore.class);
            assertTrue(writer.execute(
                    () -> store.claimNext(SERVICE_ID, METHOD_ID, System.currentTimeMillis()), 0L
            ).isPresent());
            assertEquals(1L, engine.snapshot(SERVICE_ID, METHOD_ID, 10).inProgressCount());

            runtime.setConsumerThreads(1);
            engine.applyRuntime(runtime);
            waitFor(() -> processed.contains("orphan"));
            assertTrue(engine.reclaimedLeases() >= 1L);
            waitFor(() -> engine.snapshot(SERVICE_ID, METHOD_ID, 10).entries().isEmpty());
        }
    }

    @Test
    void activeLeasesAreRenewedInOneCommitAndNeverReclaimedWhileOwned() throws Exception {
        MapDbAsyncExecutionOptions options = MapDbAsyncExecutionOptions.builder()
                .leaseDurationMillis(200L)
                .leaseRenewalIntervalMillis(50L)
                .build();
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        GovernanceRegistry registry = new GovernanceRegistry();
        MethodRuntime runtime = asyncRuntime(2);
        try (MapDbAsyncExecutionEngine engine = new MapDbAsyncExecutionEngine(tempDir.resolve("renew.db"), options)) {
            registry.attachAsyncExecutionEngine(engine);
            registry.registerAsyncMethod(runtime, arguments -> {
                executions.incrementAndGet();
                release.await(10, TimeUnit.SECONDS);
            });
            registry.enqueueAsync(runtime, new Object[]{"a"});
            registry.enqueueAsync(runtime, new Object[]{"b"});
            waitFor(() -> engine.snapshot(SERVICE_ID, METHOD_ID, 10).inProgressCount() == 2L);

            TimeUnit.MILLISECONDS.sleep(700L); // several lease durations
            assertEquals(0L, engine.reclaimedLeases());
            assertEquals(2, executions.get(), "owned work must not be re-dispatched");
            release.countDown();
            waitFor(() -> engine.snapshot(SERVICE_ID, METHOD_ID, 10).entries().isEmpty());
            assertFalse(engine.workerHealth().workerFailures() > 0L, engine.workerHealth()::toString);
        }
    }

    @Test
    void backlogKeepsDrainingAfterSwitchBackToSyncUnlessDisabled() throws Exception {
        for (boolean drain : new boolean[]{true, false}) {
            MapDbAsyncExecutionOptions options = MapDbAsyncExecutionOptions.builder()
                    .drainBacklogInSyncMode(drain)
                    .build();
            List<String> processed = new CopyOnWriteArrayList<>();
            GovernanceRegistry registry = new GovernanceRegistry();
            MethodRuntime runtime = asyncRuntime(0);
            try (MapDbAsyncExecutionEngine engine = new MapDbAsyncExecutionEngine(
                    tempDir.resolve("drain-" + drain + ".db"), options)) {
                registry.attachAsyncExecutionEngine(engine);
                registry.registerAsyncMethod(runtime, arguments -> processed.add((String) arguments[0]));
                registry.enqueueAsync(runtime, new Object[]{"queued-before-switch"});

                runtime.switchMode(ExecutionMode.SYNC);
                runtime.setConsumerThreads(1);
                engine.applyRuntime(runtime);

                if (drain) {
                    waitFor(() -> processed.contains("queued-before-switch"));
                } else {
                    TimeUnit.MILLISECONDS.sleep(400L);
                    assertTrue(processed.isEmpty(), "backlog must wait for ASYNC when draining is disabled");
                    assertEquals(1L, engine.snapshot(SERVICE_ID, METHOD_ID, 10).pendingCount());
                }
            }
        }
    }

    private static QueueStore.NewInvocation invocation() {
        return new QueueStore.NewInvocation(
                SERVICE_ID, METHOD_ID, "codec", new byte[]{1}, null, null, null, null, System.currentTimeMillis()
        );
    }

    private static MethodRuntime asyncRuntime(int consumers) {
        GovernanceRegistry registry = new GovernanceRegistry();
        MethodRuntime runtime = registry.register(new ServiceDescriptor(
                SERVICE_ID,
                new MethodDescriptor[]{new MethodDescriptor(METHOD_ID, "publish", true, 4, "disabled", true, consumers)}
        )).methodById(METHOD_ID);
        runtime.switchMode(ExecutionMode.ASYNC);
        return runtime;
    }

    private static <T> T field(MapDbAsyncExecutionEngine engine, String name, Class<T> type) throws Exception {
        Field field = MapDbAsyncExecutionEngine.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(engine));
    }

    private static void waitFor(Condition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.met()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20L);
        }
        assertTrue(condition.met(), "condition not met within timeout");
    }

    @FunctionalInterface
    private interface Condition {
        boolean met() throws Exception;
    }
}
