package com.reactor.asl.spring.boot;

import com.reactor.asl.mapdb.MapDbCorruptionPolicy;
import com.reactor.asl.mapdb.ErrorDetailPolicy;
import com.reactor.asl.mapdb.PayloadSummaryPolicy;
import com.reactor.asl.mapdb.QueueOverflowPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties("asl.async.mapdb")
public class AslAsyncMapDbProperties {
    private boolean enabled;
    private String path = "./data/asl-queue.db";
    private String codec = "jackson-json";
    private long workerShutdownAwaitMillis = 10_000L;
    private long registrationIdleSleepMillis = 100L;
    private long emptyQueueSleepMillis = 50L;
    private long requeueDelayMillis = 75L;
    private String recoveredInProgressMessage = "Recovered stale in-progress invocation after restart";
    private boolean transactionsEnabled = true;
    private boolean memoryMappedEnabled;
    private MapDbCorruptionPolicy corruptionPolicy = MapDbCorruptionPolicy.FAIL_FAST;
    private Admission admission = new Admission();
    private Writer writer = new Writer();
    private Workers workers = new Workers();
    private Retry retry = new Retry();
    private Idempotency idempotency = new Idempotency();
    private Payload payload = new Payload();
    private Map<String, ServiceConfiguration> services = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getCodec() {
        return codec;
    }

    public void setCodec(String codec) {
        this.codec = codec;
    }

    public long getWorkerShutdownAwaitMillis() {
        return workerShutdownAwaitMillis;
    }

    public void setWorkerShutdownAwaitMillis(long workerShutdownAwaitMillis) {
        this.workerShutdownAwaitMillis = workerShutdownAwaitMillis;
    }

    public long getRegistrationIdleSleepMillis() {
        return registrationIdleSleepMillis;
    }

    public void setRegistrationIdleSleepMillis(long registrationIdleSleepMillis) {
        this.registrationIdleSleepMillis = registrationIdleSleepMillis;
    }

    public long getEmptyQueueSleepMillis() {
        return emptyQueueSleepMillis;
    }

    public void setEmptyQueueSleepMillis(long emptyQueueSleepMillis) {
        this.emptyQueueSleepMillis = emptyQueueSleepMillis;
    }

    public long getRequeueDelayMillis() {
        return requeueDelayMillis;
    }

    public void setRequeueDelayMillis(long requeueDelayMillis) {
        this.requeueDelayMillis = requeueDelayMillis;
    }

    public String getRecoveredInProgressMessage() {
        return recoveredInProgressMessage;
    }

    public void setRecoveredInProgressMessage(String recoveredInProgressMessage) {
        this.recoveredInProgressMessage = recoveredInProgressMessage;
    }

    public boolean isTransactionsEnabled() {
        return transactionsEnabled;
    }

    public void setTransactionsEnabled(boolean transactionsEnabled) {
        this.transactionsEnabled = transactionsEnabled;
    }

    public boolean isMemoryMappedEnabled() {
        return memoryMappedEnabled;
    }

    public void setMemoryMappedEnabled(boolean memoryMappedEnabled) {
        this.memoryMappedEnabled = memoryMappedEnabled;
    }

    public MapDbCorruptionPolicy getCorruptionPolicy() {
        return corruptionPolicy;
    }

    public void setCorruptionPolicy(MapDbCorruptionPolicy corruptionPolicy) {
        this.corruptionPolicy = corruptionPolicy;
    }

    public Admission getAdmission() {
        return admission;
    }

    public void setAdmission(Admission admission) {
        this.admission = admission == null ? new Admission() : admission;
    }

    public Writer getWriter() {
        return writer;
    }

    public void setWriter(Writer writer) {
        this.writer = writer == null ? new Writer() : writer;
    }

    public Workers getWorkers() {
        return workers;
    }

    public void setWorkers(Workers workers) {
        this.workers = workers == null ? new Workers() : workers;
    }

    public Retry getRetry() {
        return retry;
    }

    public Idempotency getIdempotency() {
        return idempotency;
    }

