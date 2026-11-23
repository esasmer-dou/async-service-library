package com.reactor.asl.mapdb;

import com.reactor.asl.core.AsyncAdmissionRejectedException;
import com.reactor.asl.core.AsyncExecutionEngine;
import com.reactor.asl.core.AsyncInvocationContext;
import com.reactor.asl.core.AsyncMethodBinding;
import com.reactor.asl.core.AsyncPayloadCodec;
import com.reactor.asl.core.AsyncPayloadCodecException;
import com.reactor.asl.core.AsyncPayloadMetadata;
import com.reactor.asl.core.ExecutionMode;
import com.reactor.asl.core.JavaObjectStreamAsyncPayloadCodec;
import com.reactor.asl.core.MethodBufferSnapshot;
import com.reactor.asl.core.MethodRuntime;
import org.mapdb.DB;
import org.mapdb.DBMaker;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

public final class MapDbAsyncExecutionEngine implements AsyncExecutionEngine, AutoCloseable {
    static final String RECOVERED_IN_PROGRESS_MESSAGE = "Recovered stale in-progress invocation after restart";
    private static final long DEFAULT_WORKER_SHUTDOWN_AWAIT_MILLIS = 10_000L;
    private static final long DEFAULT_REGISTRATION_IDLE_SLEEP_MILLIS = 100L;
    private static final long DEFAULT_EMPTY_QUEUE_SLEEP_MILLIS = 50L;
    private static final long DEFAULT_REQUEUE_DELAY_MILLIS = 75L;
    private static final long MAX_FAILURE_BACKOFF_MILLIS = 1_000L;
    private static final boolean DEFAULT_TRANSACTIONS_ENABLED = true;
    private static final boolean DEFAULT_MEMORY_MAPPED_ENABLED = false;
    private static final String ERROR_CATEGORY_DECODE = "DECODE";
    private static final String ERROR_CATEGORY_MIGRATION = "MIGRATION";
    private static final String ERROR_CATEGORY_BUSINESS = "BUSINESS";
    private static final String ERROR_CATEGORY_TIMEOUT = "TIMEOUT";

    private final DB db;
    private final MapDbStoreLifecycle storeLifecycle;
    private final MapDbQueueStore queueStore;
    private final BoundedStoreWriter writer;
    private final AsyncPayloadCodec payloadCodec;
    private final MapDbAsyncExecutionOptions options;
    private final Map<String, MethodRegistration> registrations = new ConcurrentHashMap<>();
    private final Map<String, LaneControl> laneControls = new ConcurrentHashMap<>();
    private final Map<Long, ActiveLease> activeLeases = new ConcurrentHashMap<>();
    private final java.util.List<java.util.function.BiConsumer<String, String>> laneListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Semaphore schedulerSignal = new Semaphore(0);
    private final Semaphore enqueuePermits;
    private final ThreadPoolExecutor workers;
    private final Thread coordinatorThread;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object closeMonitor = new Object();
    private boolean resourcesClosed;
    private final AtomicLong lastCoordinatorHeartbeat = new AtomicLong();
    private final AtomicLong lastLeaseRenewal = new AtomicLong();
    private final LongAdder workerFailures = new LongAdder();
    private final LongAdder rejectedSchedules = new LongAdder();
    private final LongAdder executionTimeouts = new LongAdder();
    private final LongAdder rateLimitedSchedules = new LongAdder();
    private final LongAdder reclaimedLeases = new LongAdder();
    private final LongAdder releasedOnShutdown = new LongAdder();
    private final AtomicReference<String> lastWorkerFailure = new AtomicReference<>();

    public MapDbAsyncExecutionEngine(Path databasePath) {
        this(databasePath, new JavaObjectStreamAsyncPayloadCodec(), MapDbAsyncExecutionOptions.builder().build());
    }

    public MapDbAsyncExecutionEngine(Path databasePath, AsyncPayloadCodec payloadCodec) {
        this(databasePath, payloadCodec, MapDbAsyncExecutionOptions.builder().build());
    }

    public MapDbAsyncExecutionEngine(Path databasePath, MapDbCorruptionPolicy corruptionPolicy) {
        this(
                databasePath,
                new JavaObjectStreamAsyncPayloadCodec(),
                MapDbAsyncExecutionOptions.builder().corruptionPolicy(corruptionPolicy).build()
        );
    }

    public MapDbAsyncExecutionEngine(Path databasePath, MapDbAsyncExecutionOptions options) {
        this(databasePath, new JavaObjectStreamAsyncPayloadCodec(), options);
    }

    public MapDbAsyncExecutionEngine(
            Path databasePath,
            AsyncPayloadCodec payloadCodec,
            MapDbCorruptionPolicy corruptionPolicy
    ) {
        this(
                databasePath,
                payloadCodec,
                MapDbAsyncExecutionOptions.builder().corruptionPolicy(corruptionPolicy).build()
        );
    }

    public MapDbAsyncExecutionEngine(Path databasePath, AsyncPayloadCodec payloadCodec, long workerShutdownAwaitMillis) {
        this(
                databasePath,
                payloadCodec,
                MapDbAsyncExecutionOptions.builder()
                        .workerShutdownAwaitMillis(workerShutdownAwaitMillis)
                        .build()
        );
    }

    public MapDbAsyncExecutionEngine(
            Path databasePath,
            AsyncPayloadCodec payloadCodec,
            long workerShutdownAwaitMillis,
            boolean transactionsEnabled,
            boolean memoryMappedEnabled
    ) {
        this(
                databasePath,
                payloadCodec,
                MapDbAsyncExecutionOptions.builder()
                        .workerShutdownAwaitMillis(workerShutdownAwaitMillis)
                        .transactionsEnabled(transactionsEnabled)
                        .memoryMappedEnabled(memoryMappedEnabled)
                        .build()
        );
    }

