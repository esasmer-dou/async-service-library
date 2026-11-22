package com.reactor.asl.mapdb;

import com.reactor.asl.core.AsyncAdmissionRejectedException;
import com.reactor.asl.core.AsyncInvocationContext;
import com.reactor.asl.core.ExecutionMode;
import com.reactor.asl.core.GovernanceRegistry;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests: malformed caller input must never stop the shared writer, and a genuinely
 * fatal storage failure must be visible in health without a coordinator hot-spin.
 */
class MapDbWriterIsolationTest {
    private static final String SERVICE_ID = "isolation.service";
    private static final String METHOD_ID = "publish(java.lang.String)";

    @TempDir
    Path tempDir;

    @Test
    void oversizedOrNulIdempotencyKeyIsRejectedWithoutAffectingOtherAdmissions() throws Exception {
        MethodRuntime runtime = asyncRuntime(1);
        GovernanceRegistry registry = new GovernanceRegistry();
        Set<String> processed = ConcurrentHashMap.newKeySet();
        try (MapDbAsyncExecutionEngine engine = new MapDbAsyncExecutionEngine(
                tempDir.resolve("isolation.db"),
                MapDbAsyncExecutionOptions.builder().maxIdempotencyKeyCharacters(32).build()
        )) {
            registry.attachAsyncExecutionEngine(engine);
            registry.registerAsyncMethod(runtime, arguments -> processed.add((String) arguments[0]));

            registry.enqueueAsync(runtime, new Object[]{"before"}, AsyncInvocationContext.idempotent("k-before"));
            assertThrows(IllegalArgumentException.class, () -> registry.enqueueAsync(
                    runtime, new Object[]{"oversized"}, AsyncInvocationContext.idempotent("x".repeat(33))
            ));
            assertThrows(IllegalArgumentException.class, () -> registry.enqueueAsync(
                    runtime, new Object[]{"nul"}, AsyncInvocationContext.idempotent("bad\u0000key")
            ));
            registry.enqueueAsync(runtime, new Object[]{"after"}, AsyncInvocationContext.idempotent("k-after"));

            waitFor(() -> processed.containsAll(List.of("before", "after")));
            assertEquals(Set.of("before", "after"), processed);
            MapDbWorkerHealth health = engine.workerHealth();
            assertTrue(health.healthy(), health::toString);
            assertTrue(health.writerAccepting());
            assertEquals(0L, engine.writerMetrics().failedCommands());
            assertEquals(2L, runtime.rejectedCount());
        }
    }

    @Test
    void writerConfinesCallerInputErrorsToTheFailingCommand() throws Exception {
        MapDbAsyncExecutionOptions options = MapDbAsyncExecutionOptions.builder()
                .commitBatchSize(8)
                .commitBatchWindowMillis(50L)
                .build();
        DB db = DBMaker.memoryDB().transactionEnable().make();
        MapDbQueueStore store = new MapDbQueueStore(db, options);
        BoundedStoreWriter writer = new BoundedStoreWriter(store, options);
        try {
            // Bypass engine-side validation to prove the writer itself is resilient.
            QueueStore.NewInvocation invalid = invocation("y".repeat(options.maxIdempotencyKeyCharacters() + 1));
            QueueStore.NewInvocation valid = invocation("valid-key");

            assertThrows(IllegalArgumentException.class, () -> writer.execute(() -> store.enqueue(invalid), 0L));
            QueueStore.EnqueueResult admitted = writer.execute(() -> store.enqueue(valid), 0L);

            assertTrue(writer.isAccepting());
            assertTrue(store.fatalFailure().isEmpty());
            assertFalse(admitted.deduplicated());
            assertEquals(1L, store.snapshot(SERVICE_ID, METHOD_ID, 10).pendingCount());
        } finally {
            writer.close();
            db.close();
        }
    }

    @Test
    void fatalStorageFailureIsReportedAndDoesNotHotSpinTheCoordinator() throws Exception {
        MethodRuntime runtime = asyncRuntime(1);
        GovernanceRegistry registry = new GovernanceRegistry();
        MapDbAsyncExecutionEngine engine = new MapDbAsyncExecutionEngine(
                tempDir.resolve("fatal.db"),
                MapDbAsyncExecutionOptions.builder().workerShutdownAwaitMillis(500L).build()
        );
        try {
            registry.attachAsyncExecutionEngine(engine);
            registry.registerAsyncMethod(runtime, arguments -> { });

            // A consistency violation inside a write batch is fatal by design (partial mutation risk).
            BoundedStoreWriter writer = writer(engine);
            assertThrows(IllegalStateException.class, () -> writer.execute(() -> {
                throw new IllegalStateException("simulated store consistency failure");
            }, 0L));

            waitFor(() -> !engine.workerHealth().healthy());
            long failuresAfterDetection = engine.workerHealth().workerFailures();
            TimeUnit.MILLISECONDS.sleep(500L);
            MapDbWorkerHealth health = engine.workerHealth();

            assertFalse(health.healthy(), health::toString);
            assertFalse(health.writerAccepting());
            assertNotNull(health.storeFailureType());
            assertTrue(
                    health.workerFailures() - failuresAfterDetection < 20,
                    () -> "coordinator kept failing: " + health
            );
            AsyncAdmissionRejectedException rejected = assertThrows(
                    AsyncAdmissionRejectedException.class,
                    () -> registry.enqueueAsync(runtime, new Object[]{"after-failure"})
            );
            assertNotNull(rejected.reason());
        } finally {
            try {
                engine.close();
            } catch (RuntimeException expected) {
                // The underlying DB is already closed; close reports it but must not hang.
            }
        }
    }

    @Test
    void unreadableStoreIsClassifiedAsStorageFailureAndReportedUnhealthy() throws Exception {
        MapDbAsyncExecutionEngine engine = new MapDbAsyncExecutionEngine(
                tempDir.resolve("unreadable.db"),
                MapDbAsyncExecutionOptions.builder().workerShutdownAwaitMillis(500L).build()
        );
        try {
            Field dbField = MapDbAsyncExecutionEngine.class.getDeclaredField("db");
            dbField.setAccessible(true);
            ((DB) dbField.get(engine)).close();

            AsyncAdmissionRejectedException failure = assertThrows(
                    AsyncAdmissionRejectedException.class,
                    () -> engine.snapshot(SERVICE_ID, METHOD_ID, 10)
            );
            assertEquals(AsyncAdmissionRejectedException.Reason.STORAGE_FAILURE, failure.reason());
            assertFalse(engine.workerHealth().healthy());
            assertNotNull(engine.workerHealth().storeFailureType());
        } finally {
            try {
                engine.close();
            } catch (RuntimeException | Error expected) {
                // the volume is already closed
            }
        }
    }

    private static QueueStore.NewInvocation invocation(String idempotencyKey) {
        return new QueueStore.NewInvocation(
                SERVICE_ID,
                METHOD_ID,
                "test-codec",
                new byte[]{1, 2, 3},
                null,
                null,
                null,
                idempotencyKey,
                System.currentTimeMillis()
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

    private static BoundedStoreWriter writer(MapDbAsyncExecutionEngine engine) throws Exception {
        Field field = MapDbAsyncExecutionEngine.class.getDeclaredField("writer");
        field.setAccessible(true);
        return (BoundedStoreWriter) field.get(engine);
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