    public void setIdempotency(Idempotency idempotency) {
        this.idempotency = idempotency == null ? new Idempotency() : idempotency;
    }

    public void setRetry(Retry retry) {
        this.retry = retry == null ? new Retry() : retry;
    }

    public Payload getPayload() {
        return payload;
    }

    public void setPayload(Payload payload) {
        this.payload = payload == null ? new Payload() : payload;
    }

    public Map<String, ServiceConfiguration> getServices() {
        return services;
    }

    public void setServices(Map<String, ServiceConfiguration> services) {
        this.services = services == null ? new LinkedHashMap<>() : new LinkedHashMap<>(services);
    }

    /**
     * @deprecated Use {@code corruption-policy=ARCHIVE_AND_RESET}. This alias will be removed
     * in the next incompatible release.
     */
    @Deprecated(forRemoval = true)
    public boolean isResetIfCorrupt() {
        return corruptionPolicy == MapDbCorruptionPolicy.ARCHIVE_AND_RESET;
    }

    /** @deprecated Use {@link #setCorruptionPolicy(MapDbCorruptionPolicy)}. */
    @Deprecated(forRemoval = true)
    public void setResetIfCorrupt(boolean resetIfCorrupt) {
        this.corruptionPolicy = resetIfCorrupt
                ? MapDbCorruptionPolicy.ARCHIVE_AND_RESET
                : MapDbCorruptionPolicy.FAIL_FAST;
    }

    public static final class Admission {
        private long maxTotalEntries = 100_000L;
        private long maxTotalPayloadBytes = 512L * 1024L * 1024L;
        private long defaultLaneMaxEntries = 10_000L;
        private long defaultLaneMaxPayloadBytes = 64L * 1024L * 1024L;
        private long maxSinglePayloadBytes = 1024L * 1024L;
        private int maxConcurrentEnqueues = 64;
        private QueueOverflowPolicy overflowPolicy = QueueOverflowPolicy.REJECT;
        private long enqueueTimeoutMillis;

        public long getMaxTotalEntries() { return maxTotalEntries; }
        public void setMaxTotalEntries(long value) { this.maxTotalEntries = value; }
        public long getMaxTotalPayloadBytes() { return maxTotalPayloadBytes; }
        public void setMaxTotalPayloadBytes(long value) { this.maxTotalPayloadBytes = value; }
        public long getDefaultLaneMaxEntries() { return defaultLaneMaxEntries; }
        public void setDefaultLaneMaxEntries(long value) { this.defaultLaneMaxEntries = value; }
        public long getDefaultLaneMaxPayloadBytes() { return defaultLaneMaxPayloadBytes; }
        public void setDefaultLaneMaxPayloadBytes(long value) { this.defaultLaneMaxPayloadBytes = value; }
        public long getMaxSinglePayloadBytes() { return maxSinglePayloadBytes; }
        public void setMaxSinglePayloadBytes(long value) { this.maxSinglePayloadBytes = value; }
        public int getMaxConcurrentEnqueues() { return maxConcurrentEnqueues; }
        public void setMaxConcurrentEnqueues(int value) { this.maxConcurrentEnqueues = value; }
        public QueueOverflowPolicy getOverflowPolicy() { return overflowPolicy; }
        public void setOverflowPolicy(QueueOverflowPolicy value) { this.overflowPolicy = value; }
        public long getEnqueueTimeoutMillis() { return enqueueTimeoutMillis; }
        public void setEnqueueTimeoutMillis(long value) { this.enqueueTimeoutMillis = value; }
    }

