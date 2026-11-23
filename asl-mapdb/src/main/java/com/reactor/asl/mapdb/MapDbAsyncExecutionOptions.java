package com.reactor.asl.mapdb;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class MapDbAsyncExecutionOptions {
    public static final long MEBIBYTE = 1024L * 1024L;

    private final long workerShutdownAwaitMillis;
    private final long registrationIdleSleepMillis;
    private final long emptyQueueSleepMillis;
    private final long requeueDelayMillis;
    private final String recoveredInProgressMessage;
    private final boolean transactionsEnabled;
    private final boolean memoryMappedEnabled;
    private final MapDbCorruptionPolicy corruptionPolicy;
    private final AsyncQueueLimits globalLimits;
    private final AsyncQueueLimits defaultLaneLimits;
    private final Map<String, AsyncQueueLimits> laneLimits;
    private final long maxPayloadBytes;
    private final int maxConcurrentEnqueues;
    private final QueueOverflowPolicy overflowPolicy;
    private final long enqueueTimeoutMillis;
    private final int writerQueueCapacity;
    private final int commitBatchSize;
    private final long commitBatchWindowMillis;
    private final long writerOperationTimeoutMillis;
    private final int maxWorkerThreads;
    private final int workerQueueCapacity;
    private final int maxAttempts;
    private final long retryInitialBackoffMillis;
    private final long retryMaxBackoffMillis;
    private final double retryJitterRatio;
    private final long leaseDurationMillis;
    private final long leaseRenewalIntervalMillis;
    private final int maxInterruptions;
    private final long executionTimeoutMillis;
    private final boolean drainBacklogInSyncMode;
    private final long idempotencyRetentionMillis;
    private final int maxIdempotencyEntries;
    private final int maxIdempotencyKeyCharacters;
    private final int maxSummaryCharacters;
    private final int maxErrorCharacters;
    private final PayloadSummaryPolicy payloadSummaryPolicy;
    private final ErrorDetailPolicy errorDetailPolicy;
    private final boolean legacyJavaMigrationEnabled;
    private final long completionTimeoutMillis;
    private final long resultWaitTimeoutMillis;
    private final int maxPendingResults;

    private MapDbAsyncExecutionOptions(Builder builder) {
        this.completionTimeoutMillis = positive(builder.completionTimeoutMillis, "completionTimeoutMillis");
        this.resultWaitTimeoutMillis = positive(builder.resultWaitTimeoutMillis, "resultWaitTimeoutMillis");
        this.maxPendingResults = positive(builder.maxPendingResults, "maxPendingResults");
        this.workerShutdownAwaitMillis = positive(builder.workerShutdownAwaitMillis, "workerShutdownAwaitMillis");
        this.registrationIdleSleepMillis = positive(builder.registrationIdleSleepMillis, "registrationIdleSleepMillis");
        this.emptyQueueSleepMillis = positive(builder.emptyQueueSleepMillis, "emptyQueueSleepMillis");
        this.requeueDelayMillis = positive(builder.requeueDelayMillis, "requeueDelayMillis");
        this.recoveredInProgressMessage = requireText(builder.recoveredInProgressMessage, "recoveredInProgressMessage");
        this.transactionsEnabled = builder.transactionsEnabled;
        this.memoryMappedEnabled = builder.memoryMappedEnabled;
        this.corruptionPolicy = Objects.requireNonNull(builder.corruptionPolicy, "corruptionPolicy");
        this.globalLimits = Objects.requireNonNull(builder.globalLimits, "globalLimits");
        this.defaultLaneLimits = Objects.requireNonNull(builder.defaultLaneLimits, "defaultLaneLimits");
        this.laneLimits = Map.copyOf(builder.laneLimits);
        this.maxPayloadBytes = positive(builder.maxPayloadBytes, "maxPayloadBytes");
        this.maxConcurrentEnqueues = positive(builder.maxConcurrentEnqueues, "maxConcurrentEnqueues");
        this.overflowPolicy = Objects.requireNonNull(builder.overflowPolicy, "overflowPolicy");
        this.enqueueTimeoutMillis = nonNegative(builder.enqueueTimeoutMillis, "enqueueTimeoutMillis");
        if (overflowPolicy == QueueOverflowPolicy.BLOCK_WITH_TIMEOUT && enqueueTimeoutMillis == 0) {
            throw new IllegalArgumentException("enqueueTimeoutMillis must be positive for BLOCK_WITH_TIMEOUT");
        }
        this.writerQueueCapacity = positive(builder.writerQueueCapacity, "writerQueueCapacity");
        this.commitBatchSize = positive(builder.commitBatchSize, "commitBatchSize");
        this.commitBatchWindowMillis = nonNegative(builder.commitBatchWindowMillis, "commitBatchWindowMillis");
        this.writerOperationTimeoutMillis = positive(builder.writerOperationTimeoutMillis, "writerOperationTimeoutMillis");
        this.maxWorkerThreads = positive(builder.maxWorkerThreads, "maxWorkerThreads");
        this.workerQueueCapacity = positive(builder.workerQueueCapacity, "workerQueueCapacity");
        this.maxAttempts = positive(builder.maxAttempts, "maxAttempts");
        this.retryInitialBackoffMillis = positive(builder.retryInitialBackoffMillis, "retryInitialBackoffMillis");
        this.retryMaxBackoffMillis = positive(builder.retryMaxBackoffMillis, "retryMaxBackoffMillis");
        if (retryMaxBackoffMillis < retryInitialBackoffMillis) {
            throw new IllegalArgumentException("retryMaxBackoffMillis must be >= retryInitialBackoffMillis");
        }
        if (builder.retryJitterRatio < 0.0d || builder.retryJitterRatio > 1.0d) {
            throw new IllegalArgumentException("retryJitterRatio must be between 0.0 and 1.0");
        }
        this.retryJitterRatio = builder.retryJitterRatio;
        this.leaseDurationMillis = positive(builder.leaseDurationMillis, "leaseDurationMillis");
        this.leaseRenewalIntervalMillis = positive(builder.leaseRenewalIntervalMillis, "leaseRenewalIntervalMillis");
        if (leaseRenewalIntervalMillis >= leaseDurationMillis) {
            throw new IllegalArgumentException("leaseRenewalIntervalMillis must be less than leaseDurationMillis");
        }
        this.maxInterruptions = positive(builder.maxInterruptions, "maxInterruptions");
        if (builder.executionTimeoutMillis < 0L) {
            throw new IllegalArgumentException("executionTimeoutMillis must be zero (disabled) or positive");
        }
        this.executionTimeoutMillis = builder.executionTimeoutMillis;
        this.drainBacklogInSyncMode = builder.drainBacklogInSyncMode;
        this.idempotencyRetentionMillis = positive(builder.idempotencyRetentionMillis, "idempotencyRetentionMillis");
        this.maxIdempotencyEntries = positive(builder.maxIdempotencyEntries, "maxIdempotencyEntries");
        this.maxIdempotencyKeyCharacters = positive(
                builder.maxIdempotencyKeyCharacters,
                "maxIdempotencyKeyCharacters"
        );
        this.maxSummaryCharacters = positive(builder.maxSummaryCharacters, "maxSummaryCharacters");
        this.maxErrorCharacters = positive(builder.maxErrorCharacters, "maxErrorCharacters");
        this.payloadSummaryPolicy = Objects.requireNonNull(builder.payloadSummaryPolicy, "payloadSummaryPolicy");
        this.errorDetailPolicy = Objects.requireNonNull(builder.errorDetailPolicy, "errorDetailPolicy");
        this.legacyJavaMigrationEnabled = builder.legacyJavaMigrationEnabled;
    }

    public static Builder builder() {
        return new Builder();
    }

    public long workerShutdownAwaitMillis() {
        return workerShutdownAwaitMillis;
    }

    public long registrationIdleSleepMillis() {
        return registrationIdleSleepMillis;
    }

    public long emptyQueueSleepMillis() {
        return emptyQueueSleepMillis;
    }

    public long requeueDelayMillis() {
        return requeueDelayMillis;
    }

    public String recoveredInProgressMessage() {
        return recoveredInProgressMessage;
    }

    public boolean transactionsEnabled() {
        return transactionsEnabled;
    }

    public boolean memoryMappedEnabled() {
        return memoryMappedEnabled;
    }

    public MapDbCorruptionPolicy corruptionPolicy() {
        return corruptionPolicy;
    }

    public AsyncQueueLimits globalLimits() {
        return globalLimits;
    }

    public AsyncQueueLimits laneLimits(String serviceId, String methodId) {
        return laneLimits.getOrDefault(laneKey(serviceId, methodId), defaultLaneLimits);
    }

    public Map<String, AsyncQueueLimits> laneLimitOverrides() {
        return laneLimits;
    }

    public long maxPayloadBytes() {
        return maxPayloadBytes;
    }

    public int maxConcurrentEnqueues() {
        return maxConcurrentEnqueues;
    }

    public QueueOverflowPolicy overflowPolicy() {
        return overflowPolicy;
    }

    public long enqueueTimeoutMillis() {
        return enqueueTimeoutMillis;
    }

    public int writerQueueCapacity() {
        return writerQueueCapacity;
    }

    public int commitBatchSize() {
        return commitBatchSize;
    }

    public long commitBatchWindowMillis() {
        return commitBatchWindowMillis;
    }

    public long writerOperationTimeoutMillis() {
        return writerOperationTimeoutMillis;
    }

    public int maxWorkerThreads() {
        return maxWorkerThreads;
    }

    public int workerQueueCapacity() {
        return workerQueueCapacity;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public long retryInitialBackoffMillis() {
        return retryInitialBackoffMillis;
    }

    public long retryMaxBackoffMillis() {
        return retryMaxBackoffMillis;
    }

    public double retryJitterRatio() {
        return retryJitterRatio;
    }

    public long leaseDurationMillis() {
        return leaseDurationMillis;
    }

    public long leaseRenewalIntervalMillis() {
        return leaseRenewalIntervalMillis;
    }

    /**
     * How many times an execution may be interrupted (graceful shutdown, crash, expired lease) and
     * still be returned to the queue. Interruptions do not consume {@link #maxAttempts()}.
     */
    public int maxInterruptions() {
        return maxInterruptions;
    }

    /**
     * Maximum business execution time before the worker thread is interrupted; {@code 0} disables
     * the watchdog. Interruption is cooperative: code that ignores interrupts keeps its worker.
     */
    public long executionTimeoutMillis() {
        return executionTimeoutMillis;
    }

    /**
     * Keep consuming an already accepted backlog after a lane is switched back to SYNC. When
     * disabled the backlog waits until the lane is switched to ASYNC again.
     */
    public boolean drainBacklogInSyncMode() {
        return drainBacklogInSyncMode;
    }

    public long idempotencyRetentionMillis() {
        return idempotencyRetentionMillis;
    }

    public int maxIdempotencyEntries() {
        return maxIdempotencyEntries;
    }

    public int maxIdempotencyKeyCharacters() {
        return maxIdempotencyKeyCharacters;
    }

    public int maxSummaryCharacters() {
        return maxSummaryCharacters;
    }

    public int maxErrorCharacters() {
        return maxErrorCharacters;
    }

    public PayloadSummaryPolicy payloadSummaryPolicy() {
        return payloadSummaryPolicy;
    }

    public ErrorDetailPolicy errorDetailPolicy() {
        return errorDetailPolicy;
    }

    public boolean legacyJavaMigrationEnabled() {
        return legacyJavaMigrationEnabled;
    }

    /**
     * How long a worker waits for the {@code CompletableFuture} an ASYNC future method returned. Past it the
     * invocation fails with error category {@code TIMEOUT} (and is retried if attempts are left).
     */
    public long completionTimeoutMillis() {
        return completionTimeoutMillis;
    }

    /**
     * How long a caller's future of an ASYNC future method may stay open. Past it the future fails with
     * {@code AsyncResultUnavailableException(WAIT_TIMEOUT)}; the invocation still runs and its outcome goes to the
     * result handler. Keeps memory bounded when outcomes never arrive (for example a lane left at 0 consumers).
     */
    public long resultWaitTimeoutMillis() {
        return resultWaitTimeoutMillis;
    }

    /** Callers that may wait for results at the same time; further ASYNC future calls are rejected (RESULT_CAPACITY). */
    public int maxPendingResults() {
        return maxPendingResults;
    }

    public static String laneKey(String serviceId, String methodId) {
        String service = Objects.requireNonNull(serviceId, "serviceId");
        String method = Objects.requireNonNull(methodId, "methodId");
        if (service.indexOf('\0') >= 0 || method.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("serviceId and methodId must not contain NUL");
        }
        return service + '\0' + method;
    }

    private static long positive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static long nonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be zero or positive");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public static final class Builder {
        private long workerShutdownAwaitMillis = 10_000L;
        private long registrationIdleSleepMillis = 100L;
        private long emptyQueueSleepMillis = 50L;
        private long requeueDelayMillis = 75L;
        private String recoveredInProgressMessage = MapDbAsyncExecutionEngine.RECOVERED_IN_PROGRESS_MESSAGE;
        private boolean transactionsEnabled = true;
        private boolean memoryMappedEnabled;
        private MapDbCorruptionPolicy corruptionPolicy = MapDbCorruptionPolicy.FAIL_FAST;
        private AsyncQueueLimits globalLimits = new AsyncQueueLimits(100_000L, 512L * MEBIBYTE);
        private AsyncQueueLimits defaultLaneLimits = new AsyncQueueLimits(10_000L, 64L * MEBIBYTE);
        private final Map<String, AsyncQueueLimits> laneLimits = new LinkedHashMap<>();
        private long maxPayloadBytes = MEBIBYTE;
        private int maxConcurrentEnqueues = 64;
        private QueueOverflowPolicy overflowPolicy = QueueOverflowPolicy.REJECT;
        private long enqueueTimeoutMillis;
        private int writerQueueCapacity = 4_096;
        private int commitBatchSize = 64;
        private long commitBatchWindowMillis = 2L;
        private long writerOperationTimeoutMillis = 30_000L;
        private int maxWorkerThreads = Math.max(2, Math.min(Runtime.getRuntime().availableProcessors(), 32));
        private int workerQueueCapacity = 1_024;
        private int maxAttempts = 1;
        private long retryInitialBackoffMillis = 250L;
        private long retryMaxBackoffMillis = 30_000L;
        private double retryJitterRatio = 0.20d;
        private long leaseDurationMillis = 60_000L;
        private long leaseRenewalIntervalMillis = 20_000L;
        private int maxInterruptions = 3;
        private long executionTimeoutMillis;
        private boolean drainBacklogInSyncMode = true;
        private long idempotencyRetentionMillis = 24L * 60L * 60L * 1_000L;
        private int maxIdempotencyEntries = 100_000;
        private int maxIdempotencyKeyCharacters = 256;
        private int maxSummaryCharacters = 512;
        private int maxErrorCharacters = 2_048;
        private PayloadSummaryPolicy payloadSummaryPolicy = PayloadSummaryPolicy.TYPE_ONLY;
        private ErrorDetailPolicy errorDetailPolicy = ErrorDetailPolicy.TYPE_ONLY;
        private boolean legacyJavaMigrationEnabled;
        private long completionTimeoutMillis = 5L * 60L * 1_000L;
        private long resultWaitTimeoutMillis = 10L * 60L * 1_000L;
        private int maxPendingResults = 10_000;

        private Builder() {
        }

        public Builder completionTimeoutMillis(long value) { this.completionTimeoutMillis = value; return this; }
        public Builder resultWaitTimeoutMillis(long value) { this.resultWaitTimeoutMillis = value; return this; }
        public Builder maxPendingResults(int value) { this.maxPendingResults = value; return this; }

        public Builder workerShutdownAwaitMillis(long value) { this.workerShutdownAwaitMillis = value; return this; }
        public Builder registrationIdleSleepMillis(long value) { this.registrationIdleSleepMillis = value; return this; }
        public Builder emptyQueueSleepMillis(long value) { this.emptyQueueSleepMillis = value; return this; }
        public Builder requeueDelayMillis(long value) { this.requeueDelayMillis = value; return this; }
        public Builder recoveredInProgressMessage(String value) { this.recoveredInProgressMessage = value; return this; }
        public Builder transactionsEnabled(boolean value) { this.transactionsEnabled = value; return this; }
        public Builder memoryMappedEnabled(boolean value) { this.memoryMappedEnabled = value; return this; }
        public Builder corruptionPolicy(MapDbCorruptionPolicy value) { this.corruptionPolicy = value; return this; }
        public Builder globalLimits(AsyncQueueLimits value) { this.globalLimits = value; return this; }
        public Builder defaultLaneLimits(AsyncQueueLimits value) { this.defaultLaneLimits = value; return this; }
        public Builder laneLimits(String serviceId, String methodId, AsyncQueueLimits value) {
            this.laneLimits.put(laneKey(serviceId, methodId), Objects.requireNonNull(value, "value"));
            return this;
        }
        public Builder maxPayloadBytes(long value) { this.maxPayloadBytes = value; return this; }
        public Builder maxConcurrentEnqueues(int value) { this.maxConcurrentEnqueues = value; return this; }
        public Builder overflowPolicy(QueueOverflowPolicy value) { this.overflowPolicy = value; return this; }
        public Builder enqueueTimeoutMillis(long value) { this.enqueueTimeoutMillis = value; return this; }
        public Builder writerQueueCapacity(int value) { this.writerQueueCapacity = value; return this; }
        public Builder commitBatchSize(int value) { this.commitBatchSize = value; return this; }
        public Builder commitBatchWindowMillis(long value) { this.commitBatchWindowMillis = value; return this; }
        public Builder writerOperationTimeoutMillis(long value) { this.writerOperationTimeoutMillis = value; return this; }
        public Builder maxWorkerThreads(int value) { this.maxWorkerThreads = value; return this; }
        public Builder workerQueueCapacity(int value) { this.workerQueueCapacity = value; return this; }
        public Builder maxAttempts(int value) { this.maxAttempts = value; return this; }
        public Builder retryInitialBackoffMillis(long value) { this.retryInitialBackoffMillis = value; return this; }
        public Builder retryMaxBackoffMillis(long value) { this.retryMaxBackoffMillis = value; return this; }
        public Builder retryJitterRatio(double value) { this.retryJitterRatio = value; return this; }
        public Builder leaseDurationMillis(long value) { this.leaseDurationMillis = value; return this; }
        public Builder leaseRenewalIntervalMillis(long value) { this.leaseRenewalIntervalMillis = value; return this; }
        public Builder maxInterruptions(int value) { this.maxInterruptions = value; return this; }
        public Builder executionTimeoutMillis(long value) { this.executionTimeoutMillis = value; return this; }
        public Builder drainBacklogInSyncMode(boolean value) { this.drainBacklogInSyncMode = value; return this; }
        public Builder idempotencyRetentionMillis(long value) { this.idempotencyRetentionMillis = value; return this; }
        public Builder maxIdempotencyEntries(int value) { this.maxIdempotencyEntries = value; return this; }
        public Builder maxIdempotencyKeyCharacters(int value) { this.maxIdempotencyKeyCharacters = value; return this; }
        public Builder maxSummaryCharacters(int value) { this.maxSummaryCharacters = value; return this; }
        public Builder maxErrorCharacters(int value) { this.maxErrorCharacters = value; return this; }
        public Builder payloadSummaryPolicy(PayloadSummaryPolicy value) { this.payloadSummaryPolicy = value; return this; }
        public Builder errorDetailPolicy(ErrorDetailPolicy value) { this.errorDetailPolicy = value; return this; }
        public Builder legacyJavaMigrationEnabled(boolean value) { this.legacyJavaMigrationEnabled = value; return this; }

        public MapDbAsyncExecutionOptions build() {
            return new MapDbAsyncExecutionOptions(this);
        }
    }
}
