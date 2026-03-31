package com.reactor.asl.mapdb;

import com.reactor.asl.core.AsyncExecutionEngine;
import com.reactor.asl.core.AsyncMethodBinding;
import com.reactor.asl.core.AsyncPayloadCodec;
import com.reactor.asl.core.AsyncPayloadCodecException;
import com.reactor.asl.core.AsyncPayloadMetadata;
import com.reactor.asl.core.JavaObjectStreamAsyncPayloadCodec;
import com.reactor.asl.core.MethodBufferEntryView;
import com.reactor.asl.core.MethodBufferSnapshot;
import com.reactor.asl.core.MethodRuntime;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.HTreeMap;
import org.mapdb.Serializer;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;

public final class MapDbAsyncExecutionEngine implements AsyncExecutionEngine, AutoCloseable {
    static final String RECOVERED_IN_PROGRESS_MESSAGE = "Recovered stale in-progress invocation after restart";
    private static final long DEFAULT_WORKER_SHUTDOWN_AWAIT_MILLIS = 10_000L;
    private static final long DEFAULT_REGISTRATION_IDLE_SLEEP_MILLIS = 100L;
    private static final long DEFAULT_EMPTY_QUEUE_SLEEP_MILLIS = 50L;
    private static final long DEFAULT_REQUEUE_DELAY_MILLIS = 75L;
    private static final boolean DEFAULT_TRANSACTIONS_ENABLED = true;
    private static final boolean DEFAULT_MEMORY_MAPPED_ENABLED = false;
    private static final String ERROR_CATEGORY_RECOVERY = "RECOVERY";
    private static final String ERROR_CATEGORY_DECODE = "DECODE";
    private static final String ERROR_CATEGORY_MIGRATION = "MIGRATION";
    private static final String ERROR_CATEGORY_BUSINESS = "BUSINESS";

    private final DB db;
    private final HTreeMap<Long, StoredAsyncInvocation> invocations;
    private final AtomicLong nextId;
    private final Map<String, MethodRegistration> registrations = new ConcurrentHashMap<>();
    private final Map<String, LinkedBlockingDeque<Long>> pendingQueues = new ConcurrentHashMap<>();
    private final Map<String, WorkerGroup> workerGroups = new ConcurrentHashMap<>();
    private final Object writeMonitor = new Object();
    private final AsyncPayloadCodec payloadCodec;
    private final long workerShutdownAwaitMillis;
    private final long registrationIdleSleepMillis;
    private final long emptyQueueSleepMillis;
    private final long requeueDelayMillis;
    private final String recoveredInProgressMessage;

    public MapDbAsyncExecutionEngine(Path databasePath) {
        this(
                databasePath,
                new JavaObjectStreamAsyncPayloadCodec(),
                DEFAULT_WORKER_SHUTDOWN_AWAIT_MILLIS,
                DEFAULT_REGISTRATION_IDLE_SLEEP_MILLIS,
                DEFAULT_EMPTY_QUEUE_SLEEP_MILLIS,
                DEFAULT_REQUEUE_DELAY_MILLIS,
                RECOVERED_IN_PROGRESS_MESSAGE,
                DEFAULT_TRANSACTIONS_ENABLED,
                DEFAULT_MEMORY_MAPPED_ENABLED,
                false
        );
    }

    public MapDbAsyncExecutionEngine(Path databasePath, AsyncPayloadCodec payloadCodec) {
        this(
                databasePath,
                payloadCodec,
                DEFAULT_WORKER_SHUTDOWN_AWAIT_MILLIS,
                DEFAULT_REGISTRATION_IDLE_SLEEP_MILLIS,
                DEFAULT_EMPTY_QUEUE_SLEEP_MILLIS,
                DEFAULT_REQUEUE_DELAY_MILLIS,
                RECOVERED_IN_PROGRESS_MESSAGE,
                DEFAULT_TRANSACTIONS_ENABLED,
                DEFAULT_MEMORY_MAPPED_ENABLED,
                false
        );
    }

