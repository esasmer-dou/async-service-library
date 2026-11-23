package com.reactor.asl.admin.server;

import com.reactor.asl.admin.protocol.WireReader;
import com.reactor.asl.admin.protocol.WireWriter;
import org.mapdb.Atomic;
import org.mapdb.BTreeMap;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.HTreeMap;
import org.mapdb.Serializer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything the admin server keeps, in one MapDB database: application-wide overrides, the audit
 * trail and the telemetry history. No payload ever reaches it: telemetry is the per-method counter
 * set a replica sends with its heartbeat, stored as received.
 *
 * <p>History is a fixed ring per series ({@code retention / resolution} slots, overwritten in
 * place), so its size is bounded by {@code max-series x slots} and does not grow over time. With
 * {@code storage.mode=MEMORY} the same structures live on the heap and nothing touches the disk.</p>
 */
public final class AdminStore implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(AdminStore.class.getName());
    /** MapDB allocates its file in 1 MB pages; an empty store already occupies two of them. */
    public static final long MIN_FILE_BYTES = 2L * 1024 * 1024;
    private static final char SEP = '\u001f';

    private final Path path;
    private final long maxSizeBytes;
    private final DB db;
    private final HTreeMap<String, String> overrides;
    /** Per application, one line per timed application-wide override: service, method, then {@link OverrideTimer#toText()}. */
    private final HTreeMap<String, String> overrideTimers;
    private final Atomic.Long version;
    private final BTreeMap<Long, byte[]> audit;
    private final Atomic.Long auditSequence;
    private final HTreeMap<String, Long> seriesIds;
    private final HTreeMap<Long, Long> seriesLastBucket;
    private final HTreeMap<Long, byte[]> points;
    private final Atomic.Long seriesSequence;
    /** In-memory view of the series index, keyed without building strings on the hot write path. */
    private final Map<SeriesRef, Long> seriesCache = new ConcurrentHashMap<>();
    private final int slots;
    private final int maxSeries;
    private final int maxAuditEntries;
    private volatile boolean seriesLimitReached;
    private volatile boolean sizeLimitReached;

    public record AuditEntry(long timeMillis, String actor, String application, String action, String detail) {
    }

    /** One stored telemetry point: the bucket start and the raw values as the replica sent them. */
    public record Point(long timeMillis, long[] values) {
    }

    public record Series(String instance, List<Point> points) {
    }

    public AdminStore(ServerConfig config) {
        this.maxAuditEntries = config.auditEnabled ? config.auditMaxEntries : 0;
        this.slots = config.historyEnabled ? config.historySlots() : 0;
        this.maxSeries = config.historyMaxSeries;
        if (config.storageMode == ServerConfig.StorageMode.FILE) {
            this.path = config.storagePath.toAbsolutePath();
            this.maxSizeBytes = config.storageMaxBytes;
            try {
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            this.db = DBMaker.fileDB(path.toFile()).transactionEnable().closeOnJvmShutdown().make();
        } else {
            this.path = null;
            this.maxSizeBytes = 0;
            this.db = DBMaker.heapDB().make();
        }
        this.overrides = db.hashMap("overrides", Serializer.STRING, Serializer.STRING).createOrOpen();
        this.overrideTimers = db.hashMap("overrideTimers", Serializer.STRING, Serializer.STRING).createOrOpen();
        this.version = db.atomicLong("version").createOrOpen();
        this.audit = db.treeMap("audit", Serializer.LONG, Serializer.BYTE_ARRAY).createOrOpen();
        this.auditSequence = db.atomicLong("auditSequence").createOrOpen();
        this.seriesIds = db.hashMap("historySeries", Serializer.STRING, Serializer.LONG).createOrOpen();
        this.seriesLastBucket = db.hashMap("historyLastBucket", Serializer.LONG, Serializer.LONG).createOrOpen();
        this.points = db.hashMap("historyPoints", Serializer.LONG, Serializer.BYTE_ARRAY).createOrOpen();
        this.seriesSequence = db.atomicLong("historySeriesSequence").createOrOpen();
        Atomic.Long storedLayout = db.atomicLong("historyLayout").createOrOpen();
        long layout = ((long) slots << 32) | (config.historyResolution.toSeconds() & 0xffffffffL);
        if (storedLayout.get() != layout) {
            // Slot count or resolution changed: old points no longer line up with the ring.
            seriesIds.clear();
            seriesLastBucket.clear();
            points.clear();
            storedLayout.set(layout);
        }
        for (Map.Entry<String, Long> entry : seriesIds.getEntries()) {
            String[] parts = entry.getKey().split(String.valueOf(SEP), 4);
            if (parts.length == 4) {
                seriesCache.put(new SeriesRef(parts[0], parts[1], new MethodKey(parts[2], parts[3])), entry.getValue());
            }
        }
        commit();
    }

    // ---- overrides --------------------------------------------------------------------------

    public synchronized long loadVersion() {
        return version.get();
    }

    public synchronized Map<String, Map<MethodKey, MethodSetting>> loadOverrides() {
        Map<String, Map<MethodKey, MethodSetting>> result = new HashMap<>();
        for (Map.Entry<String, String> entry : overrides.getEntries()) {
            Map<MethodKey, MethodSetting> settings = new LinkedHashMap<>();
            for (String line : entry.getValue().split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\t", 3);
                if (parts.length != 3) {
                    throw new IllegalStateException("Invalid override entry for " + entry.getKey() + ": " + line);
                }
                settings.put(new MethodKey(parts[0], parts[1]), MethodSetting.parse(parts[2]));
            }
            result.put(entry.getKey(), settings);
        }
        return result;
    }

    public synchronized void saveOverrides(String application, Map<MethodKey, MethodSetting> settings, long newVersion) {
        writeOverrides(application, settings);
        version.set(Math.max(version.get(), newVersion));
        commit();
    }

    /** Overrides and their timers in one commit, so a restart never sees one without the other. */
    public synchronized void saveOverrides(String application, Map<MethodKey, MethodSetting> settings,
                                           Map<MethodKey, OverrideTimer> timers, long newVersion) {
        writeOverrides(application, settings);
        if (timers.isEmpty()) {
            overrideTimers.remove(application);
        } else {
            List<String> lines = new ArrayList<>();
            timers.forEach((key, timer) -> lines.add(plain(key.serviceId()) + "\t" + plain(key.methodId()) + "\t" + timer.toText()));
            lines.sort(null);
            overrideTimers.put(application, String.join("\n", lines));
        }
        version.set(Math.max(version.get(), newVersion));
        commit();
    }

    public synchronized Map<String, Map<MethodKey, OverrideTimer>> loadOverrideTimers() {
        Map<String, Map<MethodKey, OverrideTimer>> result = new HashMap<>();
        for (Map.Entry<String, String> entry : overrideTimers.getEntries()) {
            Map<MethodKey, OverrideTimer> timers = new LinkedHashMap<>();
            for (String line : entry.getValue().split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\t", 3);
                if (parts.length != 3) {
                    throw new IllegalStateException("Invalid override timer for " + entry.getKey() + ": " + line);
                }
                timers.put(new MethodKey(parts[0], parts[1]), OverrideTimer.parse(parts[2]));
            }
            result.put(entry.getKey(), timers);
        }
        return result;
    }

    private void writeOverrides(String application, Map<MethodKey, MethodSetting> settings) {
        if (settings.isEmpty()) {
            overrides.remove(application);
        } else {
            List<String> lines = new ArrayList<>();
            settings.forEach((key, setting) -> lines.add(plain(key.serviceId()) + "\t" + plain(key.methodId()) + "\t" + setting.toText()));
            lines.sort(null);
            overrides.put(application, String.join("\n", lines));
        }
    }

    // ---- audit ------------------------------------------------------------------------------

    public synchronized void appendAudit(AuditEntry entry) {
        if (maxAuditEntries == 0) {
            return;
        }
        byte[] value = new WireWriter(96).svar(entry.timeMillis()).str(clip(entry.actor())).str(clip(entry.application()))
                .str(clip(entry.action())).str(clip(entry.detail())).toByteArray();
        audit.put(auditSequence.incrementAndGet(), value);
        while (audit.size() > maxAuditEntries) {
            audit.pollFirstEntry();
        }
        commit();
    }

    /** Newest first. */
    public synchronized List<AuditEntry> recentAudit(int limit) {
        List<AuditEntry> result = new ArrayList<>();
        for (byte[] value : audit.descendingMap().values()) {
            if (result.size() >= limit) {
                break;
            }
            WireReader in = new WireReader(value);
            result.add(new AuditEntry(in.svar(), in.str(), in.str(), in.str(), in.str()));
        }
        return result;
    }

    public synchronized void trimAudit(long olderThanMillis) {
        boolean changed = false;
        while (!audit.isEmpty() && new WireReader(audit.firstEntry().getValue()).svar() < olderThanMillis) {
            audit.pollFirstEntry();
            changed = true;
        }
        if (changed) {
            commit();
        }
    }

    // ---- telemetry history ------------------------------------------------------------------

    public boolean historyEnabled() {
        return slots > 0;
    }

    /** Writes one point for every sample of this step in a single commit; samples are stored as received. */
    public synchronized void recordHistory(long bucket, long timeMillisOfBucket, List<HistorySample> samples) {
        if (slots == 0) {
            return;
        }
        int slot = (int) Math.floorMod(bucket, (long) slots);
        boolean full = sizeLimitReached || (path != null && maxSizeBytes > 0 && sizeBytes() > maxSizeBytes);
        if (full && !sizeLimitReached) {
            LOG.log(System.Logger.Level.WARNING, "ASL admin store reached storage.max-size; no new history series are created");
        }
        sizeLimitReached = full;
        for (HistorySample sample : samples) {
            SeriesRef ref = new SeriesRef(sample.application(), sample.instance(), sample.method());
            Long id = seriesCache.get(ref);
            if (id == null) {
                if (full || seriesCache.size() >= maxSeries) {
                    if (!seriesLimitReached && !full) {
                        LOG.log(System.Logger.Level.WARNING, "ASL admin history reached history.max-series={0}; new series are skipped", maxSeries);
                    }
                    seriesLimitReached = seriesCache.size() >= maxSeries;
                    continue;
                }
                id = seriesSequence.incrementAndGet();
                seriesIds.put(ref.storageKey(), id);
                seriesCache.put(ref, id);
            }
            WireWriter out = new WireWriter(48).uvar(Math.max(0, bucket));
            for (long value : sample.values()) {
                out.uvar(Math.max(0, value));
            }
            points.put(id * slots + slot, out.toByteArray());
            seriesLastBucket.put(id, bucket);
        }
        removeExpiredSeries(bucket);
        commit();
    }

    /** True when a point for this pair would be stored: the series exists or there is room for a new one. */
    public boolean acceptsSeries(String application, String instance, MethodKey method) {
        if (seriesCache.containsKey(new SeriesRef(application, instance, method))) {
            return true;
        }
        return !sizeLimitReached && seriesCache.size() < maxSeries;
    }

    /** Points of one method, one series per replica, oldest first. */
    public synchronized List<Series> history(String application, MethodKey method, long resolutionMillis) {
        List<Series> result = new ArrayList<>();
        if (slots == 0) {
            return result;
        }
        for (Map.Entry<SeriesRef, Long> entry : seriesCache.entrySet()) {
            SeriesRef ref = entry.getKey();
            if (!ref.application().equals(application) || !ref.method().equals(method)) {
                continue;
            }
            String instance = ref.instance();
            long id = entry.getValue();
            Long last = seriesLastBucket.get(id);
            if (last == null) {
                continue;
            }
            List<Point> series = new ArrayList<>();
            for (long bucket = last - slots + 1; bucket <= last; bucket++) {
                byte[] stored = points.get(id * slots + Math.floorMod(bucket, (long) slots));
                if (stored == null) {
                    continue;
                }
                WireReader in = new WireReader(stored);
                if (in.uvar() != bucket) {
                    continue;
                }
                long[] values = new long[(int) Math.min(64, in.remaining())];
                int count = 0;
                while (in.remaining() > 0) {
                    values[count++] = in.uvar();
                }
                series.add(new Point(bucket * resolutionMillis, java.util.Arrays.copyOf(values, count)));
            }
            result.add(new Series(instance, series));
        }
        result.sort(java.util.Comparator.comparing(Series::instance));
        return result;
    }

    public int seriesCount() {
        return seriesCache.size();
    }

    public int slots() {
        return slots;
    }

    public boolean seriesLimitReached() {
        return seriesLimitReached;
    }

    public long sizeBytes() {
        if (path == null) {
            return 0;
        }
        long total = 0;
        try (var files = Files.list(path.getParent())) {
            for (Path file : files.toList()) {
                if (file.getFileName().toString().startsWith(path.getFileName().toString())) {
                    total += Files.size(file);
                }
            }
        } catch (IOException exception) {
            return -1;
        }
        return total;
    }

    public boolean persistent() {
        return path != null;
    }

    @Override
    public synchronized void close() {
        if (!db.isClosed()) {
            db.close();
        }
    }

    public record HistorySample(String application, String instance, MethodKey method, long[] values) {
    }

    private record SeriesRef(String application, String instance, MethodKey method) {
        String storageKey() {
            return application + SEP + instance + SEP + method.serviceId() + SEP + method.methodId();
        }
    }

    private void removeExpiredSeries(long bucket) {
        List<SeriesRef> expired = new ArrayList<>();
        for (Map.Entry<SeriesRef, Long> entry : seriesCache.entrySet()) {
            Long last = seriesLastBucket.get(entry.getValue());
            if (last == null || bucket - last >= slots) {
                expired.add(entry.getKey());
            }
        }
        for (SeriesRef key : expired) {
            Long id = seriesCache.remove(key);
            seriesIds.remove(key.storageKey());
            if (id != null) {
                seriesLastBucket.remove(id);
                for (int slot = 0; slot < slots; slot++) {
                    points.remove(id * slots + slot);
                }
            }
        }
        if (!expired.isEmpty()) {
            seriesLimitReached = seriesCache.size() >= maxSeries;
        }
    }

    private void commit() {
        if (path != null) {
            db.commit();
        }
    }

    private static String plain(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < 0x20) {
                throw new IllegalArgumentException("Names must not contain control characters");
            }
        }
        return value;
    }

    private static String clip(String value) {
        return value == null || value.length() <= 1_024 ? value : value.substring(0, 1_024);
    }
}
