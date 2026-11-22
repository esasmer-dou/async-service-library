package com.reactor.asl.admin.server;

import com.reactor.asl.admin.protocol.AslAdminMessages.Ack;
import com.reactor.asl.admin.protocol.AslAdminMessages.Beat;
import com.reactor.asl.admin.protocol.AslAdminMessages.BeatReply;
import com.reactor.asl.admin.protocol.AslAdminMessages.Command;
import com.reactor.asl.admin.protocol.AslAdminMessages.ConfigBlock;
import com.reactor.asl.admin.protocol.AslAdminMessages.MethodInfo;
import com.reactor.asl.admin.protocol.AslAdminMessages.MethodOverride;
import com.reactor.asl.admin.protocol.AslAdminMessages.MethodSample;
import com.reactor.asl.admin.protocol.AslAdminMessages.QueueEntry;
import com.reactor.asl.admin.protocol.AslAdminMessages.Register;
import com.reactor.asl.admin.protocol.AslAdminMessages.RegisterReply;
import com.reactor.asl.admin.protocol.AslProtocolException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Relay state: which replicas are connected, their latest raw values, the overrides typed in the
 * UI and the commands waiting to be picked up. It does not compute anything: overrides are
 * relayed as stored (the pod overlays instance-scoped on top of application-wide ones) and the
 * browser does all totals and trends. Only application-wide overrides reach the disk.
 */
public final class InstanceRegistry {
    private static final String[] COMMAND_NAMES = {"", "CLEAR_QUEUE", "REPLAY_ENTRY", "DELETE_ENTRY", "BROWSE_QUEUE", "RESET_OBSERVATIONS"};

    private final ServerConfig config;
    private final AdminStore store;
    private final Map<Long, InstanceState> sessions = new ConcurrentHashMap<>();
    private final Map<String, AppState> apps = new ConcurrentHashMap<>();
    private final AtomicLong version;
    private final AtomicLong commandIds = new AtomicLong();
    /** Replicas of one application send the same names; one shared copy keeps the heap flat as pods are added. */
    private final Map<Object, Object> canonical = new ConcurrentHashMap<>();
    private static final int MAX_CANONICAL = 200_000;
    private final long intervalMillis;
    private final long watchIntervalMillis;
    private final long watchIdleMillis;
    private final long expireAfterMillis;
    private final long commandTtlMillis;

    public InstanceRegistry(ServerConfig config, AdminStore store, long nowMillis) {
        this.config = Objects.requireNonNull(config, "config");
        this.store = Objects.requireNonNull(store, "store");
        this.intervalMillis = config.interval.toMillis();
        this.watchIntervalMillis = Math.min(config.watchInterval.toMillis(), intervalMillis);
        this.watchIdleMillis = config.watchIdleTimeout.toMillis();
        this.expireAfterMillis = config.expireAfter.toMillis();
        this.commandTtlMillis = config.commandTtl.toMillis();
        long stored = store.loadVersion();
        this.version = new AtomicLong(Math.max(stored, nowMillis / 1000));
        for (Map.Entry<String, Map<MethodKey, MethodSetting>> entry : store.loadOverrides().entrySet()) {
            AppState app = new AppState(entry.getKey(), config.maxCommandResults);
            app.overrides.putAll(entry.getValue());
            app.version = Math.max(stored, 1);
            apps.put(app.name, app);
        }
    }

    // ---- protocol side ----------------------------------------------------------------------

