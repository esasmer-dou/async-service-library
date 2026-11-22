package com.reactor.asl.mapdb;

import com.reactor.asl.core.AsyncInvocationContext;
import com.reactor.asl.core.AsyncAdmissionRejectedException;
import com.reactor.asl.core.AsyncPayloadCodec;
import com.reactor.asl.core.ExecutionMode;
import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.JavaObjectStreamAsyncPayloadCodec;
import com.reactor.asl.core.MethodDescriptor;
import com.reactor.asl.core.MethodRuntime;
import com.reactor.asl.core.ServiceDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapDbV2ExecutionTest {
    @TempDir
    Path tempDir;

    @Test
    void preservesFifoAcrossRestart() throws Exception {
        Path databasePath = tempDir.resolve("fifo.db");
        MethodRuntime runtime = runtime("fifo.service", "publish(java.lang.String)", 0, 1);
        GovernanceRegistry firstRegistry = new GovernanceRegistry();

        try (MapDbAsyncExecutionEngine engine = engine(databasePath, options().build())) {
            firstRegistry.attachAsyncExecutionEngine(engine);
            firstRegistry.registerAsyncMethod(runtime, ignored -> { });
            firstRegistry.enqueueAsync(runtime, new Object[]{"one"});
            firstRegistry.enqueueAsync(runtime, new Object[]{"two"});
            firstRegistry.enqueueAsync(runtime, new Object[]{"three"});
            assertEquals(3, engine.snapshot(runtime.serviceId(), runtime.methodId(), 10).pendingCount());
        }

        List<String> consumed = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch completed = new CountDownLatch(3);
        GovernanceRegistry secondRegistry = new GovernanceRegistry();
        try (MapDbAsyncExecutionEngine reopened = engine(databasePath, options().build())) {
            secondRegistry.attachAsyncExecutionEngine(reopened);
            secondRegistry.registerAsyncMethod(runtime, arguments -> {
                consumed.add((String) arguments[0]);
                completed.countDown();
            });
            runtime.setConsumerThreads(1);
            reopened.applyRuntime(runtime);

            assertTrue(completed.await(5, TimeUnit.SECONDS));
            waitFor(() -> reopened.snapshot(runtime.serviceId(), runtime.methodId(), 10).entries().isEmpty());
            assertEquals(List.of("one", "two", "three"), consumed);
        }
    }

    @Test
    void deduplicatesActiveAndRecentlyCompletedInvocations() throws Exception {
        MethodRuntime runtime = runtime("idempotent.service", "publish(java.lang.String)", 0, 1);
        AtomicInteger calls = new AtomicInteger();
        GovernanceRegistry registry = new GovernanceRegistry();

        try (MapDbAsyncExecutionEngine engine = engine(tempDir.resolve("idempotent.db"), options().build())) {
            registry.attachAsyncExecutionEngine(engine);
            registry.registerAsyncMethod(runtime, ignored -> calls.incrementAndGet());

            AsyncInvocationContext context = AsyncInvocationContext.idempotent("order-42");
            registry.enqueueAsync(runtime, new Object[]{"first"}, context);
            registry.enqueueAsync(runtime, new Object[]{"duplicate"}, context);
            assertEquals(1, engine.snapshot(runtime.serviceId(), runtime.methodId(), 10).pendingCount());

            runtime.setConsumerThreads(1);
            engine.applyRuntime(runtime);
            waitFor(() -> calls.get() == 1 && engine.snapshot(runtime.serviceId(), runtime.methodId(), 10).entries().isEmpty());

            registry.enqueueAsync(runtime, new Object[]{"duplicate-after-success"}, context);
            Thread.sleep(100L);
            assertEquals(1, calls.get());
            assertTrue(engine.snapshot(runtime.serviceId(), runtime.methodId(), 10).entries().isEmpty());
        }
    }

    @Test
    void retriesWithBackoffUntilSuccessAndKeepsOneDurableRecord() throws Exception {
        MethodRuntime runtime = runtime("retry.service", "publish(java.lang.String)", 1, 1);
        AtomicInteger calls = new AtomicInteger();
        GovernanceRegistry registry = new GovernanceRegistry();
        MapDbAsyncExecutionOptions configured = options()
                .maxAttempts(3)
                .retryInitialBackoffMillis(10L)
                .retryMaxBackoffMillis(20L)
                .retryJitterRatio(0.0d)
                .build();

        try (MapDbAsyncExecutionEngine engine = engine(tempDir.resolve("retry.db"), configured)) {
            registry.attachAsyncExecutionEngine(engine);
            registry.registerAsyncMethod(runtime, ignored -> {
                if (calls.incrementAndGet() < 3) {
                    throw new IllegalStateException("retry me");
                }
            });
            registry.enqueueAsync(runtime, new Object[]{"payload"});

            waitFor(() -> runtime.stats().successCount() == 1L);
            assertEquals(3, calls.get());
            assertEquals(2, runtime.stats().errorCount());
            assertTrue(engine.snapshot(runtime.serviceId(), runtime.methodId(), 10).entries().isEmpty());
        }
    }

    @Test
    void boundsOptInFailureMessagesBeforePersistingThem() throws Exception {
        MethodRuntime runtime = runtime("bounded-error.service", "publish(java.lang.String)", 1, 1);
        GovernanceRegistry registry = new GovernanceRegistry();
        int errorLimit = 64;
        MapDbAsyncExecutionOptions configured = options()
                .maxAttempts(1)
                .maxErrorCharacters(errorLimit)
                .errorDetailPolicy(ErrorDetailPolicy.TYPE_AND_MESSAGE)
                .build();

        try (MapDbAsyncExecutionEngine engine = engine(tempDir.resolve("bounded-error.db"), configured)) {
            registry.attachAsyncExecutionEngine(engine);
            registry.registerAsyncMethod(runtime, ignored -> {
                throw new IllegalStateException("sensitive-" + "x".repeat(10_000));
            });
            registry.enqueueAsync(runtime, new Object[]{"payload"});

            waitFor(() -> engine.snapshot(runtime.serviceId(), runtime.methodId(), 10).failedCount() == 1);
            String lastError = engine.snapshot(runtime.serviceId(), runtime.methodId(), 10)
                    .entries().getFirst().lastError();
            assertEquals(errorLimit, lastError.length());
            assertTrue(lastError.startsWith(IllegalStateException.class.getName() + ": sensitive-"));
        }
    }

    @Test
    void enforcesLeaseOwnershipForStateTransitions() throws Exception {
        try (MapDbAsyncExecutionEngine engine = engine(tempDir.resolve("lease.db"), options().build())) {
            QueueStore store = queueStore(engine);
            QueueStore.EnqueueResult enqueued = store.enqueue(invocation("lease.service", "publish()", "lease"));
            QueueStore.ClaimedInvocation claimed = store
                    .claimNext("lease.service", "publish()", System.currentTimeMillis())
                    .orElseThrow();

            assertEquals(enqueued.invocationId(), claimed.invocationId());
            assertFalse(store.complete(claimed.invocationId(), claimed.leaseToken() + 1));
            assertFalse(store.renewLease(claimed.invocationId(), claimed.leaseToken() + 1, System.currentTimeMillis() + 1_000));
            assertFalse(store.fail(
                    claimed.invocationId(),
                    claimed.leaseToken() + 1,
                    "error",
                    "type",
                    "BUSINESS",
                    System.currentTimeMillis() + 10
            ).applied());
            assertTrue(store.complete(claimed.invocationId(), claimed.leaseToken()));
        }
    }

    @Test
    void batchesConcurrentDurableAdmissionsAndDoesNotCreateIdleWorkers() throws Exception {
        MethodRuntime runtime = runtime("batch.service", "publish(java.lang.String)", 0, 8);
        MapDbAsyncExecutionOptions configured = options()
                .commitBatchSize(32)
                .commitBatchWindowMillis(100L)
                .maxWorkerThreads(2)
                .build();
        GovernanceRegistry registry = new GovernanceRegistry();

        try (MapDbAsyncExecutionEngine engine = engine(tempDir.resolve("batch.db"), configured)) {
            registry.attachAsyncExecutionEngine(engine);
            registry.registerAsyncMethod(runtime, ignored -> { });
            assertEquals(0, engine.workerHealth().poolSize());

            int invocationCount = 24;
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService callers = Executors.newFixedThreadPool(invocationCount);
            try {
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (int i = 0; i < invocationCount; i++) {
                    int value = i;
                    futures.add(CompletableFuture.runAsync(() -> {
                        await(start);
                        registry.enqueueAsync(runtime, new Object[]{"payload-" + value});
                    }, callers));
                }
                start.countDown();
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
            } finally {
                callers.shutdownNow();
            }

            MapDbWriterMetrics metrics = engine.writerMetrics();
            assertEquals(invocationCount, metrics.acceptedCommands());
            assertEquals(invocationCount, metrics.committedCommands());
            assertTrue(metrics.commits() < metrics.committedCommands());
            assertTrue(metrics.maxCommitNanos() > 0L);
            assertEquals(0, engine.workerHealth().poolSize());
        }
    }

    @Test
    void boundsConcurrentSerializationBeforePayloadAllocationCanFanOut() throws Exception {
        MethodRuntime runtime = runtime("bounded-encode.service", "publish(java.lang.String)", 0, 1);
        GovernanceRegistry registry = new GovernanceRegistry();
        BlockingFirstEncodeCodec codec = new BlockingFirstEncodeCodec();
        MapDbAsyncExecutionOptions configured = options()
                .maxConcurrentEnqueues(1)
                .build();

        try (MapDbAsyncExecutionEngine engine = new MapDbAsyncExecutionEngine(
                tempDir.resolve("bounded-encode.db"),
                codec,
                configured
        )) {
            registry.attachAsyncExecutionEngine(engine);
            registry.registerAsyncMethod(runtime, ignored -> { });
            CompletableFuture<Void> first = CompletableFuture.runAsync(
                    () -> registry.enqueueAsync(runtime, new Object[]{"first"})
            );
            assertTrue(codec.encodingStarted.await(5, TimeUnit.SECONDS));
            assertEquals(1, engine.activeEnqueues());

            AsyncAdmissionRejectedException rejection = assertThrows(
                    AsyncAdmissionRejectedException.class,
                    () -> registry.enqueueAsync(runtime, new Object[]{"second"})
            );
            assertEquals(AsyncAdmissionRejectedException.Reason.WRITER_SATURATED, rejection.reason());

            codec.releaseEncoding.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertEquals(0, engine.activeEnqueues());
            assertEquals(1, engine.snapshot(runtime.serviceId(), runtime.methodId(), 10).pendingCount());
        } finally {
            codec.releaseEncoding.countDown();
        }
    }

    private MapDbAsyncExecutionEngine engine(Path path, MapDbAsyncExecutionOptions options) {
        return new MapDbAsyncExecutionEngine(path, new JavaObjectStreamAsyncPayloadCodec(), options);
    }

    private static MapDbAsyncExecutionOptions.Builder options() {
        return MapDbAsyncExecutionOptions.builder()
                .globalLimits(new AsyncQueueLimits(1_000, 16L * 1024L * 1024L))
                .defaultLaneLimits(new AsyncQueueLimits(1_000, 8L * 1024L * 1024L));
    }

    private static MethodRuntime runtime(String serviceId, String methodId, int consumers, int maxConcurrency) {
        GovernanceRegistry registry = new GovernanceRegistry();
        MethodRuntime runtime = registry.register(new ServiceDescriptor(
                serviceId,
                new MethodDescriptor[]{new MethodDescriptor(
                        methodId,
                        "publish",
                        true,
                        maxConcurrency,
                        "disabled",
                        true,
                        consumers
                )}
        )).methodById(methodId);
        runtime.switchMode(ExecutionMode.ASYNC);
        return runtime;
    }

    private static QueueStore.NewInvocation invocation(String serviceId, String methodId, String value) {
        JavaObjectStreamAsyncPayloadCodec codec = new JavaObjectStreamAsyncPayloadCodec();
        Object[] arguments = {value};
        return new QueueStore.NewInvocation(
                serviceId,
                methodId,
                codec.id(),
                codec.encode(serviceId, methodId, arguments),
                value,
                String.class.getName(),
                null,
                null,
                System.currentTimeMillis()
        );
    }

    private static QueueStore queueStore(MapDbAsyncExecutionEngine engine) throws Exception {
        Field field = MapDbAsyncExecutionEngine.class.getDeclaredField("queueStore");
        field.setAccessible(true);
        return (QueueStore) field.get(engine);
    }

    private static void waitFor(Condition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            if (condition.matches()) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("Condition was not met within timeout");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean matches() throws Exception;
    }

    private static final class BlockingFirstEncodeCodec implements AsyncPayloadCodec {
        private final JavaObjectStreamAsyncPayloadCodec delegate = new JavaObjectStreamAsyncPayloadCodec();
        private final AtomicBoolean first = new AtomicBoolean(true);
        private final CountDownLatch encodingStarted = new CountDownLatch(1);
        private final CountDownLatch releaseEncoding = new CountDownLatch(1);

        @Override
        public String id() {
            return delegate.id();
        }

        @Override
        public byte[] encode(String serviceId, String methodId, Object[] arguments) {
            if (first.compareAndSet(true, false)) {
                encodingStarted.countDown();
                await(releaseEncoding);
            }
            return delegate.encode(serviceId, methodId, arguments);
        }

        @Override
        public Object[] decode(String serviceId, String methodId, byte[] payload) {
            return delegate.decode(serviceId, methodId, payload);
        }

        @Override
        public String summarize(String serviceId, String methodId, Object[] arguments) {
            return delegate.summarize(serviceId, methodId, arguments);
        }
    }
}