    /**
     * @deprecated Header checksum bypass can expose an unverified store. Use an explicit
     * {@link MapDbCorruptionPolicy} instead.
     */
    @Deprecated(forRemoval = true)
    public MapDbAsyncExecutionEngine(
            Path databasePath,
            AsyncPayloadCodec payloadCodec,
            long workerShutdownAwaitMillis,
            boolean transactionsEnabled,
            boolean memoryMappedEnabled,
            boolean checksumHeaderBypass
    ) {
        this(
                databasePath,
                payloadCodec,
                MapDbAsyncExecutionOptions.builder()
                        .workerShutdownAwaitMillis(workerShutdownAwaitMillis)
                        .transactionsEnabled(transactionsEnabled)
                        .memoryMappedEnabled(memoryMappedEnabled)
                        .corruptionPolicy(rejectChecksumHeaderBypass(checksumHeaderBypass))
                        .build()
        );
    }

    public MapDbAsyncExecutionEngine(
            Path databasePath,
            AsyncPayloadCodec payloadCodec,
            long workerShutdownAwaitMillis,
            long registrationIdleSleepMillis,
            long emptyQueueSleepMillis,
            long requeueDelayMillis,
            String recoveredInProgressMessage,
            boolean transactionsEnabled,
            boolean memoryMappedEnabled,
            MapDbCorruptionPolicy corruptionPolicy
    ) {
        this(
                databasePath,
                payloadCodec,
                MapDbAsyncExecutionOptions.builder()
                        .workerShutdownAwaitMillis(workerShutdownAwaitMillis)
                        .registrationIdleSleepMillis(registrationIdleSleepMillis)
                        .emptyQueueSleepMillis(emptyQueueSleepMillis)
                        .requeueDelayMillis(requeueDelayMillis)
                        .recoveredInProgressMessage(recoveredInProgressMessage)
                        .transactionsEnabled(transactionsEnabled)
                        .memoryMappedEnabled(memoryMappedEnabled)
                        .corruptionPolicy(corruptionPolicy)
                        .build()
        );
    }

    /**
     * @deprecated Header checksum bypass can expose an unverified store. Use an explicit
     * {@link MapDbCorruptionPolicy} instead.
     */
    @Deprecated(forRemoval = true)
    public MapDbAsyncExecutionEngine(
            Path databasePath,
            AsyncPayloadCodec payloadCodec,
            long workerShutdownAwaitMillis,
            long registrationIdleSleepMillis,
            long emptyQueueSleepMillis,
            long requeueDelayMillis,
            String recoveredInProgressMessage,
            boolean transactionsEnabled,
            boolean memoryMappedEnabled,
            boolean checksumHeaderBypass
    ) {
        this(
                databasePath,
                payloadCodec,
                MapDbAsyncExecutionOptions.builder()
                        .workerShutdownAwaitMillis(workerShutdownAwaitMillis)
                        .registrationIdleSleepMillis(registrationIdleSleepMillis)
                        .emptyQueueSleepMillis(emptyQueueSleepMillis)
                        .requeueDelayMillis(requeueDelayMillis)
                        .recoveredInProgressMessage(recoveredInProgressMessage)
                        .transactionsEnabled(transactionsEnabled)
                        .memoryMappedEnabled(memoryMappedEnabled)
                        .corruptionPolicy(rejectChecksumHeaderBypass(checksumHeaderBypass))
                        .build()
        );
    }

