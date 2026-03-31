package com.reactor.asl.mapdb;

import com.reactor.asl.core.AsyncPayloadCodec;
import com.reactor.asl.core.AsyncPayloadCodecException;
import com.reactor.asl.core.AsyncPayloadMetadata;
import com.reactor.asl.core.ExecutionMode;
import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.JavaObjectStreamAsyncPayloadCodec;
import com.reactor.asl.core.MethodBufferSnapshot;
import com.reactor.asl.core.MethodDescriptor;
import com.reactor.asl.core.MethodRuntime;
import com.reactor.asl.core.ServiceDescriptor;
import com.reactor.asl.core.ServiceRuntime;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import org.mapdb.HTreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapDbAsyncExecutionEngineCoverageTest {
    @Test
    void handlesUnsupportedOperationsAndEmptySnapshots() throws Exception {
        try (MapDbAsyncExecutionEngine engine = new MapDbAsyncExecutionEngine(testDbPath("empty"))) {
            assertFalse(engine.supports("svc", "method()"));
            MethodBufferSnapshot empty = engine.snapshot("svc", "method()", 10);
            assertTrue(empty.available());
            assertEquals(0, empty.pendingCount());
            assertEquals(0, engine.clear("svc", "method()"));
            assertFalse(engine.delete("svc", "method()", "1"));
            assertFalse(engine.replay("svc", "method()", "1"));
            assertThrows(IllegalArgumentException.class, () -> engine.delete("svc", "method()", "bad-id"));
        }
    }

    @Test
    void ignoresNonAsyncRegistrationAndRejectsNonAsyncEnqueue() throws Exception {
        GovernanceRegistry registry = new GovernanceRegistry();
        ServiceRuntime service = registry.register(new ServiceDescriptor(
                "svc",
                new MethodDescriptor[]{new MethodDescriptor("sync()", "sync", true, 1, "", false, 0)}
        ));
        MethodRuntime runtime = service.methodById("sync()");

        try (MapDbAsyncExecutionEngine engine = new MapDbAsyncExecutionEngine(testDbPath("sync"))) {
            engine.register(runtime, arguments -> {
            });
            engine.applyRuntime(runtime);
            assertFalse(engine.supports("svc", "sync()"));
            assertThrows(IllegalStateException.class, () -> engine.enqueue(runtime, new Object[]{"x"}));
        }
    }

    @Test
    void rebuildsPendingQueueAfterRestartAndProcessesNullArguments() throws Exception {
        GovernanceRegistry registry = new GovernanceRegistry();
        ServiceRuntime service = registry.register(new ServiceDescriptor(
                "restart.service",
                new MethodDescriptor[]{new MethodDescriptor("publish()", "publish", true, 1, "", true, 0)}
        ));
        MethodRuntime runtime = service.methodById("publish()");
        runtime.switchMode(ExecutionMode.ASYNC);

        Path dbPath = testDbPath("restart");
        try (MapDbAsyncExecutionEngine engine = new MapDbAsyncExecutionEngine(dbPath)) {
            registry.attachAsyncExecutionEngine(engine);
            registry.registerAsyncMethod(runtime, arguments -> {
            });
            registry.enqueueAsync(runtime, null);
            waitFor(() -> engine.snapshot("restart.service", "publish()", 10).pendingCount() == 1, 2);
            assertEquals("(no arguments)", engine.snapshot("restart.service", "publish()", 10).entries().getFirst().summary());
        }

        CountDownLatch consumed = new CountDownLatch(1);
        try (MapDbAsyncExecutionEngine reopened = new MapDbAsyncExecutionEngine(dbPath)) {
            registry.attachAsyncExecutionEngine(reopened);
            registry.registerAsyncMethod(runtime, arguments -> consumed.countDown());
            runtime.setConsumerThreads(1);
            reopened.applyRuntime(runtime);

            assertTrue(consumed.await(5, TimeUnit.SECONDS));
            waitFor(() -> reopened.snapshot("restart.service", "publish()", 10).pendingCount() == 0, 5);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void recoversStaleInProgressEntriesAsFailedOnRestart() throws Exception {
        Path dbPath = testDbPath("stale-recovery");
        try (MapDbAsyncExecutionEngine engine = new MapDbAsyncExecutionEngine(dbPath)) {
            Field field = MapDbAsyncExecutionEngine.class.getDeclaredField("invocations");
            field.setAccessible(true);
            HTreeMap<Long, StoredAsyncInvocation> invocations = (HTreeMap<Long, StoredAsyncInvocation>) field.get(engine);
            invocations.put(1L, new StoredAsyncInvocation(
                    1L,
                    "svc",
                    "publish(java.lang.String)",
                    JavaObjectStreamAsyncPayloadCodec.CODEC_ID,
                    new JavaObjectStreamAsyncPayloadCodec().encode("svc", "publish(java.lang.String)", new Object[]{"payload"}),
                    "payload",
                    "java.lang.String",
                    null,
                    AsyncInvocationState.IN_PROGRESS,
                    System.currentTimeMillis(),
                    0,
                    null,
                    null,
                    null
            ));
            Field dbField = MapDbAsyncExecutionEngine.class.getDeclaredField("db");
            dbField.setAccessible(true);
            ((org.mapdb.DB) dbField.get(engine)).commit();
        }

        try (MapDbAsyncExecutionEngine reopened = new MapDbAsyncExecutionEngine(dbPath)) {
            MethodBufferSnapshot snapshot = reopened.snapshot("svc", "publish(java.lang.String)", 10);
            assertEquals(0, snapshot.pendingCount());
            assertEquals(1, snapshot.failedCount());
            assertEquals("FAILED", snapshot.entries().getFirst().state());
            assertEquals(MapDbAsyncExecutionEngine.RECOVERED_IN_PROGRESS_MESSAGE, snapshot.entries().getFirst().lastError());
            assertEquals("RECOVERY", snapshot.entries().getFirst().errorCategory());
        }
    }

    @Test
    void processesPayloadsThroughCustomCodecWithoutJavaSerialization() throws Exception {
        GovernanceRegistry registry = new GovernanceRegistry();
        ServiceRuntime service = registry.register(new ServiceDescriptor(
                "codec.service",
                new MethodDescriptor[]{new MethodDescriptor("publish(com.acme.Payload)", "publish", true, 1, "", true, 0)}
        ));
        MethodRuntime runtime = service.methodById("publish(com.acme.Payload)");
        runtime.switchMode(ExecutionMode.ASYNC);

        CountDownLatch consumed = new CountDownLatch(1);
        NonSerializablePayload payload = new NonSerializablePayload("custom-codec-value");

        try (MapDbAsyncExecutionEngine engine = new MapDbAsyncExecutionEngine(
                testDbPath("custom-codec"),
                new TestPayloadCodec()
        )) {
            registry.attachAsyncExecutionEngine(engine);
            registry.registerAsyncMethod(runtime, arguments -> {
                assertEquals(1, arguments.length);
                assertEquals(payload.value(), ((NonSerializablePayload) arguments[0]).value());
                consumed.countDown();
            });

            registry.enqueueAsync(runtime, new Object[]{payload});
            waitFor(() -> engine.snapshot("codec.service", "publish(com.acme.Payload)", 10).pendingCount() == 1, 2);
            assertEquals("custom-codec-value", engine.snapshot("codec.service", "publish(com.acme.Payload)", 10).entries().getFirst().summary());
            assertEquals("test-payload-codec", engine.snapshot("codec.service", "publish(com.acme.Payload)", 10).entries().getFirst().codecId());

            runtime.setConsumerThreads(1);
            engine.applyRuntime(runtime);

            assertTrue(consumed.await(5, TimeUnit.SECONDS));
            waitFor(() -> engine.snapshot("codec.service", "publish(com.acme.Payload)", 10).pendingCount() == 0, 5);
        }
    }

    @Test
    void failsCloseWhenWorkerDoesNotDrainWithinAwaitTimeout() throws Exception {
        GovernanceRegistry registry = new GovernanceRegistry();
        ServiceRuntime service = registry.register(new ServiceDescriptor(
                "slow.service",
                new MethodDescriptor[]{new MethodDescriptor("publish(java.lang.String)", "publish", true, 1, "", true, 0)}
        ));
        MethodRuntime runtime = service.methodById("publish(java.lang.String)");
        runtime.switchMode(ExecutionMode.ASYNC);

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean closedCleanly = new AtomicBoolean(false);

        MapDbAsyncExecutionEngine engine = new MapDbAsyncExecutionEngine(
                testDbPath("shutdown-timeout"),
                new JavaObjectStreamAsyncPayloadCodec(),
                25L
        );
        try {
            registry.attachAsyncExecutionEngine(engine);
            registry.registerAsyncMethod(runtime, arguments -> {
                started.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            });

            runtime.setConsumerThreads(1);
            engine.applyRuntime(runtime);
            registry.enqueueAsync(runtime, new Object[]{"slow"});

            assertTrue(started.await(5, TimeUnit.SECONDS));

            IllegalStateException exception = assertThrows(IllegalStateException.class, engine::close);
            assertNotNull(exception.getMessage());
            assertTrue(exception.getMessage().contains("Timed out while waiting for worker shutdown"));
        } finally {
            release.countDown();
            waitFor(() -> runtime.stats().successCount() == 1, 5);
            engine.close();
            closedCleanly.set(true);
        }

        assertTrue(closedCleanly.get());
    }

    @Test
    void classifiesDecodeAndMigrationFailuresSeparately() throws Exception {
        assertFailureCategory(new FailingDecodeCodec("No migration path from schema-v1 to schema-v2"), "MIGRATION");
        assertFailureCategory(new FailingDecodeCodec("Malformed async payload bytes"), "DECODE");
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

    private record NonSerializablePayload(String value) {
    }

    private void assertFailureCategory(AsyncPayloadCodec codec, String expectedCategory) throws Exception {
        GovernanceRegistry registry = new GovernanceRegistry();
        ServiceRuntime service = registry.register(new ServiceDescriptor(
                "decode.service",
                new MethodDescriptor[]{new MethodDescriptor("publish(java.lang.String)", "publish", true, 1, "", true, 0)}
        ));
        MethodRuntime runtime = service.methodById("publish(java.lang.String)");
        runtime.switchMode(ExecutionMode.ASYNC);

        try (MapDbAsyncExecutionEngine engine = new MapDbAsyncExecutionEngine(
                testDbPath("decode-" + expectedCategory.toLowerCase()),
                codec
        )) {
            registry.attachAsyncExecutionEngine(engine);
            registry.registerAsyncMethod(runtime, arguments -> {
            });

            registry.enqueueAsync(runtime, new Object[]{"payload"});
            runtime.setConsumerThreads(1);
            engine.applyRuntime(runtime);

            waitFor(() -> engine.snapshot("decode.service", "publish(java.lang.String)", 10).failedCount() == 1, 5);
            MethodBufferSnapshot snapshot = engine.snapshot("decode.service", "publish(java.lang.String)", 10);
            assertEquals(expectedCategory, snapshot.entries().getFirst().errorCategory());
            assertEquals(AsyncPayloadCodecException.class.getName(), snapshot.entries().getFirst().lastErrorType());
        }
    }

    private static final class TestPayloadCodec implements AsyncPayloadCodec {
        @Override
        public String id() {
            return "test-payload-codec";
        }

        @Override
        public byte[] encode(String serviceId, String methodId, Object[] arguments) {
            return ((NonSerializablePayload) arguments[0]).value().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public Object[] decode(String serviceId, String methodId, byte[] payload) {
            return new Object[]{new NonSerializablePayload(new String(payload, StandardCharsets.UTF_8))};
        }

        @Override
        public String summarize(String serviceId, String methodId, Object[] arguments) {
            return ((NonSerializablePayload) arguments[0]).value();
        }

        @Override
        public com.reactor.asl.core.AsyncPayloadMetadata describe(String serviceId, String methodId, Object[] arguments, byte[] payload) {
            return new com.reactor.asl.core.AsyncPayloadMetadata("custom.nonserializable.payload", "schema-v1");
        }
    }

    private static final class FailingDecodeCodec implements AsyncPayloadCodec {
        private final String message;

        private FailingDecodeCodec(String message) {
            this.message = message;
        }

        @Override
        public String id() {
            return "failing-decode-codec";
        }

        @Override
        public byte[] encode(String serviceId, String methodId, Object[] arguments) {
            return "payload".getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public Object[] decode(String serviceId, String methodId, byte[] payload) {
            throw new AsyncPayloadCodecException(message);
        }

        @Override
        public String summarize(String serviceId, String methodId, Object[] arguments) {
            return "payload";
        }

        @Override
        public AsyncPayloadMetadata describe(String serviceId, String methodId, Object[] arguments, byte[] payload) {
            return new AsyncPayloadMetadata("decode.test.payload", "schema-v1");
        }
    }

    private static Path testDbPath(String prefix) throws IOException {
        Path dir = Path.of("target", "test-data");
        Files.createDirectories(dir);
        return dir.resolve(prefix + "-" + UUID.randomUUID() + ".db");
    }
}
