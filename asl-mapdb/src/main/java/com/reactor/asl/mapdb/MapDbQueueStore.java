package com.reactor.asl.mapdb;

import com.reactor.asl.core.AsyncAdmissionRejectedException;
import com.reactor.asl.core.MethodBufferEntryView;
import com.reactor.asl.core.MethodBufferSnapshot;
import org.mapdb.BTreeMap;
import org.mapdb.DB;
import org.mapdb.HTreeMap;
import org.mapdb.Serializer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class MapDbQueueStore implements QueueStore {
    private static final String RECORDS_NAME = "asl.v2.records";
    private static final String STATE_INDEX_NAME = "asl.v2.state-index";
    private static final String RETRY_INDEX_NAME = "asl.v2.retry-index";
    private static final String LANE_IDS_NAME = "asl.v2.lane-ids";
    private static final String LANE_SERVICES_NAME = "asl.v2.lane-services";
    private static final String LANE_METHODS_NAME = "asl.v2.lane-methods";
    private static final String LANE_STATS_NAME = "asl.v2.lane-stats";
    private static final String IDEMPOTENCY_INDEX_NAME = "asl.v2.idempotency-index";
    private static final String IDEMPOTENCY_TOMBSTONES_NAME = "asl.v2.idempotency-tombstones";
    private static final String IDEMPOTENCY_EXPIRY_INDEX_NAME = "asl.v2.idempotency-expiry-index";
    private static final String METADATA_NAME = "asl.v2.metadata";
    private static final String LEGACY_RECORDS_NAME = "aslAsyncInvocations";
    private static final String META_SCHEMA_VERSION = "schemaVersion";
    private static final String META_NEXT_ID = "nextId";
    private static final String META_NEXT_LANE_ID = "nextLaneId";
    private static final String META_NEXT_LEASE_TOKEN = "nextLeaseToken";
    private static final String META_PROJECTION_VERSION = "projectionVersion";
    private static final String META_LEGACY_MIGRATED = "legacyMigrated";
    private static final String META_IDEMPOTENCY_ENTRIES = "idempotencyEntries";
    static final String RECOVERY_CATEGORY = "RECOVERY";
    static final String INTERRUPTED_CATEGORY = "INTERRUPTED";
    static final String LEASE_EXPIRED_CATEGORY = "LEASE_EXPIRED";
    private static final long SCHEMA_VERSION = 2L;
    private static final long ID_MASK = (1L << 48) - 1L;
    private static final int MAX_LANE_ID = 8_191;
    private static final int LANE_SHIFT = 50;
    private static final int STATE_SHIFT = 48;
    private static final char LANE_SEPARATOR = '\u0000';

    private final DB db;
    private final MapDbAsyncExecutionOptions options;
    private final QueueRecordCodec recordCodec;
    private final Object monitor = new Object();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicReference<RuntimeException> fatalFailure = new AtomicReference<>();
    private final HTreeMap<Long, byte[]> records;
    private final BTreeMap<Long, Long> stateIndex;
    private final BTreeMap<String, Long> retryIndex;
    private final HTreeMap<String, Integer> laneIds;
    private final HTreeMap<Integer, String> laneServices;
    private final HTreeMap<Integer, String> laneMethods;
    private final HTreeMap<Integer, byte[]> persistedLaneStats;
    private final HTreeMap<String, Long> idempotencyIndex;
    private final HTreeMap<String, byte[]> idempotencyTombstones;
    private final BTreeMap<String, String> idempotencyExpiryIndex;
    private final HTreeMap<String, Long> metadata;
    private final Map<Integer, LaneStats> laneStats = new HashMap<>();
    /** Lane counters as of the last commit; read without the store monitor (admin heartbeat, metrics). */
    private final ConcurrentHashMap<String, LaneCounts> committedCounts = new ConcurrentHashMap<>();
    private final Map<Integer, String> laneKeysById = new HashMap<>();
    private final Set<Integer> dirtyLanes = new HashSet<>();
    private long totalEntries;
    private long totalPayloadBytes;
    private boolean batching;
    private boolean batchDirty;

    @SuppressWarnings({"unchecked", "rawtypes"})
    MapDbQueueStore(DB db, MapDbAsyncExecutionOptions options) {
        this.db = Objects.requireNonNull(db, "db");
        this.options = Objects.requireNonNull(options, "options");
        this.recordCodec = new QueueRecordCodec(options.maxPayloadBytes());
        this.records = (HTreeMap<Long, byte[]>) (HTreeMap) db
                .hashMap(RECORDS_NAME, Serializer.LONG, Serializer.BYTE_ARRAY)
                .createOrOpen();
        this.stateIndex = (BTreeMap<Long, Long>) (BTreeMap) db
                .treeMap(STATE_INDEX_NAME, Serializer.LONG, Serializer.LONG)
                .createOrOpen();
        this.retryIndex = (BTreeMap<String, Long>) (BTreeMap) db
                .treeMap(RETRY_INDEX_NAME, Serializer.STRING, Serializer.LONG)
                .createOrOpen();
        this.laneIds = (HTreeMap<String, Integer>) (HTreeMap) db
                .hashMap(LANE_IDS_NAME, Serializer.STRING, Serializer.INTEGER)
                .createOrOpen();
        this.laneServices = (HTreeMap<Integer, String>) (HTreeMap) db
                .hashMap(LANE_SERVICES_NAME, Serializer.INTEGER, Serializer.STRING)
                .createOrOpen();
        this.laneMethods = (HTreeMap<Integer, String>) (HTreeMap) db
                .hashMap(LANE_METHODS_NAME, Serializer.INTEGER, Serializer.STRING)
                .createOrOpen();
        this.persistedLaneStats = (HTreeMap<Integer, byte[]>) (HTreeMap) db
                .hashMap(LANE_STATS_NAME, Serializer.INTEGER, Serializer.BYTE_ARRAY)
                .createOrOpen();
        this.idempotencyIndex = (HTreeMap<String, Long>) (HTreeMap) db
                .hashMap(IDEMPOTENCY_INDEX_NAME, Serializer.STRING, Serializer.LONG)
                .createOrOpen();
        this.idempotencyTombstones = (HTreeMap<String, byte[]>) (HTreeMap) db
                .hashMap(IDEMPOTENCY_TOMBSTONES_NAME, Serializer.STRING, Serializer.BYTE_ARRAY)
                .createOrOpen();
        this.idempotencyExpiryIndex = (BTreeMap<String, String>) (BTreeMap) db
                .treeMap(IDEMPOTENCY_EXPIRY_INDEX_NAME, Serializer.STRING, Serializer.STRING)
                .createOrOpen();
        this.metadata = (HTreeMap<String, Long>) (HTreeMap) db
                .hashMap(METADATA_NAME, Serializer.STRING, Serializer.LONG)
                .createOrOpen();

        synchronized (monitor) {
            migrateLegacyStoreIfNeeded();
            validateSchema();
            loadStatsCache();
            initializeIdempotencyCounter();
            recoverAbandonedClaims();
            metadata.put(META_SCHEMA_VERSION, SCHEMA_VERSION);
            commitOrFail("Could not initialize async queue store");
        }
    }

    @Override
    public EnqueueResult enqueue(NewInvocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        if (invocation.payload().length > options.maxPayloadBytes()) {
            throw rejection(
                    AsyncAdmissionRejectedException.Reason.PAYLOAD_TOO_LARGE,
                    "Async payload is " + invocation.payload().length + " bytes; limit is " + options.maxPayloadBytes()
            );
        }
        synchronized (monitor) {
            ensureAccepting();
            validateLanePart(invocation.serviceId(), "serviceId");
            validateLanePart(invocation.methodId(), "methodId");
            Integer existingLaneId = laneIds.get(laneKey(invocation.serviceId(), invocation.methodId()));
            if (invocation.idempotencyKey() != null && !invocation.idempotencyKey().isBlank()) {
                validateIdempotencyKey(invocation.idempotencyKey());
                purgeExpiredIdempotency(invocation.enqueuedAtEpochMillis(), 64);
                if (existingLaneId != null) {
                    String idempotencyIndexKey = idempotencyIndexKey(existingLaneId, invocation.idempotencyKey());
                    Long existingId = idempotencyIndex.get(idempotencyIndexKey);
                    if (existingId != null && records.containsKey(existingId)) {
                        return new EnqueueResult(existingId, true);
                    }
                    if (existingId != null) {
                        IdempotencyTombstone tombstone = decodeTombstone(idempotencyTombstones.get(idempotencyIndexKey));
                        if (tombstone != null && tombstone.expiresAtEpochMillis() > invocation.enqueuedAtEpochMillis()) {
                            return new EnqueueResult(tombstone.invocationId(), true);
                        }
                        removeIdempotencyEntry(idempotencyIndexKey, tombstone);
                    }
                }
                if (idempotencyEntryCount() >= options.maxIdempotencyEntries()) {
                    throw rejection(
                            AsyncAdmissionRejectedException.Reason.IDEMPOTENCY_CAPACITY,
                            "Idempotency index reached its bounded capacity of " + options.maxIdempotencyEntries()
                    );
                }
            }
            awaitCapacity(invocation, existingLaneId);
            int laneId = existingLaneId == null
                    ? ensureLane(invocation.serviceId(), invocation.methodId())
                    : existingLaneId;

            long id = nextMetadataValue(META_NEXT_ID, ID_MASK, "invocation id");
            QueueRecord record = new QueueRecord(
                    id,
                    laneId,
                    invocation.codecId(),
                    invocation.payload(),
                    invocation.payloadSummary(),
                    invocation.payloadType(),
                    invocation.payloadVersion(),
                    invocation.idempotencyKey(),
                    AsyncInvocationState.PENDING,
                    invocation.enqueuedAtEpochMillis(),
                    0L,
                    0L,
                    0L,
                    0,
                    null,
                    null,
                    null
            );
            addRecord(record);
            if (record.idempotencyKey() != null && !record.idempotencyKey().isBlank()) {
                putIdempotencyEntry(idempotencyIndexKey(laneId, record.idempotencyKey()), id);
            }
            advanceProjectionVersion();
            commitOrFail("Could not durably enqueue async invocation");
            return new EnqueueResult(id, false);
        }
    }

    @Override
    public Optional<ClaimedInvocation> claimNext(String serviceId, String methodId, long nowEpochMillis) {
        synchronized (monitor) {
            ensureWritable();
            if (!accepting.get()) {
                return Optional.empty();
            }
            Integer laneId = laneIds.get(laneKey(serviceId, methodId));
            if (laneId == null) {
                return Optional.empty();
            }
            Map.Entry<Long, Long> first = firstIndexEntry(laneId, AsyncInvocationState.PENDING);
            if (first == null) {
                return Optional.empty();
            }
            QueueRecord current = requiredRecord(first.getValue());
            if (current.state() != AsyncInvocationState.PENDING || current.laneId() != laneId) {
                throw consistencyFailure("Pending index does not match record " + current.id());
            }
            long leaseToken = nextMetadataValue(META_NEXT_LEASE_TOKEN, Long.MAX_VALUE, "lease token");
            QueueRecord claimed = current.claimed(nowEpochMillis + options.leaseDurationMillis(), leaseToken);
            replaceRecord(current, claimed);
            advanceProjectionVersion();
            commitOrFail("Could not durably claim async invocation " + current.id());
            return Optional.of(new ClaimedInvocation(
                    claimed.id(),
                    claimed.leaseToken(),
                    serviceId,
                    methodId,
                    claimed.codecId(),
                    claimed.payload(),
                    claimed.payloadType(),
                    claimed.payloadVersion(),
                    claimed.attempts()
            ));
        }
    }

    @Override
    public boolean complete(long invocationId, long leaseToken) {
        synchronized (monitor) {
            ensureWritable();
            QueueRecord current = findRecord(invocationId);
            if (!ownsLease(current, leaseToken)) {
                return false;
            }
            if (current.idempotencyKey() == null || current.idempotencyKey().isBlank()) {
                removeRecord(current);
            } else {
                removeRecord(current, false);
                String indexKey = idempotencyIndexKey(current.laneId(), current.idempotencyKey());
                long expiresAt = System.currentTimeMillis() + options.idempotencyRetentionMillis();
                idempotencyTombstones.put(indexKey, encodeTombstone(invocationId, expiresAt));
                idempotencyExpiryIndex.put(idempotencyExpiryKey(expiresAt, indexKey), indexKey);
            }
            advanceProjectionVersion();
            commitOrFail("Could not durably complete async invocation " + invocationId);
            monitor.notifyAll();
            return true;
        }
    }

    @Override
    public boolean renewLease(long invocationId, long leaseToken, long leaseUntilEpochMillis) {
        synchronized (monitor) {
            ensureWritable();
            QueueRecord current = findRecord(invocationId);
            if (!ownsLease(current, leaseToken)) {
                return false;
            }
            records.put(invocationId, recordCodec.encode(current.leaseRenewed(leaseUntilEpochMillis)));
            commitOrFail("Could not durably renew async invocation lease " + invocationId);
            return true;
        }
    }

    @Override
    public FailureResult fail(
            long invocationId,
            long leaseToken,
            String error,
            String errorType,
            String category,
            long retryAtEpochMillis
    ) {
        synchronized (monitor) {
            ensureWritable();
            QueueRecord current = findRecord(invocationId);
            if (!ownsLease(current, leaseToken)) {
                return FailureResult.notApplied();
            }
            boolean retry = current.attempts() < options.maxAttempts();
            QueueRecord next = retry
                    ? current.retryAt(retryAtEpochMillis, error, errorType, category)
                    : current.failed(error, errorType, category);
            replaceRecord(current, next);
            if (retry) {
                retryIndex.put(retryKey(retryAtEpochMillis, invocationId), invocationId);
            }
            advanceProjectionVersion();
            commitOrFail("Could not durably record async invocation failure " + invocationId);
            return new FailureResult(true, retry, retry ? retryAtEpochMillis : 0L);
        }
    }

    @Override
    public int activateDueRetries(long nowEpochMillis, int limit) {
        if (limit <= 0) {
            return 0;
        }
        synchronized (monitor) {
            ensureWritable();
            int activated = 0;
            while (activated < limit) {
                Map.Entry<String, Long> first = retryIndex.firstEntry();
                if (first == null || retryEpochMillis(first.getKey()) > nowEpochMillis) {
                    break;
                }
                retryIndex.remove(first.getKey());
                QueueRecord current = findRecord(first.getValue());
                if (current == null) {
                    continue;
                }
                if (current.state() != AsyncInvocationState.RETRY_WAIT) {
                    throw consistencyFailure("Retry index does not match record " + current.id());
                }
                QueueRecord pending = current.pendingAfterRecovery(
                        current.lastError(),
                        current.lastErrorType(),
                        current.errorCategory()
                );
                replaceRecord(current, pending);
                activated++;
            }
            if (activated > 0) {
                advanceProjectionVersion();
                commitOrFail("Could not activate due async retries");
            }
            return activated;
        }
    }

    @Override
    public boolean release(long invocationId, long leaseToken, String reason) {
        synchronized (monitor) {
            ensureWritable();
            QueueRecord current = findRecord(invocationId);
            if (!ownsLease(current, leaseToken)) {
                return false;
            }
            replaceRecord(current, interruptedOrFailed(current, reason, INTERRUPTED_CATEGORY));
            advanceProjectionVersion();
            commitOrFail("Could not durably release async invocation " + invocationId);
            return true;
        }
    }

    @Override
    public Set<Long> renewLeases(Map<Long, Long> leaseTokensByInvocationId, long leaseUntilEpochMillis) {
        synchronized (monitor) {
            ensureWritable();
            Set<Long> lost = new HashSet<>();
            boolean renewedAny = false;
            for (Map.Entry<Long, Long> lease : leaseTokensByInvocationId.entrySet()) {
                QueueRecord current = findRecord(lease.getKey());
                if (!ownsLease(current, lease.getValue())) {
                    lost.add(lease.getKey());
                    continue;
                }
                records.put(current.id(), recordCodec.encode(current.leaseRenewed(leaseUntilEpochMillis)));
                renewedAny = true;
            }
            if (renewedAny) {
                commitOrFail("Could not durably renew async invocation leases");
            }
            return lost;
        }
    }

    @Override
    public int reclaimExpiredLeases(long nowEpochMillis, Set<Long> liveInvocationIds, int limit) {
        if (limit <= 0) {
            return 0;
        }
        synchronized (monitor) {
            ensureWritable();
            int reclaimed = 0;
            for (Map.Entry<Integer, LaneStats> lane : laneStats.entrySet()) {
                if (lane.getValue().inProgress <= 0) {
                    continue;
                }
                List<Long> inProgress = new ArrayList<>();
                collectIds(lane.getKey(), AsyncInvocationState.IN_PROGRESS, limit, inProgress);
                for (Long id : inProgress) {
                    if (reclaimed >= limit) {
                        break;
                    }
                    if (liveInvocationIds.contains(id)) {
                        continue;
                    }
                    QueueRecord current = requiredRecord(id);
                    if (current.leaseUntilEpochMillis() > nowEpochMillis) {
                        continue;
                    }
                    replaceRecord(current, interruptedOrFailed(
                            current,
                            "Reclaimed expired async invocation lease",
                            LEASE_EXPIRED_CATEGORY
                    ));
                    reclaimed++;
                }
            }
            if (reclaimed > 0) {
                advanceProjectionVersion();
                commitOrFail("Could not reclaim expired async invocation leases");
            }
            return reclaimed;
        }
    }

    private QueueRecord interruptedOrFailed(QueueRecord current, String reason, String category) {
        String errorType = IllegalStateException.class.getName();
        return current.interruptions() < options.maxInterruptions()
                ? current.interrupted(reason, errorType, category)
                : current.failedAfterInterruptions(reason, errorType, category);
    }

    @Override
    public long nextRetryAtEpochMillis() {
        return guardedRead(() -> {
            synchronized (monitor) {
                ensureReadable();
                Map.Entry<String, Long> first = retryIndex.firstEntry();
                return first == null ? Long.MAX_VALUE : retryEpochMillis(first.getKey());
            }
        });
    }

    /** Per-lane depth for metrics; {@code oldestPendingEnqueuedAtEpochMillis} is 0 when nothing is pending. */
    LaneDepth laneDepth(String serviceId, String methodId) {
        return guardedRead(() -> {
            synchronized (monitor) {
                ensureReadable();
                Integer laneId = laneIds.get(laneKey(serviceId, methodId));
                LaneStats stats = laneId == null ? null : laneStats.get(laneId);
                if (stats == null) {
                    return new LaneDepth(0L, 0L, 0L, 0L, 0L);
                }
                long oldestPending = 0L;
                Map.Entry<Long, Long> first = firstIndexEntry(laneId, AsyncInvocationState.PENDING);
                if (first != null) {
                    QueueRecord record = findRecord(first.getValue());
                    oldestPending = record == null ? 0L : record.enqueuedAtEpochMillis();
                }
                return new LaneDepth(stats.pending, stats.inProgress, stats.retryWait, stats.failed, oldestPending);
            }
        });
    }

    record LaneDepth(long pending, long inProgress, long retryWait, long failed, long oldestPendingEnqueuedAtEpochMillis) {
    }

    /**
     * Runs a read and classifies raw MapDB failures (I/O errors, a closed or damaged volume) as a
     * fatal storage failure, so they surface in health instead of leaking as unknown exceptions.
     */
    private <T> T guardedRead(java.util.function.Supplier<T> read) {
        try {
            return read.get();
        } catch (org.mapdb.DBException | IllegalAccessError storageFailure) {
            AsyncAdmissionRejectedException failure = new AsyncAdmissionRejectedException(
                    AsyncAdmissionRejectedException.Reason.STORAGE_FAILURE,
                    "MapDB async queue store is not readable",
                    storageFailure
            );
            fatalFailure.compareAndSet(null, failure);
            throw failure;
        }
    }

    @Override
    public MethodBufferSnapshot snapshot(String serviceId, String methodId, int limit) {
        if (limit <= 0) {
            // Counts only: served from the last committed counters, so a heartbeat or a metrics
            // scrape never waits on (or delays) the writer, claim or lease paths.
            ensureReadable();
            LaneCounts counts = committedCounts.get(laneKey(serviceId, methodId));
            return counts == null
                    ? new MethodBufferSnapshot(serviceId, methodId, true, 0, 0, 0, List.of())
                    : new MethodBufferSnapshot(serviceId, methodId, true,
                            counts.pending() + counts.retryWait(), counts.failed(), counts.inProgress(), List.of());
        }
        return guardedRead(() -> unguardedSnapshot(serviceId, methodId, limit));
    }

    private MethodBufferSnapshot unguardedSnapshot(String serviceId, String methodId, int limit) {
        synchronized (monitor) {
            ensureReadable();
            Integer laneId = laneIds.get(laneKey(serviceId, methodId));
            if (laneId == null) {
                return new MethodBufferSnapshot(serviceId, methodId, true, 0, 0, 0, List.of());
            }
            LaneStats stats = laneStats.getOrDefault(laneId, new LaneStats());
            List<Long> ids = new ArrayList<>(Math.max(0, limit) * AsyncInvocationState.values().length);
            if (limit > 0) {
                for (AsyncInvocationState state : AsyncInvocationState.values()) {
                    collectIds(laneId, state, limit, ids);
                }
            }
            List<MethodBufferEntryView> entries = ids.stream()
                    .sorted()
                    .limit(Math.max(0, limit))
                    .map(this::requiredRecord)
                    .map(this::toEntryView)
                    .toList();
            return new MethodBufferSnapshot(
                    serviceId,
                    methodId,
                    true,
                    stats.pending + stats.retryWait,
                    stats.failed,
                    stats.inProgress,
                    entries
            );
        }
    }

    @Override
    public int clear(String serviceId, String methodId) {
        synchronized (monitor) {
            ensureWritable();
            Integer laneId = laneIds.get(laneKey(serviceId, methodId));
            if (laneId == null) {
                return 0;
            }
            List<Long> removableIds = new ArrayList<>();
            collectAllIds(laneId, AsyncInvocationState.PENDING, removableIds);
            collectAllIds(laneId, AsyncInvocationState.RETRY_WAIT, removableIds);
            collectAllIds(laneId, AsyncInvocationState.FAILED, removableIds);
            for (Long id : removableIds) {
                QueueRecord current = requiredRecord(id);
                if (current.state() != AsyncInvocationState.IN_PROGRESS) {
                    removeRecord(current);
                }
            }
            if (!removableIds.isEmpty()) {
                advanceProjectionVersion();
                commitOrFail("Could not durably clear async lane " + serviceId + "::" + methodId);
                monitor.notifyAll();
            }
            return removableIds.size();
        }
    }

    @Override
    public boolean delete(String serviceId, String methodId, long invocationId) {
        synchronized (monitor) {
            ensureWritable();
            Integer laneId = laneIds.get(laneKey(serviceId, methodId));
            QueueRecord current = findRecord(invocationId);
            if (laneId == null || current == null || current.laneId() != laneId
                    || current.state() == AsyncInvocationState.IN_PROGRESS) {
                return false;
            }
            removeRecord(current);
            advanceProjectionVersion();
            commitOrFail("Could not durably delete async invocation " + invocationId);
            monitor.notifyAll();
            return true;
        }
    }

    @Override
    public boolean replay(String serviceId, String methodId, long invocationId) {
        synchronized (monitor) {
            ensureWritable();
            Integer laneId = laneIds.get(laneKey(serviceId, methodId));
            QueueRecord current = findRecord(invocationId);
            if (laneId == null || current == null || current.laneId() != laneId
                    || current.state() != AsyncInvocationState.FAILED) {
                return false;
            }
            replaceRecord(current, current.replayed());
            advanceProjectionVersion();
            commitOrFail("Could not durably replay async invocation " + invocationId);
            return true;
        }
    }

    /** Entries looked at by one bulk command at most, so a huge lane cannot hold the store monitor for long. */
    static final int MAX_BULK_SCAN = 50_000;

    @Override
    public int deleteMatching(String serviceId, String methodId, com.reactor.asl.core.QueueEntryFilter filter) {
        return applyMatching(serviceId, methodId, filter, false);
    }

    @Override
    public int replayMatching(String serviceId, String methodId, com.reactor.asl.core.QueueEntryFilter filter) {
        return applyMatching(serviceId, methodId, filter, true);
    }

    private int applyMatching(String serviceId, String methodId, com.reactor.asl.core.QueueEntryFilter filter, boolean replay) {
        Objects.requireNonNull(filter, "filter");
        synchronized (monitor) {
            ensureWritable();
            Integer laneId = laneIds.get(laneKey(serviceId, methodId));
            if (laneId == null) {
                return 0;
            }
            int changed = 0;
            int scanned = 0;
            for (AsyncInvocationState state : AsyncInvocationState.values()) {
                if (state == AsyncInvocationState.IN_PROGRESS || (replay && state != AsyncInvocationState.FAILED)
                        || !filter.includesState(state.name())) {
                    continue;
                }
                // Ids first (bounded): the index must not change while it is being iterated.
                List<Long> ids = new ArrayList<>();
                collectIds(laneId, state, MAX_BULK_SCAN - scanned, ids);
                for (Long id : ids) {
                    if (changed >= filter.max()) {
                        break;
                    }
                    scanned++;
                    QueueRecord current = requiredRecord(id);
                    if (!filter.matches(current.state().name(), current.errorCategory(), current.enqueuedAtEpochMillis())) {
                        continue;
                    }
                    if (replay) {
                        replaceRecord(current, current.replayed());
                    } else {
                        removeRecord(current);
                    }
                    changed++;
                }
                if (changed >= filter.max() || scanned >= MAX_BULK_SCAN) {
                    break;
                }
            }
            if (changed > 0) {
                advanceProjectionVersion();
                commitOrFail("Could not durably " + (replay ? "replay" : "delete") + " matching entries of "
                        + serviceId + "::" + methodId);
                monitor.notifyAll();
            }
            return changed;
        }
    }

    @Override
    public boolean hasPending(String serviceId, String methodId) {
        return guardedRead(() -> unguardedHasPending(serviceId, methodId));
    }

    private boolean unguardedHasPending(String serviceId, String methodId) {
        synchronized (monitor) {
            Integer laneId = laneIds.get(laneKey(serviceId, methodId));
            if (laneId == null) {
                return false;
            }
            LaneStats stats = laneStats.get(laneId);
            return stats != null && stats.pending > 0;
        }
    }

    @Override
    public long projectionVersion() {
        return guardedRead(() -> {
            synchronized (monitor) {
                return metadata.getOrDefault(META_PROJECTION_VERSION, 0L);
            }
        });
    }

    @Override
    public boolean awaitProjectionChange(long observedVersion, long timeoutMillis) {
        return guardedRead(() -> unguardedAwaitProjectionChange(observedVersion, timeoutMillis));
    }

    private boolean unguardedAwaitProjectionChange(long observedVersion, long timeoutMillis) {
        if (timeoutMillis <= 0L) {
            return projectionVersion() != observedVersion;
        }
        synchronized (monitor) {
            ensureReadable();
            if (metadata.getOrDefault(META_PROJECTION_VERSION, 0L) != observedVersion) {
                return true;
            }
            try {
                monitor.wait(timeoutMillis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
            ensureReadable();
            return metadata.getOrDefault(META_PROJECTION_VERSION, 0L) != observedVersion;
        }
    }

    /** Number of in-progress records across all lanes (in-memory stats, no scan). */
    long inProgressCount() {
        synchronized (monitor) {
            long total = 0L;
            for (LaneStats stats : laneStats.values()) {
                total += stats.inProgress;
            }
            return total;
        }
    }

    /** Fatal storage failure that stopped this store, if any. */
    Optional<RuntimeException> fatalFailure() {
        return Optional.ofNullable(fatalFailure.get());
    }

    @Override
    public void stopAdmissions() {
        accepting.set(false);
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    private void migrateLegacyStoreIfNeeded() {
        if (!db.exists(LEGACY_RECORDS_NAME) || metadata.getOrDefault(META_LEGACY_MIGRATED, 0L) == 1L) {
            return;
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        Map<Long, StoredAsyncInvocation> legacy = (Map<Long, StoredAsyncInvocation>) (Map) db
                .hashMap(LEGACY_RECORDS_NAME, Serializer.LONG, Serializer.JAVA)
                .open();
        if (legacy.isEmpty()) {
            metadata.put(META_LEGACY_MIGRATED, 1L);
            return;
        }
        if (!records.isEmpty()) {
            throw new IllegalStateException("Both legacy and v2 async queue records exist; refusing ambiguous migration");
        }
        if (!options.legacyJavaMigrationEnabled()) {
            throw new IllegalStateException(
                    "Legacy Java-serialized async queue records were found. Back up the store and set "
                            + "asl.async.mapdb.payload.legacy-java-migration-enabled=true for one controlled startup."
            );
        }
        List<StoredAsyncInvocation> ordered = legacy.values().stream()
                .sorted(Comparator.comparingLong(StoredAsyncInvocation::id))
                .toList();
        for (StoredAsyncInvocation old : ordered) {
            int laneId = ensureLane(old.serviceId(), old.methodId());
            QueueRecord migrated = new QueueRecord(
                    old.id(),
                    laneId,
                    old.codecId(),
                    old.payload(),
                    old.payloadSummary(),
                    old.payloadType(),
                    old.payloadVersion(),
                    null,
                    old.state(),
                    old.enqueuedAtEpochMillis(),
                    0L,
                    0L,
                    0L,
                    old.attempts(),
                    old.lastError(),
                    old.lastErrorType(),
                    old.errorCategory()
            );
            addRecord(migrated);
            metadata.put(META_NEXT_ID, Math.max(metadata.getOrDefault(META_NEXT_ID, 0L), old.id()));
        }
        legacy.clear();
        metadata.put(META_LEGACY_MIGRATED, 1L);
    }

    private void validateSchema() {
        long schemaVersion = metadata.getOrDefault(META_SCHEMA_VERSION, SCHEMA_VERSION);
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalStateException("Unsupported async queue schema version: " + schemaVersion);
        }
    }

    private void recoverAbandonedClaims() {
        int recovered = 0;
        for (Integer laneId : laneServices.keySet()) {
            List<Long> inProgress = new ArrayList<>();
            collectAllIds(laneId, AsyncInvocationState.IN_PROGRESS, inProgress);
            for (Long id : inProgress) {
                QueueRecord current = requiredRecord(id);
                // A crash interrupts the execution; it does not count as a business attempt.
                replaceRecord(current, interruptedOrFailed(
                        current,
                        options.recoveredInProgressMessage(),
                        RECOVERY_CATEGORY
                ));
                recovered++;
            }
        }
        if (recovered > 0) {
            advanceProjectionVersion();
        }
    }

    private int ensureLane(String serviceId, String methodId) {
        validateLanePart(serviceId, "serviceId");
        validateLanePart(methodId, "methodId");
        String laneKey = laneKey(serviceId, methodId);
        Integer existing = laneIds.get(laneKey);
        if (existing != null) {
            return existing;
        }
        long next = nextMetadataValue(META_NEXT_LANE_ID, MAX_LANE_ID, "lane id");
        int laneId = Math.toIntExact(next);
        laneIds.put(laneKey, laneId);
        laneKeysById.put(laneId, laneKey);
        laneServices.put(laneId, serviceId);
        laneMethods.put(laneId, methodId);
        LaneStats stats = new LaneStats();
        laneStats.put(laneId, stats);
        persistStats(laneId, stats);
        return laneId;
    }

    private void awaitCapacity(NewInvocation invocation, Integer laneId) {
        AsyncAdmissionRejectedException rejection = capacityRejection(invocation, laneId);
        if (rejection != null) {
            throw rejection;
        }
    }

    private AsyncAdmissionRejectedException capacityRejection(NewInvocation invocation, Integer laneId) {
        AsyncQueueLimits global = options.globalLimits();
        if (totalEntries + 1 > global.maxEntries()
                || totalPayloadBytes + invocation.payload().length > global.maxPayloadBytes()) {
            return rejection(
                    AsyncAdmissionRejectedException.Reason.GLOBAL_CAPACITY,
                    "Global async queue capacity reached: entries=" + totalEntries + "/" + global.maxEntries()
                            + ", payloadBytes=" + totalPayloadBytes + "/" + global.maxPayloadBytes()
            );
        }
        LaneStats stats = laneId == null ? new LaneStats() : laneStats.getOrDefault(laneId, new LaneStats());
        AsyncQueueLimits laneLimits = options.laneLimits(invocation.serviceId(), invocation.methodId());
        if (stats.entries + 1 > laneLimits.maxEntries()
                || stats.payloadBytes + invocation.payload().length > laneLimits.maxPayloadBytes()) {
            return rejection(
                    AsyncAdmissionRejectedException.Reason.LANE_CAPACITY,
                    "Async lane capacity reached for " + invocation.serviceId() + "::" + invocation.methodId()
                            + ": entries=" + stats.entries + "/" + laneLimits.maxEntries()
                            + ", payloadBytes=" + stats.payloadBytes + "/" + laneLimits.maxPayloadBytes()
            );
        }
        return null;
    }

    private void addRecord(QueueRecord record) {
        if (record.id() <= 0 || record.id() > ID_MASK) {
            throw new IllegalStateException("Async invocation id is outside supported range: " + record.id());
        }
        byte[] encodedRecord = recordCodec.encode(record);
        long recordIndexKey = indexKey(record.laneId(), record.state(), record.id());
        records.put(record.id(), encodedRecord);
        stateIndex.put(recordIndexKey, record.id());
        LaneStats stats = laneStats.computeIfAbsent(record.laneId(), ignored -> new LaneStats());
        stats.add(record.state(), record.payload().length);
        persistStats(record.laneId(), stats);
        totalEntries++;
        totalPayloadBytes += record.payload().length;
    }

    private void replaceRecord(QueueRecord current, QueueRecord replacement) {
        // Encode and compute index keys before mutating, so a rejected record cannot leave the
        // indexes half-updated inside an otherwise committed batch.
        byte[] encodedReplacement = recordCodec.encode(replacement);
        long currentIndexKey = indexKey(current.laneId(), current.state(), current.id());
        long replacementIndexKey = indexKey(replacement.laneId(), replacement.state(), replacement.id());
        stateIndex.remove(currentIndexKey);
        if (current.state() == AsyncInvocationState.RETRY_WAIT) {
            retryIndex.remove(retryKey(current.availableAtEpochMillis(), current.id()));
        }
        records.put(replacement.id(), encodedReplacement);
        stateIndex.put(replacementIndexKey, replacement.id());
        LaneStats stats = laneStats.get(current.laneId());
        stats.transition(current.state(), replacement.state());
        persistStats(current.laneId(), stats);
    }

    private void removeRecord(QueueRecord record) {
        removeRecord(record, true);
    }

    private void removeRecord(QueueRecord record, boolean removeIdempotency) {
        records.remove(record.id());
        stateIndex.remove(indexKey(record.laneId(), record.state(), record.id()));
        if (record.state() == AsyncInvocationState.RETRY_WAIT) {
            retryIndex.remove(retryKey(record.availableAtEpochMillis(), record.id()));
        }
        if (removeIdempotency && record.idempotencyKey() != null && !record.idempotencyKey().isBlank()) {
            String indexKey = idempotencyIndexKey(record.laneId(), record.idempotencyKey());
            IdempotencyTombstone tombstone = decodeTombstone(idempotencyTombstones.get(indexKey));
            removeIdempotencyEntry(indexKey, tombstone);
        }
        LaneStats stats = laneStats.get(record.laneId());
        stats.remove(record.state(), record.payload().length);
        persistStats(record.laneId(), stats);
        totalEntries--;
        totalPayloadBytes -= record.payload().length;
    }

    private Map.Entry<Long, Long> firstIndexEntry(int laneId, AsyncInvocationState state) {
        Map.Entry<Long, Long> entry = stateIndex.ceilingEntry(indexStart(laneId, state));
        return entry != null && entry.getKey() <= indexEnd(laneId, state) ? entry : null;
    }

    private void collectIds(int laneId, AsyncInvocationState state, int limit, List<Long> target) {
        int count = 0;
        for (Long id : stateIndex.subMap(indexStart(laneId, state), true, indexEnd(laneId, state), true).values()) {
            target.add(id);
            if (++count >= limit) {
                return;
            }
        }
    }

    private void collectAllIds(int laneId, AsyncInvocationState state, List<Long> target) {
        target.addAll(stateIndex.subMap(indexStart(laneId, state), true, indexEnd(laneId, state), true).values());
    }

    private QueueRecord requiredRecord(long id) {
        QueueRecord record = findRecord(id);
        if (record == null) {
            throw consistencyFailure("Index references missing async queue record " + id);
        }
        return record;
    }

    private QueueRecord findRecord(long id) {
        byte[] encoded = records.get(id);
        return encoded == null ? null : recordCodec.decode(encoded);
    }

    private MethodBufferEntryView toEntryView(QueueRecord record) {
        return new MethodBufferEntryView(
                Long.toString(record.id()),
                record.state().name(),
                record.payloadSummary(),
                Instant.ofEpochMilli(record.enqueuedAtEpochMillis()),
                record.attempts(),
                record.lastError(),
                record.lastErrorType(),
                record.errorCategory(),
                record.codecId(),
                record.payloadType(),
                record.payloadVersion(),
                record.interruptions()
        );
    }

    private void loadStatsCache() {
        laneStats.clear();
        laneKeysById.clear();
        dirtyLanes.clear();
        totalEntries = 0L;
        totalPayloadBytes = 0L;
        for (Map.Entry<Integer, byte[]> entry : persistedLaneStats.entrySet()) {
            LaneStats stats = LaneStats.decode(entry.getValue());
            laneStats.put(entry.getKey(), stats);
            String service = laneServices.get(entry.getKey());
            String method = laneMethods.get(entry.getKey());
            if (service != null && method != null) {
                laneKeysById.put(entry.getKey(), laneKey(service, method));
            }
            totalEntries += stats.entries;
            totalPayloadBytes += stats.payloadBytes;
        }
        republishAllCounts();
    }

    /** Rebuilds the lock-free counters from the in-memory cache, which matches the committed state here. */
    private void republishAllCounts() {
        Map<String, LaneCounts> fresh = new HashMap<>();
        for (Map.Entry<Integer, String> lane : laneKeysById.entrySet()) {
            LaneStats stats = laneStats.get(lane.getKey());
            if (stats != null) {
                fresh.put(lane.getValue(), stats.counts());
            }
        }
        committedCounts.putAll(fresh);
        committedCounts.keySet().retainAll(fresh.keySet());
    }

    private void publishCommittedCounts() {
        if (dirtyLanes.isEmpty()) {
            return;
        }
        for (Integer laneId : dirtyLanes) {
            String key = laneKeysById.get(laneId);
            LaneStats stats = laneStats.get(laneId);
            if (key != null && stats != null) {
                committedCounts.put(key, stats.counts());
            }
        }
        dirtyLanes.clear();
    }

    private void persistStats(int laneId, LaneStats stats) {
        persistedLaneStats.put(laneId, stats.encode());
        dirtyLanes.add(laneId);
    }

    private long nextMetadataValue(String key, long max, String description) {
        long current = metadata.getOrDefault(key, 0L);
        if (current >= max) {
            throw new IllegalStateException("Exhausted supported " + description + " range");
        }
        long next = current + 1;
        metadata.put(key, next);
        return next;
    }

    private void advanceProjectionVersion() {
        nextMetadataValue(META_PROJECTION_VERSION, Long.MAX_VALUE, "projection version");
    }

    private void commitOrFail(String message) {
        if (batching) {
            batchDirty = true;
            return;
        }
        try {
            db.commit();
            publishCommittedCounts();
        } catch (RuntimeException commitFailure) {
            RuntimeException failure = new AsyncAdmissionRejectedException(
                    AsyncAdmissionRejectedException.Reason.STORAGE_FAILURE,
                    message,
                    commitFailure
            );
            if (options.transactionsEnabled()) {
                try {
                    db.rollback();
                    loadStatsCache();
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            fatalFailure.compareAndSet(null, failure);
            throw failure;
        }
    }

    long executeBatch(List<BoundedStoreWriter.WriteCommand<?>> commands) {
        Objects.requireNonNull(commands, "commands");
        synchronized (monitor) {
            ensureWritable();
            if (batching) {
                throw new IllegalStateException("Nested MapDB write batches are not supported");
            }
            batching = true;
            batchDirty = false;
            try {
                for (BoundedStoreWriter.WriteCommand<?> command : commands) {
                    command.apply();
                }
                boolean dirty = batchDirty;
                batching = false;
                batchDirty = false;
                if (dirty) {
                    long commitStarted = System.nanoTime();
                    commitOrFail("Could not commit MapDB async queue write batch");
                    return Math.max(1L, System.nanoTime() - commitStarted);
                }
                return 0L;
            } catch (Throwable failure) {
                batching = false;
                batchDirty = false;
                RuntimeException runtimeFailure = failure instanceof RuntimeException runtimeException
                        ? runtimeException
                        : new IllegalStateException("MapDB async queue write batch failed", failure);
                if (options.transactionsEnabled() && fatalFailure.get() == null) {
                    try {
                        db.rollback();
                        loadStatsCache();
                    } catch (RuntimeException rollbackFailure) {
                        runtimeFailure.addSuppressed(rollbackFailure);
                    }
                }
                fatalFailure.compareAndSet(null, runtimeFailure);
                throw runtimeFailure;
            }
        }
    }

    private void ensureAccepting() {
        ensureWritable();
        if (!accepting.get()) {
            throw rejection(AsyncAdmissionRejectedException.Reason.ENGINE_CLOSED, "Async queue store is stopping");
        }
    }

    private void ensureWritable() {
        ensureReadable();
    }

    private void ensureReadable() {
        RuntimeException failure = fatalFailure.get();
        if (failure != null) {
            throw failure;
        }
    }

    private static boolean ownsLease(QueueRecord record, long leaseToken) {
        return record != null
                && record.state() == AsyncInvocationState.IN_PROGRESS
                && record.leaseToken() == leaseToken;
    }

    private static long indexKey(int laneId, AsyncInvocationState state, long id) {
        if (laneId <= 0 || laneId > MAX_LANE_ID) {
            throw consistencyFailure("laneId is outside supported range: " + laneId);
        }
        if (id < 0 || id > ID_MASK) {
            throw consistencyFailure("id is outside supported range: " + id);
        }
        return ((long) laneId << LANE_SHIFT) | ((long) state.ordinal() << STATE_SHIFT) | id;
    }

    private static long indexStart(int laneId, AsyncInvocationState state) {
        return indexKey(laneId, state, 0L);
    }

    private static long indexEnd(int laneId, AsyncInvocationState state) {
        return indexKey(laneId, state, ID_MASK);
    }

    private static String retryKey(long retryAtEpochMillis, long invocationId) {
        return String.format(java.util.Locale.ROOT, "%019d:%020d", retryAtEpochMillis, invocationId);
    }

    private static long retryEpochMillis(String retryKey) {
        return Long.parseLong(retryKey.substring(0, 19));
    }

    private static String laneKey(String serviceId, String methodId) {
        return serviceId + LANE_SEPARATOR + methodId;
    }

    private static String idempotencyIndexKey(int laneId, String idempotencyKey) {
        return laneId + String.valueOf(LANE_SEPARATOR) + idempotencyKey;
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey.length() > options.maxIdempotencyKeyCharacters()) {
            throw new IllegalArgumentException(
                    "idempotencyKey exceeds " + options.maxIdempotencyKeyCharacters() + " characters"
            );
        }
        if (idempotencyKey.indexOf(LANE_SEPARATOR) >= 0) {
            throw new IllegalArgumentException("idempotencyKey must not contain NUL");
        }
    }

    private int purgeExpiredIdempotency(long nowEpochMillis, int limit) {
        int removed = 0;
        while (removed < limit) {
            Map.Entry<String, String> first = idempotencyExpiryIndex.firstEntry();
            if (first == null || idempotencyExpiryEpochMillis(first.getKey()) > nowEpochMillis) {
                break;
            }
            idempotencyExpiryIndex.remove(first.getKey());
            String indexKey = first.getValue();
            IdempotencyTombstone tombstone = decodeTombstone(idempotencyTombstones.get(indexKey));
            if (tombstone != null && tombstone.expiresAtEpochMillis() <= nowEpochMillis) {
                idempotencyTombstones.remove(indexKey);
                removeIdempotencyIndexEntry(indexKey);
            }
            removed++;
        }
        if (removed > 0) {
            commitOrFail("Could not purge expired idempotency records");
        }
        return removed;
    }

    /**
     * Idempotency entries are counted in persisted metadata because {@code HTreeMap.size()} walks the
     * whole map. The counter lives in the same transaction as the index, so rollback keeps them equal.
     */
    private long idempotencyEntryCount() {
        return metadata.getOrDefault(META_IDEMPOTENCY_ENTRIES, 0L);
    }

    private void initializeIdempotencyCounter() {
        if (!metadata.containsKey(META_IDEMPOTENCY_ENTRIES)) {
            // One-time migration for stores created before the counter existed.
            metadata.put(META_IDEMPOTENCY_ENTRIES, (long) idempotencyIndex.size());
        }
    }

    private void putIdempotencyEntry(String indexKey, long invocationId) {
        if (idempotencyIndex.put(indexKey, invocationId) == null) {
            metadata.put(META_IDEMPOTENCY_ENTRIES, idempotencyEntryCount() + 1L);
        }
    }

    private void removeIdempotencyIndexEntry(String indexKey) {
        if (idempotencyIndex.remove(indexKey) != null) {
            metadata.put(META_IDEMPOTENCY_ENTRIES, Math.max(0L, idempotencyEntryCount() - 1L));
        }
    }

    /** Test and diagnostics hook: persisted counter value. */
    long idempotencyEntries() {
        synchronized (monitor) {
            return idempotencyEntryCount();
        }
    }

    private void removeIdempotencyEntry(String indexKey, IdempotencyTombstone tombstone) {
        removeIdempotencyIndexEntry(indexKey);
        idempotencyTombstones.remove(indexKey);
        if (tombstone != null) {
            idempotencyExpiryIndex.remove(idempotencyExpiryKey(tombstone.expiresAtEpochMillis(), indexKey));
        }
    }

    private static byte[] encodeTombstone(long invocationId, long expiresAtEpochMillis) {
        return ByteBuffer.allocate(Long.BYTES * 2)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(invocationId)
                .putLong(expiresAtEpochMillis)
                .array();
    }

    private static IdempotencyTombstone decodeTombstone(byte[] encoded) {
        if (encoded == null) {
            return null;
        }
        if (encoded.length != Long.BYTES * 2) {
            throw consistencyFailure("invalid idempotency tombstone record");
        }
        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        return new IdempotencyTombstone(buffer.getLong(), buffer.getLong());
    }

    private static String idempotencyExpiryKey(long expiresAtEpochMillis, String indexKey) {
        return String.format(java.util.Locale.ROOT, "%019d%c%s", expiresAtEpochMillis, LANE_SEPARATOR, indexKey);
    }

    private static long idempotencyExpiryEpochMillis(String expiryKey) {
        return Long.parseLong(expiryKey.substring(0, 19));
    }

    private static void validateLanePart(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.indexOf(LANE_SEPARATOR) >= 0) {
            throw new IllegalArgumentException(name + " must not contain NUL");
        }
    }

    private static AsyncAdmissionRejectedException rejection(
            AsyncAdmissionRejectedException.Reason reason,
            String message
    ) {
        return new AsyncAdmissionRejectedException(reason, message);
    }

    private static IllegalStateException consistencyFailure(String message) {
        return new IllegalStateException("Async queue store consistency failure: " + message);
    }

    private static final class LaneStats {
        private long pending;
        private long inProgress;
        private long retryWait;
        private long failed;
        private long entries;
        private long payloadBytes;
        private long version;

        private LaneCounts counts() {
            return new LaneCounts(pending, inProgress, retryWait, failed);
        }

        private void add(AsyncInvocationState state, long payloadLength) {
            increment(state, 1L);
            entries++;
            payloadBytes += payloadLength;
            version++;
        }

        private void remove(AsyncInvocationState state, long payloadLength) {
            increment(state, -1L);
            entries--;
            payloadBytes -= payloadLength;
            version++;
            verifyNonNegative();
        }

        private void transition(AsyncInvocationState from, AsyncInvocationState to) {
            increment(from, -1L);
            increment(to, 1L);
            version++;
            verifyNonNegative();
        }

        private void increment(AsyncInvocationState state, long delta) {
            switch (state) {
                case PENDING -> pending += delta;
                case IN_PROGRESS -> inProgress += delta;
                case RETRY_WAIT -> retryWait += delta;
                case FAILED -> failed += delta;
            }
        }

        private void verifyNonNegative() {
            if (pending < 0 || inProgress < 0 || retryWait < 0 || failed < 0 || entries < 0 || payloadBytes < 0) {
                throw consistencyFailure("negative lane counter detected");
            }
        }

        private byte[] encode() {
            return ByteBuffer.allocate(Long.BYTES * 7)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putLong(pending)
                    .putLong(inProgress)
                    .putLong(retryWait)
                    .putLong(failed)
                    .putLong(entries)
                    .putLong(payloadBytes)
                    .putLong(version)
                    .array();
        }

        private static LaneStats decode(byte[] encoded) {
            if (encoded == null || encoded.length != Long.BYTES * 7) {
                throw consistencyFailure("invalid lane counter record");
            }
            ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
            LaneStats stats = new LaneStats();
            stats.pending = buffer.getLong();
            stats.inProgress = buffer.getLong();
            stats.retryWait = buffer.getLong();
            stats.failed = buffer.getLong();
            stats.entries = buffer.getLong();
            stats.payloadBytes = buffer.getLong();
            stats.version = buffer.getLong();
            stats.verifyNonNegative();
            return stats;
        }
    }

    private record LaneCounts(long pending, long inProgress, long retryWait, long failed) {
    }

    private record IdempotencyTombstone(long invocationId, long expiresAtEpochMillis) {
    }
}