    public RegisterReply register(Register message, long now) {
        requirePlain(message.application());
        requirePlain(message.instance());
        for (MethodInfo method : message.methods()) {
            requirePlain(method.serviceId());
            requirePlain(method.methodId());
        }
        AppState app = apps.computeIfAbsent(message.application(), name -> new AppState(name, config.maxCommandResults));
        synchronized (app) {
            InstanceState previous = app.instances.get(message.instance());
            if (previous == null && sessions.size() >= config.maxInstances) {
                throw new CapacityExceededException("Instance limit reached: " + config.maxInstances);
            }
            if (previous != null) {
                drop(app, previous, now, "instance re-registered", true);
            }
            long sessionId;
            do {
                sessionId = 1 + ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE - 1);
            } while (sessions.containsKey(sessionId));
            List<MethodInfo> catalog = new ArrayList<>(message.methods().size());
            for (MethodInfo method : message.methods()) {
                catalog.add(canonical(new MethodInfo(canonical(method.serviceId()), canonical(method.methodId()),
                        method.asyncCapable(), method.hasQueue())));
            }
            InstanceState instance = new InstanceState(sessionId, app.name, message.instance(), canonical(message.libraryVersion()), catalog, now, this);
            for (int i = 0; i < catalog.size(); i++) {
                app.known.putIfAbsent(instance.keys[i], catalog.get(i));
            }
            app.instances.put(instance.name, instance);
            sessions.put(sessionId, instance);
            long interval = desiredInterval(app, now);
            instance.lastIntervalSent = interval;
            return new RegisterReply(sessionId, interval, app.version > 0 ? configBlock(app, instance) : null);
        }
    }

    /** Returns {@code null} when the session is unknown; the caller answers 410. */
    public BeatReply beat(Beat beat, long now) {
        InstanceState instance = sessions.get(beat.sessionId());
        if (instance == null) {
            return null;
        }
        AppState app = apps.get(instance.application);
        if (app == null) {
            return null;
        }
        synchronized (app) {
            if (sessions.get(beat.sessionId()) != instance) {
                return null;
            }
            instance.lastSeen = now;
            if (beat.leaving()) {
                drop(app, instance, now, "instance stopped", false);
                return BeatReply.empty();
            }
            boolean sendFull = !beat.full() && beat.seq() != instance.lastSeq + 1;
            instance.lastSeq = beat.seq();
            for (MethodSample sample : beat.samples()) {
                int index = sample.methodIndex();
                if (index < 0 || index >= instance.catalog.size()) {
                    sendFull = true;
                    continue;
                }
                long[] values = instance.values[index];
                for (int field = 0; field < MethodSample.FIELD_COUNT; field++) {
                    if (sample.has(field)) {
                        values[field] = sample.value(field);
                    }
                }
                instance.seen[index] = true;
            }
            if (beat.appliedConfigVersion() != instance.appliedVersion) {
                instance.appliedVersion = beat.appliedConfigVersion();
                instance.configError = beat.configError();
            } else if (beat.configError() != null) {
                instance.configError = beat.configError();
            }
            for (Ack ack : beat.acks()) {
                PendingCommand pending = instance.pending.remove(ack.commandId());
                if (pending != null) {
                    app.addResult(new CommandResult(pending, instance.name, status(ack.status()), ack.value(), ack.message(), ack.entries(), now));
                }
            }
            ConfigBlock block = app.version > 0 && instance.appliedVersion != app.version ? configBlock(app, instance) : null;
            long interval = desiredInterval(app, now);
            long intervalHint = 0;
            if (interval != instance.lastIntervalSent) {
                intervalHint = interval;
                instance.lastIntervalSent = interval;
            }
            List<Command> commands = new ArrayList<>();
            long resendAfter = Math.max(2 * instance.lastIntervalSent, 5_000L);
            for (PendingCommand pending : instance.pending.values()) {
                if (pending.lastSentAt == 0 || now - pending.lastSentAt >= resendAfter) {
                    pending.lastSentAt = now;
                    commands.add(new Command(pending.id, pending.type, pending.methodIndex, pending.entryId, pending.limit));
                }
            }
            return new BeatReply(sendFull, intervalHint, block, commands);
        }
    }

    // ---- UI side ----------------------------------------------------------------------------

    /**
     * Stores the override of one method for the whole application, or for one replica when
     * {@code instanceName} is given (kept in memory, gone with the replica). An empty setting
     * removes it, so the method returns to the application's own value.
     */
    public void setOverride(String application, MethodKey key, String instanceName, MethodSetting setting, String actor, long now) {
        AppState app = requireApp(application);
        boolean appWide = instanceName == null || instanceName.isBlank();
        synchronized (app) {
            MethodInfo method = app.known.get(key);
            if (method == null) {
                throw new IllegalArgumentException("Unknown method " + key.serviceId() + "#" + key.methodId());
            }
            if (Boolean.TRUE.equals(setting.async()) && !method.asyncCapable()) {
                throw new IllegalArgumentException("Method is not async-capable");
            }
            if (appWide) {
                if (setting.isEmpty()) {
                    app.overrides.remove(key);
                } else {
                    app.overrides.put(key, setting);
                }
            } else {
                if (!app.instances.containsKey(instanceName)) {
                    throw new IllegalArgumentException("Unknown instance " + instanceName);
                }
                Map<MethodKey, MethodSetting> perInstance = app.instanceOverrides.computeIfAbsent(instanceName, name -> new HashMap<>());
                if (setting.isEmpty()) {
                    perInstance.remove(key);
                    if (perInstance.isEmpty()) {
                        app.instanceOverrides.remove(instanceName);
                    }
                } else {
                    perInstance.put(key, setting);
                }
            }
            app.version = version.incrementAndGet();
            if (appWide) {
                store.saveOverrides(app.name, Map.copyOf(app.overrides), app.version);
            }
        }
        if (config.auditEnabled) {
            store.appendAudit(new AdminStore.AuditEntry(now, actor, application, setting.isEmpty() ? "override.clear" : "override.set",
                    key.serviceId() + "#" + key.methodId() + (appWide ? "" : " @" + instanceName)
                            + (setting.isEmpty() ? "" : " " + setting.toText())));
        }
    }

    /** Queues a command for one replica, or every replica that has the method when {@code instanceName} is "*". */
    public List<Long> enqueueCommand(String application, String instanceName, MethodKey key, int type, String entryId, int limit, String actor, long now) {
        if (type < Command.CLEAR_QUEUE || type > Command.RESET_OBSERVATIONS) {
            throw new IllegalArgumentException("Unknown command type " + type);
        }
        if ((type == Command.REPLAY_ENTRY || type == Command.DELETE_ENTRY) && (entryId == null || entryId.isBlank())) {
            throw new IllegalArgumentException("entryId is required");
        }
        AppState app = requireApp(application);
        List<Long> ids = new ArrayList<>();
        synchronized (app) {
            List<InstanceState> targets = new ArrayList<>();
            if ("*".equals(instanceName)) {
                targets.addAll(app.instances.values());
            } else {
                InstanceState target = app.instances.get(instanceName);
                if (target == null) {
                    throw new IllegalArgumentException("Unknown instance " + instanceName);
                }
                targets.add(target);
            }
            for (InstanceState target : targets) {
                Integer index = target.index.get(key);
                if (index == null) {
                    continue;
                }
                if (target.pending.size() >= config.maxPendingCommands) {
                    throw new CapacityExceededException("Too many open commands for " + target.name);
                }
                long id = commandIds.incrementAndGet();
                target.pending.put(id, new PendingCommand(id, type, index, key, entryId, Math.max(0, limit), now, actor));
                ids.add(id);
            }
            if (ids.isEmpty()) {
                throw new IllegalArgumentException("No instance has method " + key.serviceId() + "#" + key.methodId());
            }
        }
        if (config.auditEnabled && type != Command.BROWSE_QUEUE) {
            store.appendAudit(new AdminStore.AuditEntry(now, actor, application, "command." + COMMAND_NAMES[type],
                    key.serviceId() + "#" + key.methodId() + " @" + instanceName + (entryId == null ? "" : " entry=" + entryId)));
        }
        return ids;
    }

    /** Called while the UI shows an application: its replicas switch to the watch interval. */
    public void watch(String application, long now) {
        AppState app = apps.get(application);
        if (app != null) {
            synchronized (app) {
                app.watchedUntil = now + watchIdleMillis;
            }
        }
    }

    /** Forgets silent replicas and expires unanswered commands. */
    public void maintain(long now) {
        for (AppState app : apps.values()) {
            synchronized (app) {
                for (InstanceState instance : List.copyOf(app.instances.values())) {
                    if (now - instance.lastSeen > expireAfterMillis) {
                        drop(app, instance, now, "instance expired", false);
                        continue;
                    }
                    Iterator<PendingCommand> iterator = instance.pending.values().iterator();
                    while (iterator.hasNext()) {
                        PendingCommand pending = iterator.next();
                        if (now - pending.createdAt > commandTtlMillis) {
                            iterator.remove();
                            app.addResult(new CommandResult(pending, instance.name, "EXPIRED", 0, "no answer within ttl", List.of(), now));
                        }
                    }
                }
                if (app.instances.isEmpty() && app.overrides.isEmpty()) {
                    apps.remove(app.name, app);
                }
            }
        }
    }

    public void writeApplications(Json json) {
        List<AppState> ordered = new ArrayList<>(apps.values());
        ordered.sort(Comparator.comparing(app -> app.name));
        json.beginArray();
        for (AppState app : ordered) {
            synchronized (app) {
                int outOfSync = 0;
                int errors = 0;
                long failed = 0;
                for (InstanceState instance : app.instances.values()) {
                    outOfSync += app.version > 0 && instance.appliedVersion != app.version ? 1 : 0;
                    errors += instance.configError != null ? 1 : 0;
                    for (long[] values : instance.values) {
                        failed += values[MethodSample.QUEUE_FAILED];
                    }
                }
                json.beginObject().field("name", app.name).field("instances", app.instances.size()).field("methods", app.known.size())
                        .field("outOfSync", outOfSync).field("configErrors", errors).field("queueFailed", failed).endObject();
            }
        }
        json.endArray();
    }

    /** Raw values for the browser: methods in a fixed order, every replica's samples aligned to it. */
    public void writeApplication(Json json, String application, long now) {
        AppState app = requireApp(application);
        synchronized (app) {
            Map<MethodKey, MethodInfo> known = new TreeMap<>(Comparator.comparing(MethodKey::serviceId).thenComparing(MethodKey::methodId));
            known.putAll(app.known);
            List<MethodKey> order = new ArrayList<>(known.keySet());
            json.beginObject().field("name", app.name).field("configVersion", app.version).field("intervalMillis", desiredInterval(app, now));
            json.name("methods").beginArray();
            for (MethodKey key : order) {
                MethodInfo info = known.get(key);
                json.beginObject().field("serviceId", key.serviceId()).field("methodId", key.methodId())
                        .field("asyncCapable", info.asyncCapable()).field("hasQueue", info.hasQueue())
                        .setting("override", app.overrides.get(key)).endObject();
            }
            json.endArray();
            List<InstanceState> instances = new ArrayList<>(app.instances.values());
            instances.sort(Comparator.comparing(state -> state.name));
            json.name("instances").beginArray();
            for (InstanceState instance : instances) {
                Map<MethodKey, MethodSetting> own = app.instanceOverrides.getOrDefault(instance.name, Map.of());
                json.beginObject().field("name", instance.name).field("libraryVersion", instance.libraryVersion)
                        .field("lastSeenMillisAgo", now - instance.lastSeen).field("uptimeMillis", now - instance.registeredAt)
                        .field("appliedConfigVersion", instance.appliedVersion)
                        .field("inSync", app.version == 0 || instance.appliedVersion == app.version)
                        .field("configError", instance.configError).field("pendingCommands", instance.pending.size());
                json.name("samples").beginArray();
                for (MethodKey key : order) {
                    Integer index = instance.index.get(key);
                    if (index == null || !instance.seen[index]) {
                        json.value((String) null);
                    } else {
                        json.beginArray();
                        for (long value : instance.values[index]) {
                            json.value(value);
                        }
                        json.endArray();
                    }
                }
                json.endArray();
                json.name("overrides").beginArray();
                for (MethodKey key : order) {
                    MethodSetting setting = own.get(key);
                    if (setting == null) {
                        json.value((String) null);
                    } else {
                        json.beginObject().field("enabled", setting.enabled()).field("async", setting.async())
                                .field("maxConcurrency", setting.maxConcurrency()).field("consumerThreads", setting.consumerThreads()).endObject();
                    }
                }
                json.endArray().endObject();
            }
            json.endArray();
            json.name("commands").beginArray();
            for (InstanceState instance : instances) {
                for (PendingCommand pending : instance.pending.values()) {
                    new CommandResult(pending, instance.name, "PENDING", 0, null, List.of(), 0).write(json);
                }
            }
            for (CommandResult result : app.results) {
                result.write(json);
            }
            json.endArray().endObject();
        }
    }

    /** Hands the latest raw values of every replica to the history store, unchanged. */
    public List<AdminStore.HistorySample> historySamples() {
        List<AdminStore.HistorySample> samples = new ArrayList<>();
        for (AppState app : apps.values()) {
            synchronized (app) {
                for (InstanceState instance : app.instances.values()) {
                    for (int i = 0; i < instance.catalog.size(); i++) {
                        if (instance.seen[i]) {
                            samples.add(new AdminStore.HistorySample(app.name, instance.name, instance.keys[i], instance.values[i].clone()));
                        }
                    }
                }
            }
        }
        return samples;
    }

    public int applicationCount() {
        return apps.size();
    }

    public int instanceCount() {
        return sessions.size();
    }

    public long intervalMillis() {
        return intervalMillis;
    }

    public static final class CapacityExceededException extends RuntimeException {
        public CapacityExceededException(String message) {
            super(message);
        }
    }

    // ---- internals --------------------------------------------------------------------------

    private AppState requireApp(String application) {
        AppState app = apps.get(application);
        if (app == null) {
            throw new IllegalArgumentException("Unknown application " + application);
        }
        return app;
    }

    private long desiredInterval(AppState app, long now) {
        return app.watchedUntil > now ? watchIntervalMillis : intervalMillis;
    }

    /** Relays stored overrides as they are; the pod overlays instance-scoped entries itself. */
    private ConfigBlock configBlock(AppState app, InstanceState instance) {
        Map<MethodKey, MethodSetting> own = app.instanceOverrides.getOrDefault(instance.name, Map.of());
        List<MethodOverride> overrides = new ArrayList<>();
        for (int i = 0; i < instance.catalog.size(); i++) {
            MethodKey key = instance.keys[i];
            MethodSetting wide = app.overrides.get(key);
            if (wide != null) {
                overrides.add(new MethodOverride(i, wide.enabled(), wide.async(), wide.maxConcurrency(), wide.consumerThreads(), false));
            }
            MethodSetting narrow = own.get(key);
            if (narrow != null) {
                overrides.add(new MethodOverride(i, narrow.enabled(), narrow.async(), narrow.maxConcurrency(), narrow.consumerThreads(), true));
            }
        }
        return new ConfigBlock(app.version, overrides);
    }

    private void drop(AppState app, InstanceState instance, long now, String reason, boolean keepInstanceOverrides) {
        sessions.remove(instance.sessionId, instance);
        app.instances.remove(instance.name, instance);
        for (PendingCommand pending : instance.pending.values()) {
            app.addResult(new CommandResult(pending, instance.name, "EXPIRED", 0, reason, List.of(), now));
        }
        instance.pending.clear();
        if (!keepInstanceOverrides) {
            app.instanceOverrides.remove(instance.name);
        }
    }

    @SuppressWarnings("unchecked")
    <T> T canonical(T value) {
        if (value == null || canonical.size() >= MAX_CANONICAL) {
            return value;
        }
        Object existing = canonical.putIfAbsent(value, value);
        return existing == null ? value : (T) existing;
    }

    private static void requirePlain(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < 0x20) {
                throw new AslProtocolException("Names must not contain control characters");
            }
        }
    }

    private static String status(int status) {
        return switch (status) {
            case Ack.OK -> "OK";
            case Ack.REJECTED -> "REJECTED";
            default -> "FAILED";
        };
    }

    private static final class AppState {
        final String name;
        final int maxResults;
        final Map<String, InstanceState> instances = new LinkedHashMap<>();
        final Map<MethodKey, MethodSetting> overrides = new HashMap<>();
        final Map<String, Map<MethodKey, MethodSetting>> instanceOverrides = new HashMap<>();
        final Map<MethodKey, MethodInfo> known = new HashMap<>();
        final ArrayDeque<CommandResult> results = new ArrayDeque<>();
        long version;
        long watchedUntil;

        AppState(String name, int maxResults) {
            this.name = name;
            this.maxResults = Math.max(0, maxResults);
        }

        void addResult(CommandResult result) {
            if (maxResults == 0) {
                return;
            }
            results.addFirst(result);
            while (results.size() > maxResults) {
                results.removeLast();
            }
        }
    }

    private static final class InstanceState {
        final long sessionId;
        final String application;
        final String name;
        final String libraryVersion;
        final List<MethodInfo> catalog;
        final MethodKey[] keys;
        final Map<MethodKey, Integer> index;
        final long[][] values;
        final boolean[] seen;
        final long registeredAt;
        final LinkedHashMap<Long, PendingCommand> pending = new LinkedHashMap<>();
        long lastSeen;
        long lastSeq;
        long appliedVersion;
        String configError;
        long lastIntervalSent;

        InstanceState(long sessionId, String application, String name, String libraryVersion, List<MethodInfo> catalog, long now,
                      InstanceRegistry registry) {
            this.sessionId = sessionId;
            this.application = application;
            this.name = name;
            this.libraryVersion = libraryVersion;
            this.catalog = List.copyOf(catalog);
            this.values = new long[catalog.size()][MethodSample.FIELD_COUNT];
            this.seen = new boolean[catalog.size()];
            this.keys = new MethodKey[catalog.size()];
            this.index = new HashMap<>(Math.max(4, catalog.size() * 4 / 3 + 1));
            for (int i = 0; i < catalog.size(); i++) {
                keys[i] = registry.canonical(new MethodKey(catalog.get(i).serviceId(), catalog.get(i).methodId()));
                index.put(keys[i], i);
            }
            this.registeredAt = now;
            this.lastSeen = now;
        }
    }

    private static final class PendingCommand {
        final long id;
        final int type;
        final int methodIndex;
        final MethodKey key;
        final String entryId;
        final int limit;
        final long createdAt;
        final String actor;
        long lastSentAt;

        PendingCommand(long id, int type, int methodIndex, MethodKey key, String entryId, int limit, long createdAt, String actor) {
            this.id = id;
            this.type = type;
            this.methodIndex = methodIndex;
            this.key = key;
            this.entryId = entryId;
            this.limit = limit;
            this.createdAt = createdAt;
            this.actor = actor;
        }
    }

    private record CommandResult(PendingCommand pending, String instance, String status, long value, String message,
                                 List<QueueEntry> entries, long completedAt) {
        void write(Json json) {
            json.beginObject().field("commandId", pending.id).field("instance", instance)
                    .field("serviceId", pending.key.serviceId()).field("methodId", pending.key.methodId())
                    .field("type", COMMAND_NAMES[pending.type]).field("status", status).field("value", value)
                    .field("message", message).field("createdAtMillis", pending.createdAt).field("completedAtMillis", completedAt)
                    .field("actor", pending.actor);
            json.name("entries").beginArray();
            for (QueueEntry entry : entries) {
                json.beginObject().field("entryId", entry.entryId()).field("state", entry.state()).field("attempts", entry.attempts())
                        .field("interruptions", entry.interruptions()).field("enqueuedAtMillis", entry.enqueuedAtMillis())
                        .field("errorCategory", entry.errorCategory()).field("lastError", entry.lastError()).endObject();
            }
            json.endArray().endObject();
        }
    }
}