    public MapDbAsyncExecutionEngine(
            Path databasePath,
            AsyncPayloadCodec payloadCodec,
            MapDbAsyncExecutionOptions options
    ) {
        Objects.requireNonNull(databasePath, "databasePath");
        this.payloadCodec = Objects.requireNonNull(payloadCodec, "payloadCodec");
        this.options = Objects.requireNonNull(options, "options");
        this.enqueuePermits = new Semaphore(options.maxConcurrentEnqueues());

        MapDbStoreLifecycle preparedLifecycle = MapDbStoreLifecycle.open(databasePath, options.corruptionPolicy());
        DB openedDb = null;
        BoundedStoreWriter openedWriter = null;
        ThreadPoolExecutor openedWorkers = null;
        try {
            DBMaker.Maker maker = DBMaker.fileDB(preparedLifecycle.databasePath().toFile());
            if (options.transactionsEnabled()) {
                maker = maker.transactionEnable();
            }
            if (options.memoryMappedEnabled()) {
                maker = maker.fileMmapEnableIfSupported();
            }
            openedDb = maker.make();
            MapDbQueueStore openedStore = new MapDbQueueStore(openedDb, options);
            openedWriter = new BoundedStoreWriter(openedStore, options);
            openedWorkers = createWorkerPool(options);

            this.db = openedDb;
            this.queueStore = openedStore;
            this.writer = openedWriter;
            this.workers = openedWorkers;
            this.storeLifecycle = preparedLifecycle;
            this.coordinatorThread = new Thread(this::coordinate, "asl-mapdb-coordinator");
            this.coordinatorThread.setDaemon(true);
            this.coordinatorThread.setUncaughtExceptionHandler((thread, failure) -> recordWorkerFailure(failure));
            this.coordinatorThread.start();
        } catch (RuntimeException | Error failure) {
            if (openedWorkers != null) {
                openedWorkers.shutdownNow();
            }
            if (openedWriter != null) {
                try {
                    openedWriter.close();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            closeDbAfterFailure(openedDb, failure);
            try {
                preparedLifecycle.close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public Optional<MapDbStartupRecovery> startupRecovery() {
        return storeLifecycle.startupRecovery();
    }

    @Override
    public long projectionVersion() {
        return queueStore.projectionVersion();
    }

    public MapDbWriterMetrics writerMetrics() {
        return writer.metrics();
    }

    /** Depth of every registered async lane. */
    public java.util.List<MapDbLaneDepth> laneDepths() {
        long now = System.currentTimeMillis();
        java.util.List<MapDbLaneDepth> depths = new java.util.ArrayList<>(registrations.size());
        for (MethodRegistration registration : registrations.values()) {
            depths.add(laneDepth(registration.runtime.serviceId(), registration.runtime.methodId(), now));
        }
        return depths;
    }

    /** Current depth of one lane; ages are measured against the wall clock. */
    public MapDbLaneDepth laneDepth(String serviceId, String methodId) {
        return laneDepth(serviceId, methodId, System.currentTimeMillis());
    }

    private MapDbLaneDepth laneDepth(String serviceId, String methodId, long nowEpochMillis) {
        MapDbQueueStore.LaneDepth depth = queueStore.laneDepth(serviceId, methodId);
        long oldest = depth.oldestPendingEnqueuedAtEpochMillis();
        return new MapDbLaneDepth(
                serviceId,
                methodId,
                depth.pending(),
                depth.inProgress(),
                depth.retryWait(),
                depth.failed(),
                oldest == 0L ? 0L : Math.max(0L, nowEpochMillis - oldest)
        );
    }

    /**
     * Registers a callback invoked for every async lane, immediately for lanes already registered
     * and later for lanes registered afterwards. Used by metric binders.
     */
    public void onLaneRegistered(java.util.function.BiConsumer<String, String> listener) {
        Objects.requireNonNull(listener, "listener");
        laneListeners.add(listener);
        for (MethodRegistration registration : registrations.values()) {
            listener.accept(registration.runtime.serviceId(), registration.runtime.methodId());
        }
    }

    /** Times a consumer waited for a rate-limit permit instead of starting an execution. */
    public long rateLimitedSchedules() {
        return rateLimitedSchedules.sum();
    }

    /** Executions interrupted by the execution-timeout watchdog. */
    public long executionTimeouts() {
        return executionTimeouts.sum();
    }

    /** Orphaned in-progress invocations returned to the queue after their lease expired. */
    public long reclaimedLeases() {
        return reclaimedLeases.sum();
    }

    /** In-progress invocations returned to the queue because the engine stopped. */
    public long releasedOnShutdown() {
        return releasedOnShutdown.sum();
    }

    public int activeEnqueues() {
        return options.maxConcurrentEnqueues() - enqueuePermits.availablePermits();
    }

    public int maxConcurrentEnqueues() {
        return options.maxConcurrentEnqueues();
    }

    public MapDbWorkerHealth workerHealth() {
        int active = laneControls.values().stream().mapToInt(control -> control.active.get()).sum();
        RuntimeException storeFailure = queueStore.fatalFailure().orElse(null);
        return new MapDbWorkerHealth(
                !closed.get() && coordinatorThread.isAlive(),
                registrations.size(),
                active,
                workers.getPoolSize(),
                workers.getQueue().size(),
                workers.getCompletedTaskCount(),
                workerFailures.sum(),
                rejectedSchedules.sum(),
                lastCoordinatorHeartbeat.get(),
                lastWorkerFailure.get(),
                writer.isAccepting(),
                storeFailure == null ? null : truncate(storeFailure.getClass().getName(), options.maxErrorCharacters())
        );
    }

    @Override
    public void register(MethodRuntime runtime, AsyncMethodBinding binding) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(binding, "binding");
        ensureOpen();
        if (!runtime.asyncCapable()) {
            return;
        }
        String laneKey = key(runtime.serviceId(), runtime.methodId());
        MethodRegistration registration = new MethodRegistration(runtime, binding);
        MethodRegistration existing = registrations.putIfAbsent(laneKey, registration);
        if (existing != null && (existing.runtime != runtime || existing.binding != binding)) {
            throw new IllegalStateException("Async method binding already registered: " + laneKey);
        }
        laneControls.computeIfAbsent(laneKey, ignored -> new LaneControl());
        if (existing == null) {
            for (java.util.function.BiConsumer<String, String> listener : laneListeners) {
                try {
                    listener.accept(runtime.serviceId(), runtime.methodId());
                } catch (RuntimeException listenerFailure) {
                    recordWorkerFailure(listenerFailure);
                }
            }
        }
        signalScheduler();
    }

    @Override
    public void enqueue(MethodRuntime runtime, Object[] arguments) {
        enqueue(runtime, arguments, AsyncInvocationContext.none());
    }

    @Override
    public void enqueue(MethodRuntime runtime, Object[] arguments, AsyncInvocationContext context) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(context, "context");
        ensureOpen();
        if (!runtime.asyncCapable()) {
            throw new IllegalStateException("Method is not async-capable: " + runtime.methodId());
        }
        validateAdmissionInput(runtime, context);

        long admissionDeadlineNanos = admissionDeadlineNanos();
        acquireEnqueuePermit(admissionDeadlineNanos);
        try {
            Object[] safeArguments = arguments == null ? new Object[0] : arguments;
            byte[] encodedPayload = payloadCodec.encode(runtime.serviceId(), runtime.methodId(), safeArguments);
            AsyncPayloadMetadata payloadMetadata = payloadCodec.describe(
                    runtime.serviceId(),
                    runtime.methodId(),
                    safeArguments,
                    encodedPayload
            );
            QueueStore.NewInvocation invocation = new QueueStore.NewInvocation(
                    runtime.serviceId(),
                    runtime.methodId(),
                    payloadCodec.id(),
                    encodedPayload,
                    payloadSummary(runtime, safeArguments, payloadMetadata),
                    payloadMetadata.payloadType(),
                    payloadMetadata.payloadVersion(),
                    context.idempotencyKey(),
                    System.currentTimeMillis()
            );

            enqueueDurably(invocation, admissionDeadlineNanos);
            signalScheduler();
        } finally {
            enqueuePermits.release();
        }
    }

    @Override
    public boolean supports(String serviceId, String methodId) {
        return registrations.containsKey(key(serviceId, methodId));
    }

    @Override
    public MethodBufferSnapshot snapshot(String serviceId, String methodId, int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be zero or positive");
        }
        return queueStore.snapshot(serviceId, methodId, limit);
    }

    @Override
    public int clear(String serviceId, String methodId) {
        int cleared = internalWrite(() -> queueStore.clear(serviceId, methodId));
        signalScheduler();
        return cleared;
    }

    @Override
    public boolean delete(String serviceId, String methodId, String entryId) {
        boolean deleted = internalWrite(() -> queueStore.delete(serviceId, methodId, parseId(entryId)));
        signalScheduler();
        return deleted;
    }

    @Override
    public boolean replay(String serviceId, String methodId, String entryId) {
        boolean replayed = internalWrite(() -> queueStore.replay(serviceId, methodId, parseId(entryId)));
        if (replayed) {
            signalScheduler();
        }
        return replayed;
    }

    @Override
    public int deleteMatching(String serviceId, String methodId, com.reactor.asl.core.QueueEntryFilter filter) {
        int deleted = internalWrite(() -> queueStore.deleteMatching(serviceId, methodId, filter));
        signalScheduler();
        return deleted;
    }

    @Override
    public int replayMatching(String serviceId, String methodId, com.reactor.asl.core.QueueEntryFilter filter) {
        int replayed = internalWrite(() -> queueStore.replayMatching(serviceId, methodId, filter));
        if (replayed > 0) {
            signalScheduler();
        }
        return replayed;
    }

    @Override
    public void applyRuntime(MethodRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        if (runtime.asyncCapable()) {
            signalScheduler();
        }
    }

    @Override
    public void close() {
        closed.set(true);
        queueStore.stopAdmissions();
        signalScheduler();
        coordinatorThread.interrupt();

        synchronized (closeMonitor) {
            if (resourcesClosed) {
                return;
            }

            RuntimeException failure = null;
            failure = stopCoordinator(failure);
            failure = stopWorkers(failure);
            try {
                writer.close();
            } catch (RuntimeException exception) {
                // Closing MapDB while its single writer may still be inside a mutation can
                // corrupt the store. Keep the DB and ownership lock open so close can be retried.
                throw appendFailure(failure, exception);
            }

            if (!db.isClosed()) {
                try {
                    db.commit();
                } catch (RuntimeException commitFailure) {
                    failure = appendFailure(failure, commitFailure);
                    if (options.transactionsEnabled()) {
                        try {
                            db.rollback();
                        } catch (RuntimeException rollbackFailure) {
                            failure = appendFailure(failure, rollbackFailure);
                        }
                    }
                }
                try {
                    db.close();
                } catch (RuntimeException closeFailure) {
                    failure = appendFailure(failure, closeFailure);
                }
            }

            if (!db.isClosed()) {
                throw appendFailure(failure, new IllegalStateException(
                        "MapDB did not close; retaining the ownership lock to prevent concurrent access"
                ));
            }
            try {
                storeLifecycle.close();
                resourcesClosed = true;
            } catch (RuntimeException exception) {
                failure = appendFailure(failure, exception);
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private void enqueueDurably(QueueStore.NewInvocation invocation, long deadlineNanos) {
        while (true) {
            long observedVersion = queueStore.projectionVersion();
            long admissionWaitMillis = options.overflowPolicy() == QueueOverflowPolicy.REJECT
                    ? 0L
                    : remainingMillis(deadlineNanos);
            try {
                writer.execute(() -> queueStore.enqueue(invocation), admissionWaitMillis);
                return;
            } catch (AsyncAdmissionRejectedException rejection) {
                if (!isCapacityRejection(rejection)
                        || options.overflowPolicy() == QueueOverflowPolicy.REJECT) {
                    throw rejection;
                }
                long remainingMillis = remainingMillis(deadlineNanos);
                if (remainingMillis <= 0L) {
                    throw new AsyncAdmissionRejectedException(
                            AsyncAdmissionRejectedException.Reason.ADMISSION_TIMEOUT,
                            "Timed out waiting for async queue capacity for "
                                    + invocation.serviceId() + "::" + invocation.methodId(),
                            rejection
                    );
                }
                if (!queueStore.awaitProjectionChange(observedVersion, remainingMillis)
                        && Thread.currentThread().isInterrupted()) {
                    throw new AsyncAdmissionRejectedException(
                            AsyncAdmissionRejectedException.Reason.ADMISSION_TIMEOUT,
                            "Interrupted while waiting for async queue capacity",
                            rejection
                    );
                }
            }
        }
    }

    /**
     * Rejects malformed caller input before it reaches the shared writer. Input errors are the
     * caller's problem and must never affect other admissions.
     */
    private void validateAdmissionInput(MethodRuntime runtime, AsyncInvocationContext context) {
        requireLanePart(runtime.serviceId(), "serviceId");
        requireLanePart(runtime.methodId(), "methodId");
        String idempotencyKey = context.idempotencyKey();
        if (idempotencyKey == null) {
            return;
        }
        if (idempotencyKey.length() > options.maxIdempotencyKeyCharacters()) {
            throw new IllegalArgumentException(
                    "idempotencyKey exceeds " + options.maxIdempotencyKeyCharacters() + " characters"
            );
        }
        if (idempotencyKey.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("idempotencyKey must not contain NUL");
        }
    }

    private static void requireLanePart(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(name + " must not contain NUL");
        }
    }

    private long admissionDeadlineNanos() {
        return options.overflowPolicy() == QueueOverflowPolicy.BLOCK_WITH_TIMEOUT
                ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(options.enqueueTimeoutMillis())
                : 0L;
    }

    private void acquireEnqueuePermit(long deadlineNanos) {
        boolean acquired;
        try {
            acquired = options.overflowPolicy() == QueueOverflowPolicy.REJECT
                    ? enqueuePermits.tryAcquire()
                    : enqueuePermits.tryAcquire(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AsyncAdmissionRejectedException(
                    AsyncAdmissionRejectedException.Reason.ADMISSION_TIMEOUT,
                    "Interrupted while waiting for async enqueue capacity",
                    exception
            );
        }
        if (!acquired) {
            AsyncAdmissionRejectedException.Reason reason =
                    options.overflowPolicy() == QueueOverflowPolicy.REJECT
                            ? AsyncAdmissionRejectedException.Reason.WRITER_SATURATED
                            : AsyncAdmissionRejectedException.Reason.ADMISSION_TIMEOUT;
            throw new AsyncAdmissionRejectedException(
                    reason,
                    "Concurrent async enqueue limit reached: " + options.maxConcurrentEnqueues()
            );
        }
    }

    private void coordinate() {
        int consecutiveFailures = 0;
        while (!closed.get()) {
            try {
                long now = System.currentTimeMillis();
                lastCoordinatorHeartbeat.set(now);
                if (storeUnavailable()) {
                    // The writer or store stopped after a fatal storage failure. Scheduling would only
                    // produce failing claims; idle at the failure backoff until the engine is closed.
                    awaitSchedulerSignal(MAX_FAILURE_BACKOFF_MILLIS);
                    continue;
                }
                renewActiveLeases(now);
                enforceExecutionTimeouts();
                activateDueRetries(now);
                schedulePendingLanes();
                consecutiveFailures = 0;
                awaitSchedulerSignal(coordinatorWaitMillis(now));
            } catch (InterruptedException interrupted) {
                if (closed.get()) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } catch (Throwable failure) {
                recordWorkerFailure(failure);
                if (failure instanceof VirtualMachineError virtualMachineError) {
                    throw virtualMachineError;
                }
                consecutiveFailures = Math.min(consecutiveFailures + 1, 16);
                try {
                    // Never hot-spin on a persistent failure.
                    Thread.sleep(failureBackoffMillis(consecutiveFailures));
                } catch (InterruptedException interrupted) {
                    if (closed.get()) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private void awaitSchedulerSignal(long waitMillis) throws InterruptedException {
        schedulerSignal.tryAcquire(waitMillis, TimeUnit.MILLISECONDS);
        schedulerSignal.drainPermits();
    }

    private long failureBackoffMillis(int consecutiveFailures) {
        long base = Math.max(1L, options.emptyQueueSleepMillis());
        long backoff = base << Math.min(consecutiveFailures - 1, 10);
        return Math.min(MAX_FAILURE_BACKOFF_MILLIS, Math.max(base, backoff));
    }

    private boolean storeUnavailable() {
        return !writer.isAccepting() || queueStore.fatalFailure().isPresent();
    }

    private void activateDueRetries(long nowEpochMillis) {
        if (queueStore.nextRetryAtEpochMillis() <= nowEpochMillis) {
            internalWrite(() -> queueStore.activateDueRetries(nowEpochMillis, options.commitBatchSize()));
        }
    }

    private void renewActiveLeases(long nowEpochMillis) {
        long lastRenewal = lastLeaseRenewal.get();
        if (nowEpochMillis - lastRenewal < options.leaseRenewalIntervalMillis()
                || !lastLeaseRenewal.compareAndSet(lastRenewal, nowEpochMillis)) {
            return;
        }
        long leaseUntil = nowEpochMillis + options.leaseDurationMillis();
        Map<Long, Long> owned = new java.util.HashMap<>();
        for (ActiveLease lease : activeLeases.values()) {
            owned.put(lease.invocationId(), lease.leaseToken());
        }
        if (!owned.isEmpty()) {
            // One durable write for all leases instead of one commit per lease.
            java.util.Set<Long> lost = internalWrite(() -> queueStore.renewLeases(owned, leaseUntil));
            for (Long invocationId : lost) {
                activeLeases.remove(invocationId);
            }
        }
        // Orphaned claims (for example a claim whose caller timed out waiting for the writer) are not
        // owned by any live worker and are never renewed; return them once their lease expired.
        if (queueStore.inProgressCount() <= activeLeases.size()) {
            return;
        }
        java.util.Set<Long> live = java.util.Set.copyOf(activeLeases.keySet());
        int reclaimed = internalWrite(
                () -> queueStore.reclaimExpiredLeases(nowEpochMillis, live, options.commitBatchSize())
        );
        if (reclaimed > 0) {
            reclaimedLeases.add(reclaimed);
            signalScheduler();
        }
    }

    private void enforceExecutionTimeouts() {
        long timeoutMillis = options.executionTimeoutMillis();
        if (timeoutMillis <= 0L || activeLeases.isEmpty()) {
            return;
        }
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        long nowNanos = System.nanoTime();
        for (ActiveLease lease : activeLeases.values()) {
            if (nowNanos - lease.startedNanos >= timeoutNanos && lease.timedOut.compareAndSet(false, true)) {
                executionTimeouts.increment();
                lease.worker.interrupt();
            }
        }
    }

    private void schedulePendingLanes() {
        long nowNanos = System.nanoTime();
        for (Map.Entry<String, MethodRegistration> entry : registrations.entrySet()) {
            MethodRegistration registration = entry.getValue();
            MethodRuntime runtime = registration.runtime;
            if (!consumesBacklog(runtime) || runtime.consumerThreads() <= 0) {
                continue;
            }
            LaneControl control = laneControls.computeIfAbsent(entry.getKey(), ignored -> new LaneControl());
            if (control.nextScheduleNanos.get() > nowNanos) {
                continue;
            }
            int desired = Math.min(
                    options.maxWorkerThreads(),
                    Math.min(runtime.consumerThreads(), runtime.policy().maxConcurrency())
            );
            // Stop as soon as a worker pushed the lane back (rate limit, concurrency). A rate-limited lane
            // also gets at most `desired` dispatches per pass: a worker that finds no permit returns at
            // once and frees its slot, which would otherwise make this loop dispatch again and again.
            int passLimit = runtime.policy().rateLimited() ? desired : Integer.MAX_VALUE;
            int dispatched = 0;
            while (dispatched < passLimit
                    && control.active.get() < desired
                    && control.nextScheduleNanos.get() <= System.nanoTime()
                    && queueStore.hasPending(runtime.serviceId(), runtime.methodId())) {
                int current = control.active.get();
                if (current >= desired || !control.active.compareAndSet(current, current + 1)) {
                    continue;
                }
                try {
                    workers.execute(() -> processOne(entry.getKey(), registration, control));
                    dispatched++;
                } catch (RejectedExecutionException rejected) {
                    control.active.decrementAndGet();
                    rejectedSchedules.increment();
                    return;
                }
            }
        }
    }

    private void processOne(String laneKey, MethodRegistration registration, LaneControl control) {
        MethodRuntime runtime = registration.runtime;
        boolean runtimeAcquired = false;
        QueueStore.ClaimedInvocation claimed = null;
        ActiveLease lease = null;
        try {
            if (!consumesBacklog(runtime) || closed.get()) {
                return;
            }
            if (!runtime.tryBeginAsyncExecution()) {
                control.nextScheduleNanos.set(
                        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(options.requeueDelayMillis())
                );
                return;
            }
            runtimeAcquired = true;
            // Rate limit in ASYNC mode: the queue absorbs the calls and consumers start at most N per second.
            long rateWaitNanos = runtime.reserveAsyncRatePermitNanos();
            if (rateWaitNanos > 0L) {
                rateLimitedSchedules.increment();
                // Keep the later deadline: a worker that just got a permit must not clear this wait,
                // or the coordinator would keep dispatching workers that only find no permit.
                long due = System.nanoTime() + rateWaitNanos;
                control.nextScheduleNanos.accumulateAndGet(due, Math::max);
                return;
            }
            boolean ratePermitTaken = runtime.policy().rateLimited();
            if (!ratePermitTaken) {
                control.nextScheduleNanos.set(0L);
            }
            claimed = internalWrite(
                    () -> queueStore.claimNext(runtime.serviceId(), runtime.methodId(), System.currentTimeMillis())
            ).orElse(null);
            if (claimed == null) {
                if (ratePermitTaken) {
                    // Nothing to run (another consumer took the last entry): the permit was not used.
                    runtime.returnAsyncRatePermit();
                }
                return;
            }
            lease = new ActiveLease(claimed.invocationId(), claimed.leaseToken(), Thread.currentThread(), System.nanoTime());
            activeLeases.put(claimed.invocationId(), lease);

            Throwable businessFailure = null;
            try {
                registration.binding.invoke(decodeArguments(claimed, registration.binding));
            } catch (Throwable failure) {
                businessFailure = failure;
            }
            // An interrupt from the timeout watchdog or from shutdown must not leak into the durable
            // writes below (they would fail as "interrupted while awaiting commit").
            Thread.interrupted();
            if (businessFailure != null) {
                throw businessFailure;
            }
            QueueStore.ClaimedInvocation completedInvocation = claimed;
            boolean completed = internalWrite(
                    () -> queueStore.complete(
                            completedInvocation.invocationId(),
                            completedInvocation.leaseToken()
                    )
            );
            if (!completed) {
                throw new IllegalStateException("Lost async invocation lease while completing " + claimed.invocationId());
            }
            runtime.onSuccess();
        } catch (Throwable failure) {
            boolean timedOut = lease != null && lease.timedOut.get();
            if (claimed != null && closed.get() && !timedOut) {
                // The engine is stopping and interrupted this execution: give the attempt back so the
                // invocation runs again after restart (at-least-once).
                releaseOnShutdown(claimed);
            } else if (claimed != null) {
                runtime.onError(failure);
                recordFailure(claimed, failure, timedOut);
            } else if (!(failure instanceof InterruptedException)) {
                recordWorkerFailure(failure);
                // Back off this lane so a failing claim path cannot turn into a scheduling spin.
                control.nextScheduleNanos.set(
                        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(options.requeueDelayMillis())
                );
            }
            if (failure instanceof VirtualMachineError virtualMachineError) {
                throw virtualMachineError;
            }
        } finally {
            if (claimed != null) {
                activeLeases.remove(claimed.invocationId());
            }
            if (runtimeAcquired) {
                runtime.release();
            }
            control.active.decrementAndGet();
            signalScheduler();
        }
    }

    /**
     * ASYNC lanes always consume. After a switch back to SYNC, new calls run synchronously but the
     * already accepted backlog keeps draining unless {@code drainBacklogInSyncMode} is disabled.
     */
    private boolean consumesBacklog(MethodRuntime runtime) {
        return runtime.executionMode() == ExecutionMode.ASYNC
                || (options.drainBacklogInSyncMode() && queueStore.hasPending(runtime.serviceId(), runtime.methodId()));
    }

    private void releaseOnShutdown(QueueStore.ClaimedInvocation claimed) {
        Thread.interrupted();
        try {
            boolean released = internalWrite(() -> queueStore.release(
                    claimed.invocationId(),
                    claimed.leaseToken(),
                    "Execution interrupted by engine shutdown"
            ));
            if (released) {
                releasedOnShutdown.increment();
            }
        } catch (RuntimeException storageFailure) {
            // The record stays IN_PROGRESS and is recovered on the next start.
            recordWorkerFailure(storageFailure);
        }
    }

    private void recordFailure(QueueStore.ClaimedInvocation claimed, Throwable failure, boolean timedOut) {
        String error = timedOut
                ? truncate("Execution exceeded " + options.executionTimeoutMillis() + " ms and was interrupted",
                        options.maxErrorCharacters())
                : boundedErrorDetail(failure);
        String errorType = truncate(failure.getClass().getName(), options.maxErrorCharacters());
        String category = timedOut ? ERROR_CATEGORY_TIMEOUT : classifyErrorCategory(failure);
        long retryAt = System.currentTimeMillis() + retryDelayMillis(claimed.attempt());
        try {
            internalWrite(() -> queueStore.fail(
                    claimed.invocationId(),
                    claimed.leaseToken(),
                    error,
                    errorType,
                    category,
                    retryAt
            ));
        } catch (RuntimeException storageFailure) {
            failure.addSuppressed(storageFailure);
            recordWorkerFailure(storageFailure);
        }
    }

    private Object[] decodeArguments(QueueStore.ClaimedInvocation invocation, AsyncMethodBinding binding) {
        if (!payloadCodec.id().equals(invocation.codecId())) {
            throw new AsyncPayloadCodecException(
                    "Stored async payload codec mismatch for "
                            + invocation.serviceId() + "::" + invocation.methodId()
                            + ": expected " + payloadCodec.id() + " but found " + invocation.codecId()
            );
        }
        return payloadCodec.decode(
                invocation.serviceId(),
                invocation.methodId(),
                invocation.payload(),
                binding.parameterTypes()
        );
    }

    private String payloadSummary(
            MethodRuntime runtime,
            Object[] arguments,
            AsyncPayloadMetadata metadata
    ) {
        return switch (options.payloadSummaryPolicy()) {
            case NONE -> null;
            case TYPE_ONLY -> truncate(
                    metadata.payloadType() == null
                            ? (arguments.length == 0 ? "(no arguments)" : "(type unavailable)")
                            : metadata.payloadType(),
                    options.maxSummaryCharacters()
            );
            case CODEC -> truncate(
                    payloadCodec.summarize(runtime.serviceId(), runtime.methodId(), arguments),
                    options.maxSummaryCharacters()
            );
        };
    }

    private long retryDelayMillis(int attempt) {
        int exponent = Math.max(0, Math.min(62, attempt - 1));
        long multiplier = 1L << Math.min(exponent, 30);
        long base;
        try {
            base = Math.multiplyExact(options.retryInitialBackoffMillis(), multiplier);
        } catch (ArithmeticException overflow) {
            base = options.retryMaxBackoffMillis();
        }
        base = Math.min(base, options.retryMaxBackoffMillis());
        double jitter = options.retryJitterRatio();
        if (jitter == 0.0d) {
            return base;
        }
        double factor = ThreadLocalRandom.current().nextDouble(1.0d - jitter, 1.0d + jitter);
        return Math.max(1L, Math.min(options.retryMaxBackoffMillis(), Math.round(base * factor)));
    }

    private long coordinatorWaitMillis(long nowEpochMillis) {
        long idleWait = registrations.isEmpty()
                ? options.registrationIdleSleepMillis()
                : options.emptyQueueSleepMillis();
        long retryAt = queueStore.nextRetryAtEpochMillis();
        long retryWait = retryAt == Long.MAX_VALUE
                ? idleWait
                : Math.max(1L, retryAt - nowEpochMillis);
        long wait = Math.min(idleWait, retryWait);
        // A lane held back by its rate limit (or a requeue delay) is due again at nextScheduleNanos:
        // wake up then instead of after the idle interval, or the rate would be far below the limit.
        long nowNanos = System.nanoTime();
        for (LaneControl control : laneControls.values()) {
            long due = control.nextScheduleNanos.get();
            if (due != 0L && due - nowNanos > 0L) {
                wait = Math.min(wait, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(due - nowNanos) + 1L));
            }
        }
        if (options.executionTimeoutMillis() > 0L && !activeLeases.isEmpty()) {
            wait = Math.min(wait, Math.max(10L, options.executionTimeoutMillis() / 4L));
        }
        return Math.max(1L, wait);
    }

    private <T> T internalWrite(BoundedStoreWriter.Mutation<T> mutation) {
        return writer.execute(mutation, options.writerOperationTimeoutMillis());
    }

    private RuntimeException stopCoordinator(RuntimeException failure) {
        try {
            coordinatorThread.join(options.workerShutdownAwaitMillis());
            if (coordinatorThread.isAlive()) {
                failure = appendFailure(failure, new IllegalStateException(
                        "MapDB coordinator did not stop within " + options.workerShutdownAwaitMillis() + " ms"
                ));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failure = appendFailure(failure, new IllegalStateException(
                    "Interrupted while stopping MapDB coordinator",
                    interrupted
            ));
        }
        return failure;
    }

    private RuntimeException stopWorkers(RuntimeException failure) {
        workers.shutdown();
        try {
            if (!workers.awaitTermination(options.workerShutdownAwaitMillis(), TimeUnit.MILLISECONDS)) {
                failure = appendFailure(failure, new IllegalStateException(
                        "Timed out while waiting for worker shutdown; interrupting remaining work"
                ));
                workers.shutdownNow();
                if (!workers.awaitTermination(options.workerShutdownAwaitMillis(), TimeUnit.MILLISECONDS)) {
                    failure = appendFailure(failure, new IllegalStateException(
                            "MapDB workers did not stop after interrupt; in-progress leases will recover on restart"
                    ));
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
            failure = appendFailure(failure, new IllegalStateException(
                    "Interrupted while stopping MapDB workers",
                    interrupted
            ));
        }
        return failure;
    }

    private void signalScheduler() {
        if (schedulerSignal.availablePermits() == 0) {
            schedulerSignal.release();
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new AsyncAdmissionRejectedException(
                    AsyncAdmissionRejectedException.Reason.ENGINE_CLOSED,
                    "Async execution engine is closed"
            );
        }
    }

    private void recordWorkerFailure(Throwable failure) {
        workerFailures.increment();
        lastWorkerFailure.set(boundedErrorDetail(failure));
    }

    private String boundedErrorDetail(Throwable failure) {
        int limit = options.maxErrorCharacters();
        String type = failure.getClass().getName();
        if (options.errorDetailPolicy() == ErrorDetailPolicy.TYPE_ONLY) {
            return truncate(type, limit);
        }

        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return truncate(type, limit);
        }
        if (type.length() >= limit) {
            return type.substring(0, limit);
        }

        int remaining = limit - type.length();
        if (remaining <= 2) {
            return type + ": ".substring(0, remaining);
        }
        int messageLimit = remaining - 2;
        return type + ": " + (message.length() <= messageLimit
                ? message
                : message.substring(0, messageLimit));
    }

    private static ThreadPoolExecutor createWorkerPool(MapDbAsyncExecutionOptions options) {
        AtomicInteger sequence = new AtomicInteger();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                options.maxWorkerThreads(),
                options.maxWorkerThreads(),
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(options.workerQueueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable, "asl-mapdb-worker-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static boolean isCapacityRejection(AsyncAdmissionRejectedException rejection) {
        return rejection.reason() == AsyncAdmissionRejectedException.Reason.GLOBAL_CAPACITY
                || rejection.reason() == AsyncAdmissionRejectedException.Reason.LANE_CAPACITY;
    }

    private static long remainingMillis(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            return 0L;
        }
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
    }

    private static long parseId(String entryId) {
        try {
            long id = Long.parseLong(entryId);
            if (id <= 0L) {
                throw new NumberFormatException("non-positive id");
            }
            return id;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid entryId: " + entryId, exception);
        }
    }

    private static String classifyErrorCategory(Throwable throwable) {
        if (throwable instanceof AsyncPayloadCodecException) {
            String message = throwable.getMessage();
            if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains("migration")) {
                return ERROR_CATEGORY_MIGRATION;
            }
            return ERROR_CATEGORY_DECODE;
        }
        return ERROR_CATEGORY_BUSINESS;
    }

    private static String key(String serviceId, String methodId) {
        return MapDbAsyncExecutionOptions.laneKey(serviceId, methodId);
    }

    private static String truncate(String value, int maxCharacters) {
        if (value == null || value.length() <= maxCharacters) {
            return value;
        }
        return value.substring(0, maxCharacters);
    }

    private static MapDbCorruptionPolicy rejectChecksumHeaderBypass(boolean checksumHeaderBypass) {
        if (checksumHeaderBypass) {
            throw new IllegalArgumentException(
                    "checksumHeaderBypass is unsafe and no longer supported; use MapDbCorruptionPolicy"
            );
        }
        return MapDbCorruptionPolicy.FAIL_FAST;
    }

    private static RuntimeException appendFailure(RuntimeException current, RuntimeException next) {
        if (current == null) {
            return next;
        }
        current.addSuppressed(next);
        return current;
    }

    private static void closeDbAfterFailure(DB db, Throwable failure) {
        if (db == null || db.isClosed()) {
            return;
        }
        try {
            db.rollback();
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
        try {
            db.close();
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private record MethodRegistration(MethodRuntime runtime, AsyncMethodBinding binding) {
    }

    private static final class ActiveLease {
        private final long invocationId;
        private final long leaseToken;
        private final Thread worker;
        private final long startedNanos;
        private final AtomicBoolean timedOut = new AtomicBoolean();

        private ActiveLease(long invocationId, long leaseToken, Thread worker, long startedNanos) {
            this.invocationId = invocationId;
            this.leaseToken = leaseToken;
            this.worker = worker;
            this.startedNanos = startedNanos;
        }

        long invocationId() {
            return invocationId;
        }

        long leaseToken() {
            return leaseToken;
        }
    }

    private static final class LaneControl {
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicLong nextScheduleNanos = new AtomicLong();
    }
}