    public static final class Writer {
        private int queueCapacity = 4_096;
        private int commitBatchSize = 64;
        private long commitBatchWindowMillis = 2L;
        private long operationTimeoutMillis = 30_000L;

        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int value) { this.queueCapacity = value; }
        public int getCommitBatchSize() { return commitBatchSize; }
        public void setCommitBatchSize(int value) { this.commitBatchSize = value; }
        public long getCommitBatchWindowMillis() { return commitBatchWindowMillis; }
        public void setCommitBatchWindowMillis(long value) { this.commitBatchWindowMillis = value; }
        public long getOperationTimeoutMillis() { return operationTimeoutMillis; }
        public void setOperationTimeoutMillis(long value) { this.operationTimeoutMillis = value; }
    }

    public static final class Workers {
        private int maxThreads = Math.max(2, Math.min(Runtime.getRuntime().availableProcessors(), 32));
        private int taskQueueCapacity = 1_024;
        private long leaseDurationMillis = 60_000L;
        private long leaseRenewalIntervalMillis = 20_000L;
        private long executionTimeoutMillis;
        private boolean drainBacklogInSyncMode = true;
        private long completionTimeoutMillis = 5L * 60L * 1_000L;
        private long resultWaitTimeoutMillis = 10L * 60L * 1_000L;
        private int maxPendingResults = 10_000;

        /** ASYNC CompletableFuture methods: how long a worker waits for the method's future (then TIMEOUT). */
        public long getCompletionTimeoutMillis() { return completionTimeoutMillis; }
        public void setCompletionTimeoutMillis(long value) { this.completionTimeoutMillis = value; }
        /** ASYNC CompletableFuture methods: how long a caller's future may wait for the outcome. */
        public long getResultWaitTimeoutMillis() { return resultWaitTimeoutMillis; }
        public void setResultWaitTimeoutMillis(long value) { this.resultWaitTimeoutMillis = value; }
        /** ASYNC CompletableFuture methods: callers that may wait at the same time; more are rejected. */
        public int getMaxPendingResults() { return maxPendingResults; }
        public void setMaxPendingResults(int value) { this.maxPendingResults = value; }

        public int getMaxThreads() { return maxThreads; }
        public void setMaxThreads(int value) { this.maxThreads = value; }
        public int getTaskQueueCapacity() { return taskQueueCapacity; }
        public void setTaskQueueCapacity(int value) { this.taskQueueCapacity = value; }
        public long getLeaseDurationMillis() { return leaseDurationMillis; }
        public void setLeaseDurationMillis(long value) { this.leaseDurationMillis = value; }
        public long getLeaseRenewalIntervalMillis() { return leaseRenewalIntervalMillis; }
        public void setLeaseRenewalIntervalMillis(long value) { this.leaseRenewalIntervalMillis = value; }
        /** Business execution time after which the worker is interrupted; 0 disables the watchdog. */
        public long getExecutionTimeoutMillis() { return executionTimeoutMillis; }
        public void setExecutionTimeoutMillis(long value) { this.executionTimeoutMillis = value; }
        /** Keep consuming accepted backlog after a lane is switched back to SYNC. */
        public boolean isDrainBacklogInSyncMode() { return drainBacklogInSyncMode; }
        public void setDrainBacklogInSyncMode(boolean value) { this.drainBacklogInSyncMode = value; }
    }

    public static final class Retry {
        private int maxAttempts = 1;
        private int maxInterruptions = 3;
        private long initialBackoffMillis = 250L;
        private long maxBackoffMillis = 30_000L;
        private double jitterRatio = 0.20d;

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int value) { this.maxAttempts = value; }
        /** Times an execution interrupted by shutdown, crash or lease expiry is returned to the queue. */
        public int getMaxInterruptions() { return maxInterruptions; }
        public void setMaxInterruptions(int value) { this.maxInterruptions = value; }
        public long getInitialBackoffMillis() { return initialBackoffMillis; }
        public void setInitialBackoffMillis(long value) { this.initialBackoffMillis = value; }
        public long getMaxBackoffMillis() { return maxBackoffMillis; }
        public void setMaxBackoffMillis(long value) { this.maxBackoffMillis = value; }
        public double getJitterRatio() { return jitterRatio; }
        public void setJitterRatio(double value) { this.jitterRatio = value; }
    }

    public static final class Idempotency {
        private long retentionMillis = 24L * 60L * 60L * 1_000L;
        private int maxEntries = 100_000;
        private int maxKeyCharacters = 256;

        public long getRetentionMillis() { return retentionMillis; }
        public void setRetentionMillis(long value) { this.retentionMillis = value; }
        public int getMaxEntries() { return maxEntries; }
        public void setMaxEntries(int value) { this.maxEntries = value; }
        public int getMaxKeyCharacters() { return maxKeyCharacters; }
        public void setMaxKeyCharacters(int value) { this.maxKeyCharacters = value; }
    }

    public static final class Payload {
        private int maxSummaryCharacters = 512;
        private int maxErrorCharacters = 2_048;
        private PayloadSummaryPolicy summaryPolicy = PayloadSummaryPolicy.TYPE_ONLY;
        private ErrorDetailPolicy errorDetailPolicy = ErrorDetailPolicy.TYPE_ONLY;
        private List<String> allowedTypeNames = new ArrayList<>();
        private List<String> allowedTypePrefixes = new ArrayList<>();
        private long maxDeserializationDepth = 32L;
        private long maxDeserializationReferences = 10_000L;
        private long maxDeserializationArrayLength = 100_000L;
        private boolean legacyJavaMigrationEnabled;

        public int getMaxSummaryCharacters() { return maxSummaryCharacters; }
        public void setMaxSummaryCharacters(int value) { this.maxSummaryCharacters = value; }
        public int getMaxErrorCharacters() { return maxErrorCharacters; }
        public void setMaxErrorCharacters(int value) { this.maxErrorCharacters = value; }
        public PayloadSummaryPolicy getSummaryPolicy() { return summaryPolicy; }
        public void setSummaryPolicy(PayloadSummaryPolicy value) { this.summaryPolicy = value; }
        public ErrorDetailPolicy getErrorDetailPolicy() { return errorDetailPolicy; }
        public void setErrorDetailPolicy(ErrorDetailPolicy value) { this.errorDetailPolicy = value; }
        public List<String> getAllowedTypeNames() { return List.copyOf(allowedTypeNames); }
        public void setAllowedTypeNames(List<String> value) {
            this.allowedTypeNames = value == null ? new ArrayList<>() : new ArrayList<>(value);
        }
        public List<String> getAllowedTypePrefixes() { return List.copyOf(allowedTypePrefixes); }
        public void setAllowedTypePrefixes(List<String> value) {
            this.allowedTypePrefixes = value == null ? new ArrayList<>() : new ArrayList<>(value);
        }
        public long getMaxDeserializationDepth() { return maxDeserializationDepth; }
        public void setMaxDeserializationDepth(long value) { this.maxDeserializationDepth = value; }
        public long getMaxDeserializationReferences() { return maxDeserializationReferences; }
        public void setMaxDeserializationReferences(long value) { this.maxDeserializationReferences = value; }
        public long getMaxDeserializationArrayLength() { return maxDeserializationArrayLength; }
        public void setMaxDeserializationArrayLength(long value) { this.maxDeserializationArrayLength = value; }
        public boolean isLegacyJavaMigrationEnabled() { return legacyJavaMigrationEnabled; }
        public void setLegacyJavaMigrationEnabled(boolean value) { this.legacyJavaMigrationEnabled = value; }
    }

    public static final class ServiceConfiguration {
        private Map<String, LaneConfiguration> methods = new LinkedHashMap<>();

        public Map<String, LaneConfiguration> getMethods() { return methods; }
        public void setMethods(Map<String, LaneConfiguration> methods) {
            this.methods = methods == null ? new LinkedHashMap<>() : new LinkedHashMap<>(methods);
        }
    }

    public static final class LaneConfiguration {
        private Long maxEntries;
        private Long maxPayloadBytes;

        public Long getMaxEntries() { return maxEntries; }
        public void setMaxEntries(Long value) { this.maxEntries = value; }
        public Long getMaxPayloadBytes() { return maxPayloadBytes; }
        public void setMaxPayloadBytes(Long value) { this.maxPayloadBytes = value; }
    }
}