    public MapDbAsyncExecutionEngine(Path databasePath, AsyncPayloadCodec payloadCodec, long workerShutdownAwaitMillis) {
        this(
                databasePath,
                payloadCodec,
                workerShutdownAwaitMillis,
                DEFAULT_REGISTRATION_IDLE_SLEEP_MILLIS,
                DEFAULT_EMPTY_QUEUE_SLEEP_MILLIS,
                DEFAULT_REQUEUE_DELAY_MILLIS,
                RECOVERED_IN_PROGRESS_MESSAGE,
                DEFAULT_TRANSACTIONS_ENABLED,
                DEFAULT_MEMORY_MAPPED_ENABLED,
                false
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
                workerShutdownAwaitMillis,
                DEFAULT_REGISTRATION_IDLE_SLEEP_MILLIS,
                DEFAULT_EMPTY_QUEUE_SLEEP_MILLIS,
                DEFAULT_REQUEUE_DELAY_MILLIS,
                RECOVERED_IN_PROGRESS_MESSAGE,
                transactionsEnabled,
                memoryMappedEnabled,
                false
        );
    }

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
                workerShutdownAwaitMillis,
                DEFAULT_REGISTRATION_IDLE_SLEEP_MILLIS,
                DEFAULT_EMPTY_QUEUE_SLEEP_MILLIS,
                DEFAULT_REQUEUE_DELAY_MILLIS,
                RECOVERED_IN_PROGRESS_MESSAGE,
                transactionsEnabled,
                memoryMappedEnabled,
                checksumHeaderBypass
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
            boolean checksumHeaderBypass
    ) {
        Objects.requireNonNull(databasePath, "databasePath");
        this.payloadCodec = Objects.requireNonNull(payloadCodec, "payloadCodec");
        if (workerShutdownAwaitMillis <= 0) {
            throw new IllegalArgumentException("workerShutdownAwaitMillis must be positive");
        }
        if (registrationIdleSleepMillis <= 0) {
            throw new IllegalArgumentException("registrationIdleSleepMillis must be positive");
        }
        if (emptyQueueSleepMillis <= 0) {
            throw new IllegalArgumentException("emptyQueueSleepMillis must be positive");
        }
        if (requeueDelayMillis <= 0) {
            throw new IllegalArgumentException("requeueDelayMillis must be positive");
        }
        this.workerShutdownAwaitMillis = workerShutdownAwaitMillis;
        this.registrationIdleSleepMillis = registrationIdleSleepMillis;
        this.emptyQueueSleepMillis = emptyQueueSleepMillis;
        this.requeueDelayMillis = requeueDelayMillis;
        this.recoveredInProgressMessage = Objects.requireNonNull(recoveredInProgressMessage, "recoveredInProgressMessage");
        try {
            Path parent = databasePath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to prepare MapDB path: " + databasePath, e);
        }
        DBMaker.Maker maker = DBMaker
                .fileDB(databasePath.toFile())
                .closeOnJvmShutdown();
        if (checksumHeaderBypass) {
            maker = maker.checksumHeaderBypass();
        }
        if (transactionsEnabled) {
            maker = maker.transactionEnable();
        }
        if (memoryMappedEnabled) {
            maker = maker.fileMmapEnableIfSupported();
        }
        this.db = maker.make();
        this.invocations = db
                .hashMap("aslAsyncInvocations", Serializer.LONG, Serializer.JAVA)
                .createOrOpen();
        this.nextId = new AtomicLong(highestInvocationId());
        recoverStaleInProgressInvocations();
        rebuildPendingQueues();
    }

    @Override
    public void register(MethodRuntime runtime, AsyncMethodBinding binding) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(binding, "binding");
        if (!runtime.asyncCapable()) {
            return;
        }
        String key = key(runtime.serviceId(), runtime.methodId());
        registrations.put(key, new MethodRegistration(runtime, binding));
        pendingQueues.computeIfAbsent(key, ignored -> new LinkedBlockingDeque<>());
        workerGroups.computeIfAbsent(key, ignored -> new WorkerGroup(key)).resize(runtime.consumerThreads());
    }

    @Override
    public void enqueue(MethodRuntime runtime, Object[] arguments) {
        Objects.requireNonNull(runtime, "runtime");
        if (!runtime.asyncCapable()) {
            throw new IllegalStateException("Method is not async-capable: " + runtime.methodId());
        }

        Object[] safeArguments = arguments == null ? new Object[0] : arguments;
        long id = nextId.incrementAndGet();
        byte[] encodedPayload = payloadCodec.encode(runtime.serviceId(), runtime.methodId(), safeArguments);
        AsyncPayloadMetadata payloadMetadata = payloadCodec.describe(
                runtime.serviceId(),
                runtime.methodId(),
                safeArguments,
                encodedPayload
        );
        StoredAsyncInvocation invocation = new StoredAsyncInvocation(
                id,
                runtime.serviceId(),
                runtime.methodId(),
                payloadCodec.id(),
                encodedPayload,
                payloadCodec.summarize(runtime.serviceId(), runtime.methodId(), safeArguments),
                payloadMetadata.payloadType(),
                payloadMetadata.payloadVersion(),
                AsyncInvocationState.PENDING,
                System.currentTimeMillis(),
                0,
                null,
                null,
                null
        );

        synchronized (writeMonitor) {
            invocations.put(id, invocation);
            db.commit();
        }
        pendingQueues.computeIfAbsent(key(runtime.serviceId(), runtime.methodId()), ignored -> new LinkedBlockingDeque<>()).offer(id);
    }

    @Override
    public boolean supports(String serviceId, String methodId) {
        return registrations.containsKey(key(serviceId, methodId));
    }

    @Override
    public MethodBufferSnapshot snapshot(String serviceId, String methodId, int limit) {
        List<StoredAsyncInvocation> matching = new ArrayList<>();
        long pendingCount = 0;
        long failedCount = 0;
        long inProgressCount = 0;

        for (StoredAsyncInvocation invocation : invocations.values()) {
            if (!invocation.serviceId().equals(serviceId) || !invocation.methodId().equals(methodId)) {
                continue;
            }
            matching.add(invocation);
            switch (invocation.state()) {
                case PENDING -> pendingCount++;
                case FAILED -> failedCount++;
                case IN_PROGRESS -> inProgressCount++;
            }
        }

        matching.sort(Comparator.comparingLong(StoredAsyncInvocation::id));

        List<MethodBufferEntryView> entries = matching.stream()
                .limit(limit)
                .map(this::toEntryView)
                .toList();

        return new MethodBufferSnapshot(serviceId, methodId, true, pendingCount, failedCount, inProgressCount, entries);
    }

    @Override
    public int clear(String serviceId, String methodId) {
        List<Long> toRemove = new ArrayList<>();
        for (Map.Entry<Long, StoredAsyncInvocation> entry : invocations.entrySet()) {
            StoredAsyncInvocation invocation = entry.getValue();
            if (invocation.serviceId().equals(serviceId)
                    && invocation.methodId().equals(methodId)
                    && invocation.state() != AsyncInvocationState.IN_PROGRESS) {
                toRemove.add(entry.getKey());
            }
        }
        synchronized (writeMonitor) {
            for (Long id : toRemove) {
                invocations.remove(id);
                pendingQueues.computeIfAbsent(key(serviceId, methodId), ignored -> new LinkedBlockingDeque<>()).remove(id);
            }
            db.commit();
        }
        return toRemove.size();
    }

    @Override
    public boolean delete(String serviceId, String methodId, String entryId) {
        long id = parseId(entryId);
        synchronized (writeMonitor) {
            StoredAsyncInvocation current = invocations.get(id);
            if (current == null || !matches(current, serviceId, methodId) || current.state() == AsyncInvocationState.IN_PROGRESS) {
                return false;
            }
            invocations.remove(id);
            pendingQueues.computeIfAbsent(key(serviceId, methodId), ignored -> new LinkedBlockingDeque<>()).remove(id);
            db.commit();
            return true;
        }
    }

    @Override
    public boolean replay(String serviceId, String methodId, String entryId) {
        long id = parseId(entryId);
        synchronized (writeMonitor) {
            StoredAsyncInvocation current = invocations.get(id);
            if (current == null || !matches(current, serviceId, methodId) || current.state() != AsyncInvocationState.FAILED) {
                return false;
            }
            invocations.put(id, current.replay());
            db.commit();
        }
        pendingQueues.computeIfAbsent(key(serviceId, methodId), ignored -> new LinkedBlockingDeque<>()).offer(id);
        return true;
    }

    @Override
    public void applyRuntime(MethodRuntime runtime) {
        if (!runtime.asyncCapable()) {
            return;
        }
        workerGroups.computeIfAbsent(key(runtime.serviceId(), runtime.methodId()), ignored -> new WorkerGroup(key(runtime.serviceId(), runtime.methodId())))
                .resize(runtime.consumerThreads());
    }

    @Override
    public void close() {
        workerGroups.values().forEach(WorkerGroup::shutdown);
        db.close();
    }

    private long highestInvocationId() {
        long highest = 0;
        for (Long id : invocations.keySet()) {
            if (id > highest) {
                highest = id;
            }
        }
        return highest;
    }

    private void rebuildPendingQueues() {
        for (StoredAsyncInvocation invocation : invocations.values()) {
            if (invocation.state() == AsyncInvocationState.PENDING) {
                pendingQueues.computeIfAbsent(key(invocation.serviceId(), invocation.methodId()), ignored -> new LinkedBlockingDeque<>())
                        .offer(invocation.id());
            }
        }
    }

    private void recoverStaleInProgressInvocations() {
        boolean changed = false;
        synchronized (writeMonitor) {
            for (Map.Entry<Long, StoredAsyncInvocation> entry : invocations.entrySet()) {
                StoredAsyncInvocation invocation = entry.getValue();
                if (invocation.state() == AsyncInvocationState.IN_PROGRESS) {
                    invocations.put(
                            entry.getKey(),
                            invocation.failed(
                                    recoveredInProgressMessage,
                                    IllegalStateException.class.getName(),
                                    ERROR_CATEGORY_RECOVERY
                            )
                    );
                    changed = true;
                }
            }
            if (changed) {
                db.commit();
            }
        }
    }

    private void process(String methodKey) {
        LinkedBlockingDeque<Long> queue = pendingQueues.computeIfAbsent(methodKey, ignored -> new LinkedBlockingDeque<>());
        MethodRegistration registration = registrations.get(methodKey);
        if (registration == null) {
            sleepQuietly(registrationIdleSleepMillis);
            return;
        }

        Long invocationId = null;
        try {
            invocationId = queue.pollFirst();
        } catch (Exception ignored) {
        }
        if (invocationId == null) {
            sleepQuietly(emptyQueueSleepMillis);
            return;
        }

        StoredAsyncInvocation current = invocations.get(invocationId);
        if (current == null || current.state() != AsyncInvocationState.PENDING) {
            return;
        }

        MethodRuntime runtime = registration.runtime();
        if (!runtime.tryBeginAsyncExecution()) {
            queue.offerLast(invocationId);
            sleepQuietly(requeueDelayMillis);
            return;
        }

        synchronized (writeMonitor) {
            StoredAsyncInvocation latest = invocations.get(invocationId);
            if (latest == null || latest.state() != AsyncInvocationState.PENDING) {
                runtime.release();
                return;
            }
            invocations.put(invocationId, latest.withState(AsyncInvocationState.IN_PROGRESS));
            db.commit();
        }

        try {
            registration.binding().invoke(decodeArguments(current));
            runtime.onSuccess();
            synchronized (writeMonitor) {
                invocations.remove(invocationId);
                db.commit();
            }
        } catch (Throwable throwable) {
            runtime.onError(throwable);
            synchronized (writeMonitor) {
                StoredAsyncInvocation latest = invocations.get(invocationId);
                if (latest != null) {
                    invocations.put(
                            invocationId,
                            latest.failed(
                                    throwable.toString(),
                                    throwable.getClass().getName(),
                                    classifyErrorCategory(throwable)
                            )
                    );
                    db.commit();
                }
            }
        } finally {
            runtime.release();
        }
    }

    private static boolean matches(StoredAsyncInvocation invocation, String serviceId, String methodId) {
        return invocation.serviceId().equals(serviceId) && invocation.methodId().equals(methodId);
    }

    private static long parseId(String entryId) {
        try {
            return Long.parseLong(entryId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid entryId: " + entryId, e);
        }
    }

    private Object[] decodeArguments(StoredAsyncInvocation invocation) {
        if (!payloadCodec.id().equals(invocation.codecId())) {
            throw new AsyncPayloadCodecException(
                    "Stored async payload codec mismatch for "
                            + invocation.serviceId()
                            + "::"
                            + invocation.methodId()
                            + ": expected "
                            + payloadCodec.id()
                            + " but found "
                            + invocation.codecId()
            );
        }
        return payloadCodec.decode(invocation.serviceId(), invocation.methodId(), invocation.payload());
    }

    private MethodBufferEntryView toEntryView(StoredAsyncInvocation invocation) {
        AsyncPayloadMetadata payloadMetadata = resolvePayloadMetadata(invocation);
        return new MethodBufferEntryView(
                Long.toString(invocation.id()),
                invocation.state().name(),
                invocation.payloadSummary(),
                Instant.ofEpochMilli(invocation.enqueuedAtEpochMillis()),
                invocation.attempts(),
                invocation.lastError(),
                invocation.lastErrorType(),
                invocation.errorCategory(),
                invocation.codecId(),
                payloadMetadata.payloadType(),
                payloadMetadata.payloadVersion()
        );
    }

    private AsyncPayloadMetadata resolvePayloadMetadata(StoredAsyncInvocation invocation) {
        if (invocation.payloadType() != null || invocation.payloadVersion() != null) {
            return new AsyncPayloadMetadata(invocation.payloadType(), invocation.payloadVersion());
        }
        try {
            return payloadCodec.inspectEncoded(invocation.serviceId(), invocation.methodId(), invocation.payload());
        } catch (RuntimeException ignored) {
            return AsyncPayloadMetadata.unknown();
        }
    }

    private static String classifyErrorCategory(Throwable throwable) {
        if (throwable instanceof AsyncPayloadCodecException) {
            String message = throwable.getMessage();
            if (message != null) {
                String lowered = message.toLowerCase();
                if (lowered.contains("migration")) {
                    return ERROR_CATEGORY_MIGRATION;
                }
            }
            return ERROR_CATEGORY_DECODE;
        }
        return ERROR_CATEGORY_BUSINESS;
    }

    private static String key(String serviceId, String methodId) {
        return serviceId + "::" + methodId;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private record MethodRegistration(MethodRuntime runtime, AsyncMethodBinding binding) {
    }

    private final class WorkerGroup {
        private final String methodKey;
        private final List<WorkerHandle> handles = new ArrayList<>();

        private WorkerGroup(String methodKey) {
            this.methodKey = methodKey;
        }

        private synchronized void resize(int targetThreads) {
            while (handles.size() < targetThreads) {
                WorkerHandle handle = new WorkerHandle(methodKey, handles.size());
                handles.add(handle);
                handle.start();
            }
            while (handles.size() > targetThreads) {
                WorkerHandle handle = handles.remove(handles.size() - 1);
                handle.stop();
                handle.awaitStopped(workerShutdownAwaitMillis);
            }
        }

        private synchronized void shutdown() {
            while (!handles.isEmpty()) {
                WorkerHandle handle = handles.remove(handles.size() - 1);
                handle.stop();
                handle.awaitStopped(workerShutdownAwaitMillis);
            }
        }
    }

    private final class WorkerHandle implements Runnable {
        private final String methodKey;
        private final Thread thread;
        private volatile boolean running = true;

        private WorkerHandle(String methodKey, int index) {
            this.methodKey = methodKey;
            this.thread = new Thread(this, "asl-worker-" + methodKey.replace(':', '_') + "-" + index);
            this.thread.setDaemon(true);
            this.thread.setUncaughtExceptionHandler((ignored, throwable) -> {
            });
        }

        private void start() {
            thread.start();
        }

        private void stop() {
            running = false;
        }

        private void awaitStopped(long awaitMillis) {
            try {
                thread.join(awaitMillis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for worker shutdown", exception);
            }
            if (thread.isAlive()) {
                throw new IllegalStateException(
                        "Timed out while waiting for worker shutdown: " + thread.getName()
                );
            }
        }

        @Override
        public void run() {
            while (running) {
                process(methodKey);
            }
        }
    }
}
