package com.reactor.asl.mapdb;

import com.reactor.asl.core.AsyncAdmissionRejectedException;
import com.reactor.asl.core.ExecutionMode;
import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.JavaObjectStreamAsyncPayloadCodec;
import com.reactor.asl.core.MethodBufferSnapshot;
import com.reactor.asl.core.MethodDescriptor;
import com.reactor.asl.core.MethodRuntime;
import com.reactor.asl.core.ServiceDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapDbCapacityAndStateTest {
    @TempDir
    Path tempDir;

    @Test
    void rejectsOversizedPayloadAndCountsAdmissionRejection() {
        MethodRuntime runtime = runtime("payload.service", "publish(java.lang.String)");
        MapDbAsyncExecutionOptions options = optionsBuilder()
                .maxPayloadBytes(8)
                .build();

        try (MapDbAsyncExecutionEngine engine = engine("payload.db", options)) {
            GovernanceRegistry registry = registry(engine, runtime, arguments -> { });

            AsyncAdmissionRejectedException exception = assertThrows(
                    AsyncAdmissionRejectedException.class,
                    () -> registry.enqueueAsync(runtime, new Object[]{"payload larger than eight bytes"})
            );

            assertEquals(AsyncAdmissionRejectedException.Reason.PAYLOAD_TOO_LARGE, exception.reason());
            assertEquals(1, runtime.stats().rejectedCount());
            assertEquals(0, engine.snapshot(runtime.serviceId(), runtime.methodId(), 10).pendingCount());
        }
    }

    @Test
    void enforcesLaneCapacityAndReleasesCapacityAfterDelete() {
        MethodRuntime runtime = runtime("lane.service", "publish(java.lang.String)");
        MapDbAsyncExecutionOptions options = optionsBuilder()
                .defaultLaneLimits(new AsyncQueueLimits(1, 1_024 * 1_024))
                .build();

        try (MapDbAsyncExecutionEngine engine = engine("lane.db", options)) {
            GovernanceRegistry registry = registry(engine, runtime, arguments -> { });
            registry.enqueueAsync(runtime, new Object[]{"first"});

            AsyncAdmissionRejectedException exception = assertThrows(
                    AsyncAdmissionRejectedException.class,
                    () -> registry.enqueueAsync(runtime, new Object[]{"second"})
            );
            assertEquals(AsyncAdmissionRejectedException.Reason.LANE_CAPACITY, exception.reason());

            String entryId = engine.snapshot(runtime.serviceId(), runtime.methodId(), 10).entries().getFirst().entryId();
            assertTrue(engine.delete(runtime.serviceId(), runtime.methodId(), entryId));
            registry.enqueueAsync(runtime, new Object[]{"second"});
            assertEquals(1, engine.snapshot(runtime.serviceId(), runtime.methodId(), 10).pendingCount());
        }
    }

    @Test
    void enforcesGlobalCapacityAcrossIndependentLanes() {
        MethodRuntime first = runtime("global.service", "first(java.lang.String)");
        MethodRuntime second = runtime("global.service", "second(java.lang.String)");
        MapDbAsyncExecutionOptions options = optionsBuilder()
                .globalLimits(new AsyncQueueLimits(1, 1_024 * 1_024))
                .defaultLaneLimits(new AsyncQueueLimits(2, 1_024 * 1_024))
                .build();

        try (MapDbAsyncExecutionEngine engine = engine("global.db", options)) {
            GovernanceRegistry registry = new GovernanceRegistry();
            registry.attachAsyncExecutionEngine(engine);
            registry.registerAsyncMethod(first, arguments -> { });
            registry.registerAsyncMethod(second, arguments -> { });
            registry.enqueueAsync(first, new Object[]{"first"});

            AsyncAdmissionRejectedException exception = assertThrows(
                    AsyncAdmissionRejectedException.class,
                    () -> registry.enqueueAsync(second, new Object[]{"second"})
            );
            assertEquals(AsyncAdmissionRejectedException.Reason.GLOBAL_CAPACITY, exception.reason());
        }
    }

    @Test
    void blockedAdmissionContinuesWhenDeleteFreesCapacity() throws Exception {
        MethodRuntime runtime = runtime("blocking.service", "publish(java.lang.String)");
        MapDbAsyncExecutionOptions options = optionsBuilder()
                .defaultLaneLimits(new AsyncQueueLimits(1, 1_024 * 1_024))
                .overflowPolicy(QueueOverflowPolicy.BLOCK_WITH_TIMEOUT)
                .enqueueTimeoutMillis(2_000)
                .build();

        try (MapDbAsyncExecutionEngine engine = engine("blocking.db", options)) {
            GovernanceRegistry registry = registry(engine, runtime, arguments -> { });
            registry.enqueueAsync(runtime, new Object[]{"first"});

            CompletableFuture<Void> blocked = CompletableFuture.runAsync(
                    () -> registry.enqueueAsync(runtime, new Object[]{"second"})
            );
            Thread.sleep(75);
            assertFalse(blocked.isDone());

            String entryId = engine.snapshot(runtime.serviceId(), runtime.methodId(), 10).entries().getFirst().entryId();
            assertTrue(engine.delete(runtime.serviceId(), runtime.methodId(), entryId));
            blocked.get(2, TimeUnit.SECONDS);
            assertEquals(1, engine.snapshot(runtime.serviceId(), runtime.methodId(), 10).pendingCount());
        }
    }

    @Test
    void blockedAdmissionTimesOutWithoutUnboundedWaiting() {
        MethodRuntime runtime = runtime("timeout.service", "publish(java.lang.String)");
        MapDbAsyncExecutionOptions options = optionsBuilder()
                .defaultLaneLimits(new AsyncQueueLimits(1, 1_024 * 1_024))
                .overflowPolicy(QueueOverflowPolicy.BLOCK_WITH_TIMEOUT)
                .enqueueTimeoutMillis(50)
                .build();

        try (MapDbAsyncExecutionEngine engine = engine("timeout.db", options)) {
            GovernanceRegistry registry = registry(engine, runtime, arguments -> { });
            registry.enqueueAsync(runtime, new Object[]{"first"});

            AsyncAdmissionRejectedException exception = assertThrows(
                    AsyncAdmissionRejectedException.class,
                    () -> registry.enqueueAsync(runtime, new Object[]{"second"})
            );
            assertEquals(AsyncAdmissionRejectedException.Reason.ADMISSION_TIMEOUT, exception.reason());
        }
    }

    @Test
    void clearCannotDeleteInvocationAfterWorkerClaimsIt() throws Exception {
        MethodRuntime runtime = runtime("race.service", "publish(java.lang.String)");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (MapDbAsyncExecutionEngine engine = engine("race.db", optionsBuilder().build())) {
            GovernanceRegistry registry = registry(engine, runtime, arguments -> {
                started.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                throw new IllegalStateException("expected failure");
            });
            registry.enqueueAsync(runtime, new Object[]{"work"});
            runtime.setConsumerThreads(1);
            engine.applyRuntime(runtime);

            assertTrue(started.await(5, TimeUnit.SECONDS));
            waitFor(() -> engine.snapshot(runtime.serviceId(), runtime.methodId(), 10).inProgressCount() == 1);
            assertEquals(0, engine.clear(runtime.serviceId(), runtime.methodId()));

            release.countDown();
            waitFor(() -> engine.snapshot(runtime.serviceId(), runtime.methodId(), 10).failedCount() == 1);
            assertEquals(1, engine.snapshot(runtime.serviceId(), runtime.methodId(), 10).entries().size());
        } finally {
            release.countDown();
        }
    }

    @Test
    void countOnlySnapshotMatchesFullSnapshotAndDoesNotWaitForTheStoreMonitor() throws Exception {
        MethodRuntime runtime = runtime("count.service", "publish(java.lang.String)");

        try (MapDbAsyncExecutionEngine engine = engine("count.db", optionsBuilder().build())) {
            GovernanceRegistry registry = registry(engine, runtime, arguments -> { });
            assertEquals(0, engine.snapshot(runtime.serviceId(), runtime.methodId(), 0).pendingCount());
            registry.enqueueAsync(runtime, new Object[]{"first"});
            registry.enqueueAsync(runtime, new Object[]{"second"});

            MethodBufferSnapshot full = engine.snapshot(runtime.serviceId(), runtime.methodId(), 10);
            MethodBufferSnapshot counts = engine.snapshot(runtime.serviceId(), runtime.methodId(), 0);
            assertEquals(full.pendingCount(), counts.pendingCount());
            assertEquals(full.inProgressCount(), counts.inProgressCount());
            assertEquals(full.failedCount(), counts.failedCount());
            assertTrue(counts.entries().isEmpty());

            Object monitor = storeMonitor(engine);
            CountDownLatch held = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread holder = new Thread(() -> {
                synchronized (monitor) {
                    held.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            holder.start();
            try {
                assertTrue(held.await(5, TimeUnit.SECONDS));
                CompletableFuture<MethodBufferSnapshot> read = CompletableFuture.supplyAsync(
                        () -> engine.snapshot(runtime.serviceId(), runtime.methodId(), 0)
                );
                assertEquals(full.pendingCount(), read.get(2, TimeUnit.SECONDS).pendingCount());
            } finally {
                release.countDown();
                holder.join();
            }
        }
    }

    private static Object storeMonitor(MapDbAsyncExecutionEngine engine) throws Exception {
        java.lang.reflect.Field storeField = MapDbAsyncExecutionEngine.class.getDeclaredField("queueStore");
        storeField.setAccessible(true);
        Object store = storeField.get(engine);
        java.lang.reflect.Field monitorField = MapDbQueueStore.class.getDeclaredField("monitor");
        monitorField.setAccessible(true);
        return monitorField.get(store);
    }

    private MapDbAsyncExecutionEngine engine(String fileName, MapDbAsyncExecutionOptions options) {
        return new MapDbAsyncExecutionEngine(
                tempDir.resolve(fileName),
                new JavaObjectStreamAsyncPayloadCodec(),
                options
        );
    }

    private static MapDbAsyncExecutionOptions.Builder optionsBuilder() {
        return MapDbAsyncExecutionOptions.builder()
                .globalLimits(new AsyncQueueLimits(100, 8 * 1_024 * 1_024))
                .defaultLaneLimits(new AsyncQueueLimits(100, 4 * 1_024 * 1_024));
    }

    private static MethodRuntime runtime(String serviceId, String methodId) {
        GovernanceRegistry registry = new GovernanceRegistry();
        MethodRuntime runtime = registry.register(new ServiceDescriptor(
                serviceId,
                new MethodDescriptor[]{new MethodDescriptor(methodId, "publish", true, 4, "disabled", true, 0)}
        )).methodById(methodId);
        runtime.switchMode(ExecutionMode.ASYNC);
        return runtime;
    }

    private static GovernanceRegistry registry(
            MapDbAsyncExecutionEngine engine,
            MethodRuntime runtime,
            com.reactor.asl.core.AsyncMethodBinding binding
    ) {
        GovernanceRegistry registry = new GovernanceRegistry();
        registry.attachAsyncExecutionEngine(engine);
        registry.registerAsyncMethod(runtime, binding);
        return registry;
    }

    private static void waitFor(Condition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.matches()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Condition was not met within timeout");
    }

    @FunctionalInterface
    private interface Condition {
        boolean matches();
    }
}
