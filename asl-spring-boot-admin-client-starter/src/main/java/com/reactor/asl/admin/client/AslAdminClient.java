package com.reactor.asl.admin.client;

import com.reactor.asl.admin.protocol.AslAdminCodec;
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
import com.reactor.asl.admin.protocol.AslAdminProtocol;
import com.reactor.asl.admin.protocol.AslAdminSigner;
import com.reactor.asl.admin.protocol.WireWriter;
import com.reactor.asl.core.AsyncAdminProvider;
import com.reactor.asl.core.BufferAdminProvider;
import com.reactor.asl.core.ExecutionMode;
import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.MethodBufferEntryView;
import com.reactor.asl.core.MethodBufferSnapshot;
import com.reactor.asl.core.MethodRuntime;
import com.reactor.asl.core.ServiceRuntime;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Pod side of the ASL admin protocol. One daemon thread opens every connection: it registers,
 * then sends a heartbeat each interval carrying only the methods whose values changed. Desired
 * settings and commands come back in the reply. The pod exposes no endpoint of its own.
 *
 * <p>The client never buffers telemetry: while the server is unreachable it backs off and the
 * application keeps running with its last applied settings. Telemetry is read from the existing
 * lock-free counters on this thread only, so governed calls are not affected.</p>
 */
public final class AslAdminClient implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(AslAdminClient.class.getName());
    private static final long FAILURE_LOG_INTERVAL_MILLIS = 5 * 60_000L;
    private static final long ACK_FLUSH_DELAY_MILLIS = 200L;
    private static final int EXECUTED_COMMAND_MEMORY = 256;
    /** Payload type, payload version and codec id are short labels; anything longer is cut. */
    private static final int MAX_LABEL_CHARS = 120;
    /** One browse answer stays under this size, so two of them and the samples still fit the server's 64 KB body limit. */
    private static final int BROWSE_ANSWER_BUDGET_BYTES = 24 * 1024;

    private final AslAdminClientSettings settings;
    private final GovernanceRegistry registry;
    private final List<BufferAdminProvider> providers;
    private final AslAdminTransport transport;
    private final AslAdminSigner signer;
    private final LongSupplier clock;
    private final WireWriter writer = new WireWriter(256);

    private final List<MethodRuntime> catalog = new ArrayList<>();
    private final List<BufferAdminProvider> catalogProviders = new ArrayList<>();
    private final List<Baseline> baselines = new ArrayList<>();
    private final List<long[]> lastSent = new ArrayList<>();
    private final Set<Integer> overridden = new HashSet<>();
    private final ArrayDeque<Ack> pendingAcks = new ArrayDeque<>();
    private final Map<Long, Ack> executed = new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Ack> eldest) {
            return size() > EXECUTED_COMMAND_MEMORY;
        }
    };

    private long sessionId;
    private long seq;
    private long appliedConfigVersion;
    private String configError;
    private boolean configErrorPending;
    private boolean forceFull = true;
    private long intervalMillis;
    private int failures;
    private long lastFailureLogMillis = -FAILURE_LOG_INTERVAL_MILLIS;

    private ScheduledExecutorService scheduler;
    private volatile boolean stopped;

    public AslAdminClient(
            AslAdminClientSettings settings,
            GovernanceRegistry registry,
            List<BufferAdminProvider> providers,
            AslAdminTransport transport,
            AslAdminSigner signer
    ) {
        this(settings, registry, providers, transport, signer, System::currentTimeMillis);
    }

    AslAdminClient(
            AslAdminClientSettings settings,
            GovernanceRegistry registry,
            List<BufferAdminProvider> providers,
            AslAdminTransport transport,
            AslAdminSigner signer,
            LongSupplier clock
    ) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.providers = List.copyOf(providers);
        this.transport = Objects.requireNonNull(transport, "transport");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.intervalMillis = settings.intervalMillis();
    }

    // ---- lifecycle --------------------------------------------------------------------------

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        stopped = false;
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "asl-admin-client");
            thread.setDaemon(true);
            return thread;
        });
        scheduleNext(jitter(Math.min(settings.intervalMillis(), 1_000L)));
    }

    /** Stops the heartbeat thread and tells the server, best effort, that this pod is leaving. */
    @Override
    public void close() {
        ScheduledExecutorService current;
        synchronized (this) {
            stopped = true;
            current = scheduler;
            scheduler = null;
        }
        if (current != null) {
            current.shutdownNow();
            try {
                current.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        sendLeaving();
    }

    public synchronized boolean isRegistered() {
        return sessionId != 0;
    }

    public synchronized long currentIntervalMillis() {
        return intervalMillis;
    }

    synchronized long appliedConfigVersion() {
        return appliedConfigVersion;
    }

    private void scheduleNext(long delayMillis) {
        ScheduledExecutorService current = scheduler;
        if (current == null || stopped) {
            return;
        }
        try {
            current.schedule(this::tick, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException ignored) {
            // shutting down
        }
    }

    private void tick() {
        long next;
        try {
            next = runOnce();
        } catch (Throwable unexpected) {
            LOG.log(System.Logger.Level.WARNING, "ASL admin client tick failed", unexpected);
            next = backoff();
        }
        scheduleNext(jitter(next));
    }

    // ---- one tick ---------------------------------------------------------------------------

    /** Runs one register-or-beat exchange and returns the delay before the next one. */
    synchronized long runOnce() throws InterruptedException {
        if (sessionId == 0) {
            if (!register()) {
                return backoff();
            }
        }
        return beat();
    }

    private boolean register() throws InterruptedException {
        refreshCatalog();
        List<MethodInfo> methods = new ArrayList<>(catalog.size());
        for (int i = 0; i < catalog.size(); i++) {
            MethodRuntime runtime = catalog.get(i);
            methods.add(new MethodInfo(runtime.serviceId(), runtime.methodId(), runtime.asyncCapable(), catalogProviders.get(i) != null));
        }
        byte[] body = AslAdminCodec.encode(new Register(settings.application(), settings.instance(), settings.libraryVersion(), methods));
        AslAdminTransport.Response response;
        try {
            response = send(AslAdminProtocol.REGISTER_PATH, body);
        } catch (IOException exception) {
            onFailure("register", exception.toString());
            return false;
        }
        if (response.status() != 200) {
            onFailure("register", "HTTP " + response.status());
            return false;
        }
        RegisterReply reply = AslAdminCodec.decodeRegisterReply(response.body());
        sessionId = reply.sessionId();
        seq = 0;
        // Command ids only identify a re-delivery within one session. A new session may come from a
        // restarted admin server whose ids start again, so an old answer must not be replayed for them.
        executed.clear();
        forceFull = true;
        if (reply.intervalMillis() > 0) {
            intervalMillis = reply.intervalMillis();
        }
        if (reply.config() != null) {
            applyConfig(reply.config());
        }
        onSuccess();
        return true;
    }

    private long beat() throws InterruptedException {
        if (catalogChanged()) {
            sessionId = 0;
            return 0;
        }
        boolean full = forceFull;
        List<MethodSample> samples = new ArrayList<>();
        List<long[]> current = new ArrayList<>(catalog.size());
        for (int i = 0; i < catalog.size(); i++) {
            long[] values = read(i);
            current.add(values);
            int mask = full ? fullMask(values) : changedMask(lastSent.get(i), values);
            if (mask != 0) {
                samples.add(new MethodSample(i, mask, values));
            }
        }
        List<Ack> acks = List.copyOf(pendingAcks);
        String error = configErrorPending || full ? configError : null;
        Beat beat = new Beat(sessionId, ++seq, appliedConfigVersion, full ? Beat.FULL : 0, error, acks, samples);
        byte[] body = AslAdminCodec.encode(beat, writer.reset()).toByteArray();

        AslAdminTransport.Response response;
        try {
            response = send(AslAdminProtocol.BEAT_PATH, body);
        } catch (IOException exception) {
            forceFull = true;
            onFailure("beat", exception.toString());
            return backoff();
        }
        switch (response.status()) {
            case 204, 200 -> {
                for (int i = 0; i < current.size(); i++) {
                    lastSent.set(i, current.get(i));
                }
                for (int i = 0; i < acks.size(); i++) {
                    pendingAcks.pollFirst();
                }
                forceFull = false;
                if (error != null) {
                    configErrorPending = false;
                }
                onSuccess();
                if (response.status() == 200) {
                    handle(AslAdminCodec.decodeBeatReply(response.body()));
                }
                return pendingAcks.isEmpty() ? intervalMillis : ACK_FLUSH_DELAY_MILLIS;
            }
            case 410 -> {
                sessionId = 0;
                return Math.min(intervalMillis, 1_000L);
            }
            default -> {
                forceFull = true;
                onFailure("beat", "HTTP " + response.status());
                return backoff();
            }
        }
    }

    private void handle(BeatReply reply) {
        if (reply.sendFull()) {
            forceFull = true;
        }
        if (reply.intervalMillis() > 0) {
            intervalMillis = Math.max(100L, reply.intervalMillis());
        }
        if (reply.config() != null) {
            applyConfig(reply.config());
        }
        for (Command command : reply.commands()) {
            Ack previous = executed.get(command.commandId());
            Ack ack = previous != null ? previous : execute(command);
            executed.put(command.commandId(), ack);
            pendingAcks.addLast(ack);
        }
    }

    private void sendLeaving() {
        long session;
        long nextSeq;
        long applied;
        synchronized (this) {
            session = sessionId;
            nextSeq = ++seq;
            applied = appliedConfigVersion;
            sessionId = 0;
        }
        if (session == 0) {
            return;
        }
        byte[] body = AslAdminCodec.encode(new Beat(session, nextSeq, applied, Beat.LEAVING, null, List.of(), List.of()));
        try {
            send(AslAdminProtocol.BEAT_PATH, body);
        } catch (IOException exception) {
            // best effort; the server expires the instance anyway
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private AslAdminTransport.Response send(String path, byte[] body) throws IOException, InterruptedException {
        return transport.post(path, body, signer.sign(body, clock.getAsLong() / 1000L));
    }

    // ---- catalog and telemetry --------------------------------------------------------------

    private void refreshCatalog() {
        List<MethodRuntime> discovered = discover();
        for (MethodRuntime runtime : discovered) {
            if (catalog.contains(runtime)) {
                continue;
            }
            catalog.add(runtime);
            catalogProviders.add(provider(runtime));
            baselines.add(Baseline.of(runtime));
            lastSent.add(null);
        }
    }

    private boolean catalogChanged() {
        int total = 0;
        for (ServiceRuntime service : registry.services()) {
            total += service.methods().size();
        }
        return total != catalog.size();
    }

    private List<MethodRuntime> discover() {
        List<MethodRuntime> methods = new ArrayList<>();
        registry.services().stream()
                .sorted(Comparator.comparing(ServiceRuntime::serviceId))
                .forEach(service -> service.methods().stream()
                        .sorted(Comparator.comparing(MethodRuntime::methodId))
                        .forEach(methods::add));
        return methods;
    }

    private long[] read(int index) {
        MethodRuntime runtime = catalog.get(index);
        long[] values = new long[MethodSample.FIELD_COUNT];
        values[MethodSample.STATE] = (runtime.policy().enabled() ? MethodSample.STATE_ENABLED : 0)
                | (runtime.executionMode() == ExecutionMode.ASYNC ? MethodSample.STATE_ASYNC : 0);
        values[MethodSample.MAX_CONCURRENCY] = runtime.policy().maxConcurrency();
        values[MethodSample.CONSUMER_THREADS] = runtime.consumerThreads();
        values[MethodSample.IN_FLIGHT] = Math.max(0, runtime.inFlight());
        values[MethodSample.SUCCESS] = runtime.totalSuccessCount();
        values[MethodSample.ERROR] = runtime.totalErrorCount();
        values[MethodSample.REJECTED] = runtime.totalRejectedCount();
        values[MethodSample.RATE_LIMIT] = runtime.policy().rateLimitPerSecond();
        BufferAdminProvider provider = catalogProviders.get(index);
        if (provider != null) {
            long[] previous = lastSent.get(index);
            try {
                MethodBufferSnapshot buffer = provider.snapshot(runtime.serviceId(), runtime.methodId(), 0);
                values[MethodSample.QUEUE_PENDING] = buffer.pendingCount();
                values[MethodSample.QUEUE_FAILED] = buffer.failedCount();
                values[MethodSample.QUEUE_IN_PROGRESS] = buffer.inProgressCount();
            } catch (RuntimeException storeFailure) {
                if (previous != null) {
                    values[MethodSample.QUEUE_PENDING] = previous[MethodSample.QUEUE_PENDING];
                    values[MethodSample.QUEUE_FAILED] = previous[MethodSample.QUEUE_FAILED];
                    values[MethodSample.QUEUE_IN_PROGRESS] = previous[MethodSample.QUEUE_IN_PROGRESS];
                }
            }
        }
        return values;
    }

    /**
     * Every field for a full sample, except a rate limit of 0: the server already assumes 0, and leaving the bit out
     * keeps pods without a rate limit readable by admin servers that predate the field.
     */
    private static int fullMask(long[] current) {
        int mask = (1 << MethodSample.FIELD_COUNT) - 1;
        return current[MethodSample.RATE_LIMIT] == 0L ? mask & ~(1 << MethodSample.RATE_LIMIT) : mask;
    }

    private static int changedMask(long[] previous, long[] current) {
        if (previous == null) {
            return fullMask(current);
        }
        int mask = 0;
        for (int field = 0; field < MethodSample.FIELD_COUNT; field++) {
            if (previous[field] != current[field]) {
                mask |= 1 << field;
            }
        }
        return mask;
    }

    // ---- desired configuration --------------------------------------------------------------

    private void applyConfig(ConfigBlock config) {
        List<String> errors = new ArrayList<>();
        Map<Integer, MethodOverride> merged = new LinkedHashMap<>();
        for (int pass = 0; pass < 2; pass++) {
            for (MethodOverride override : config.overrides()) {
                if (override.instanceScoped() != (pass == 1)) {
                    continue;
                }
                int index = override.methodIndex();
                if (index < 0 || index >= catalog.size()) {
                    errors.add("unknown method index " + index);
                    continue;
                }
                merged.merge(index, override, AslAdminClient::overlay);
            }
        }
        Set<Integer> present = new HashSet<>(merged.keySet());
        for (MethodOverride override : merged.values()) {
            int index = override.methodIndex();
            Baseline base = baselines.get(index);
            try {
                applyTarget(index,
                        override.enabled() != null ? override.enabled() : base.enabled(),
                        override.async() != null ? override.async() : base.async(),
                        override.maxConcurrency() != null ? override.maxConcurrency() : base.maxConcurrency(),
                        override.consumerThreads() != null ? override.consumerThreads() : base.consumerThreads(),
                        override.rateLimit() != null ? override.rateLimit() : base.rateLimit());
            } catch (RuntimeException exception) {
                errors.add(label(index) + ": " + exception.getMessage());
            }
        }
        for (Integer index : overridden) {
            if (present.contains(index)) {
                continue;
            }
            Baseline base = baselines.get(index);
            try {
                applyTarget(index, base.enabled(), base.async(), base.maxConcurrency(), base.consumerThreads(), base.rateLimit());
            } catch (RuntimeException exception) {
                errors.add(label(index) + ": " + exception.getMessage());
            }
        }
        overridden.clear();
        overridden.addAll(present);
        appliedConfigVersion = config.version();
        configError = errors.isEmpty() ? null : truncate(String.join("; ", errors));
        configErrorPending = true;
    }

    /** Components of {@code narrower} (instance-scoped) win over {@code wider} (application-wide). */
    private static MethodOverride overlay(MethodOverride wider, MethodOverride narrower) {
        return new MethodOverride(
                wider.methodIndex(),
                narrower.enabled() != null ? narrower.enabled() : wider.enabled(),
                narrower.async() != null ? narrower.async() : wider.async(),
                narrower.maxConcurrency() != null ? narrower.maxConcurrency() : wider.maxConcurrency(),
                narrower.consumerThreads() != null ? narrower.consumerThreads() : wider.consumerThreads(),
                narrower.instanceScoped(),
                narrower.rateLimit() != null ? narrower.rateLimit() : wider.rateLimit()
        );
    }

    private void applyTarget(int index, boolean enabled, boolean async, int maxConcurrency, int consumerThreads, int rateLimit) {
        MethodRuntime runtime = catalog.get(index);
        if (async && !runtime.asyncCapable()) {
            throw new IllegalStateException("method is not async-capable");
        }
        if (async && !registry.hasAsyncExecutionEngine()) {
            throw new IllegalStateException("no async execution engine attached");
        }
        if (maxConcurrency <= 0 || consumerThreads < 0 || rateLimit < 0) {
            throw new IllegalArgumentException("invalid limits");
        }
        boolean runtimeChanged = false;
        if (runtime.policy().maxConcurrency() != maxConcurrency) {
            runtime.setMaxConcurrency(maxConcurrency);
        }
        if (runtime.policy().rateLimitPerSecond() != rateLimit) {
            runtime.setRateLimitPerSecond(rateLimit);
            runtimeChanged = true;
        }
        if (runtime.consumerThreads() != consumerThreads) {
            runtime.setConsumerThreads(consumerThreads);
            runtimeChanged = true;
        }
        if (runtime.policy().enabled() != enabled) {
            if (enabled) {
                runtime.enable();
            } else {
                runtime.disable(runtime.policy().unavailableMessage());
            }
        }
        ExecutionMode mode = async ? ExecutionMode.ASYNC : ExecutionMode.SYNC;
        if (runtime.executionMode() != mode) {
            runtime.switchMode(mode);
            runtimeChanged = true;
        }
        if (runtimeChanged) {
            BufferAdminProvider provider = catalogProviders.get(index);
            if (provider instanceof AsyncAdminProvider asyncProvider) {
                asyncProvider.applyRuntime(runtime);
            }
        }
    }

    // ---- commands ---------------------------------------------------------------------------

    private Ack execute(Command command) {
        int index = command.methodIndex();
        if (index < 0 || index >= catalog.size()) {
            return reject(command, "unknown method index " + index);
        }
        MethodRuntime runtime = catalog.get(index);
        BufferAdminProvider provider = catalogProviders.get(index);
        try {
            return switch (command.type()) {
                case Command.RESET_OBSERVATIONS -> {
                    runtime.resetObservations();
                    yield Ack.ok(command.commandId(), 1);
                }
                case Command.CLEAR_QUEUE -> provider == null
                        ? reject(command, "method has no queue")
                        : Ack.ok(command.commandId(), provider.clear(runtime.serviceId(), runtime.methodId()));
                case Command.DELETE_ENTRY -> provider == null
                        ? reject(command, "method has no queue")
                        : Ack.ok(command.commandId(), provider.delete(runtime.serviceId(), runtime.methodId(), command.entryId()) ? 1 : 0);
                case Command.REPLAY_ENTRY -> provider instanceof AsyncAdminProvider asyncProvider
                        ? Ack.ok(command.commandId(), asyncProvider.replay(runtime.serviceId(), runtime.methodId(), command.entryId()) ? 1 : 0)
                        : reject(command, "method has no async queue");
                case Command.BROWSE_QUEUE -> browse(command, runtime, provider);
                default -> reject(command, "unknown command type " + command.type());
            };
        } catch (RuntimeException exception) {
            return new Ack(command.commandId(), Ack.FAILED, 0, truncate(String.valueOf(exception.getMessage())), List.of());
        }
    }

    private Ack browse(Command command, MethodRuntime runtime, BufferAdminProvider provider) {
        if (!settings.queueBrowse()) {
            return reject(command, "queue browse is disabled on this pod (asl.admin.client.queue-browse)");
        }
        if (provider == null) {
            return reject(command, "method has no queue");
        }
        int limit = Math.max(1, Math.min(command.limit() <= 0 ? settings.browseLimit() : command.limit(), settings.browseLimit()));
        MethodBufferSnapshot snapshot = provider.snapshot(runtime.serviceId(), runtime.methodId(), limit);
        List<QueueEntry> entries = new ArrayList<>(snapshot.entries().size());
        int bytes = 0;
        for (MethodBufferEntryView entry : snapshot.entries()) {
            QueueEntry item = new QueueEntry(
                    entry.entryId(),
                    entry.state(),
                    entry.attempts(),
                    entry.interruptions(),
                    entry.enqueuedAt() == null ? 0 : entry.enqueuedAt().toEpochMilli(),
                    entry.errorCategory(),
                    cut(entry.lastError(), settings.maxErrorChars()),
                    cut(entry.lastErrorType(), settings.maxErrorChars()),
                    cut(entry.summary(), settings.maxSummaryChars()),
                    cut(entry.payloadType(), MAX_LABEL_CHARS),
                    cut(entry.payloadVersion(), MAX_LABEL_CHARS),
                    cut(entry.codecId(), MAX_LABEL_CHARS)
            );
            // The answer rides in one beat; stop before it could pass the server's body limit (64 KB by default).
            bytes += utf8Length(item);
            if (bytes > BROWSE_ANSWER_BUDGET_BYTES && !entries.isEmpty()) {
                break;
            }
            entries.add(item);
        }
        return new Ack(command.commandId(), Ack.OK, entries.size(), null, entries);
    }

    private static int utf8Length(QueueEntry entry) {
        int total = 32;
        for (String value : new String[] {entry.entryId(), entry.state(), entry.errorCategory(), entry.lastError(),
                entry.lastErrorType(), entry.summary(), entry.payloadType(), entry.payloadVersion(), entry.codecId()}) {
            total += value == null ? 1 : value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 2;
        }
        return total;
    }

    /** Cuts to at most {@code max} characters without splitting a surrogate pair; 0 drops the value. */
    private static String cut(String value, int max) {
        if (value == null || max <= 0) {
            return null;
        }
        if (value.length() <= max) {
            return value;
        }
        int end = Character.isHighSurrogate(value.charAt(max - 1)) ? max - 1 : max;
        return value.substring(0, end);
    }

    private static Ack reject(Command command, String reason) {
        return new Ack(command.commandId(), Ack.REJECTED, 0, reason, List.of());
    }

    // ---- helpers ----------------------------------------------------------------------------

    private BufferAdminProvider provider(MethodRuntime runtime) {
        for (BufferAdminProvider provider : providers) {
            try {
                if (provider.supports(runtime.serviceId(), runtime.methodId())) {
                    return provider;
                }
            } catch (RuntimeException ignored) {
                // a failing provider is treated as absent
            }
        }
        return null;
    }

    private String label(int index) {
        MethodRuntime runtime = catalog.get(index);
        return runtime.serviceId() + "#" + runtime.methodId();
    }

    private String truncate(String value) {
        return value.length() <= settings.maxErrorChars() ? value : value.substring(0, settings.maxErrorChars());
    }

    private long backoff() {
        long delay = intervalMillis;
        for (int i = 0; i < failures && delay < settings.maxBackoffMillis(); i++) {
            delay *= 2;
        }
        return Math.min(delay, settings.maxBackoffMillis());
    }

    private void onFailure(String step, String reason) {
        failures = Math.min(failures + 1, 30);
        long now = clock.getAsLong();
        if (now - lastFailureLogMillis >= FAILURE_LOG_INTERVAL_MILLIS) {
            lastFailureLogMillis = now;
            LOG.log(System.Logger.Level.WARNING,
                    "ASL admin server unreachable ({0}: {1}); the application keeps its current settings", step, reason);
        }
    }

    private void onSuccess() {
        if (failures > 0) {
            LOG.log(System.Logger.Level.INFO, "ASL admin server reachable again");
        }
        failures = 0;
        lastFailureLogMillis = -FAILURE_LOG_INTERVAL_MILLIS;
    }

    private static long jitter(long delayMillis) {
        if (delayMillis <= 0) {
            return 0;
        }
        long spread = delayMillis / 10;
        return spread == 0 ? delayMillis : delayMillis - spread + ThreadLocalRandom.current().nextLong(2 * spread + 1);
    }

    private record Baseline(boolean enabled, boolean async, int maxConcurrency, int consumerThreads, int rateLimit) {
        static Baseline of(MethodRuntime runtime) {
            return new Baseline(
                    runtime.policy().enabled(),
                    runtime.executionMode() == ExecutionMode.ASYNC,
                    runtime.policy().maxConcurrency(),
                    runtime.consumerThreads(),
                    runtime.policy().rateLimitPerSecond()
            );
        }
    }
}
