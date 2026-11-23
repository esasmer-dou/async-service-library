package com.reactor.asl.admin.protocol;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Message model of protocol v1. Methods are referenced by their position in the catalog the pod
 * sent with {@link Register}; every later message carries only that small index.
 */
public final class AslAdminMessages {
    private AslAdminMessages() {
    }

    /** Pod to server, once at startup and whenever the server answers 410. */
    public record Register(String application, String instance, String libraryVersion, List<MethodInfo> methods) {
        public Register {
            Objects.requireNonNull(application, "application");
            Objects.requireNonNull(instance, "instance");
            methods = List.copyOf(methods);
        }
    }

    public record MethodInfo(String serviceId, String methodId, boolean asyncCapable, boolean hasQueue) {
        public MethodInfo {
            Objects.requireNonNull(serviceId, "serviceId");
            Objects.requireNonNull(methodId, "methodId");
        }
    }

    /** Server to pod. {@code config} is {@code null} when the server holds no desired state for the application. */
    public record RegisterReply(long sessionId, long intervalMillis, ConfigBlock config) {
    }

    /** Pod to server, on every tick. */
    public record Beat(
            long sessionId,
            long seq,
            long appliedConfigVersion,
            int flags,
            String configError,
            List<Ack> acks,
            List<MethodSample> samples
    ) {
        /** Every method is included, not only the changed ones. */
        public static final int FULL = 1;
        /** The pod is shutting down; the server may forget it immediately. */
        public static final int LEAVING = 2;

        public Beat {
            acks = List.copyOf(acks);
            samples = List.copyOf(samples);
        }

        public boolean full() {
            return (flags & FULL) != 0;
        }

        public boolean leaving() {
            return (flags & LEAVING) != 0;
        }
    }

    /**
     * Cumulative telemetry for one method. Only the fields whose bit is set in {@code mask} are
     * present on the wire; counters are lifetime totals so a lost beat never corrupts the view.
     */
    public static final class MethodSample {
        public static final int STATE = 0;
        public static final int MAX_CONCURRENCY = 1;
        public static final int CONSUMER_THREADS = 2;
        public static final int IN_FLIGHT = 3;
        public static final int SUCCESS = 4;
        public static final int ERROR = 5;
        public static final int REJECTED = 6;
        public static final int QUEUE_PENDING = 7;
        public static final int QUEUE_FAILED = 8;
        public static final int QUEUE_IN_PROGRESS = 9;
        /** Effective calls-per-second limit on the replica; 0 when unlimited. */
        public static final int RATE_LIMIT = 10;
        public static final int FIELD_COUNT = 11;

        /** {@link #STATE} bit: method is enabled. */
        public static final int STATE_ENABLED = 1;
        /** {@link #STATE} bit: method runs in ASYNC mode. */
        public static final int STATE_ASYNC = 2;

        private final int methodIndex;
        private final int mask;
        private final long[] values;

        public MethodSample(int methodIndex, int mask, long[] values) {
            if (values.length != FIELD_COUNT) {
                throw new IllegalArgumentException("values must have " + FIELD_COUNT + " entries");
            }
            this.methodIndex = methodIndex;
            this.mask = mask & ((1 << FIELD_COUNT) - 1);
            this.values = values.clone();
        }

        public int methodIndex() {
            return methodIndex;
        }

        public int mask() {
            return mask;
        }

        public boolean has(int field) {
            return (mask & (1 << field)) != 0;
        }

        public long value(int field) {
            return values[field];
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof MethodSample sample)) {
                return false;
            }
            if (sample.methodIndex != methodIndex || sample.mask != mask) {
                return false;
            }
            for (int field = 0; field < FIELD_COUNT; field++) {
                if (has(field) && values[field] != sample.values[field]) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            return Objects.hash(methodIndex, mask);
        }

        @Override
        public String toString() {
            return "MethodSample[" + methodIndex + ", mask=" + Integer.toBinaryString(mask) + ", " + Arrays.toString(values) + "]";
        }
    }

    /** Result of a command, sent back with the next beat. */
    public record Ack(long commandId, int status, long value, String message, List<QueueEntry> entries) {
        public static final int OK = 0;
        public static final int REJECTED = 1;
        public static final int FAILED = 2;

        public Ack {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }

        public static Ack ok(long commandId, long value) {
            return new Ack(commandId, OK, value, null, List.of());
        }
    }

    /**
     * One queued call as a browse answer shows it. Never the payload itself: {@code summary} is what the
     * pod's {@code asl.async.mapdb.payload.summary-policy} stored (type only by default), already cut short.
     */
    public record QueueEntry(
            String entryId,
            String state,
            int attempts,
            int interruptions,
            long enqueuedAtMillis,
            String errorCategory,
            String lastError,
            String lastErrorType,
            String summary,
            String payloadType,
            String payloadVersion,
            String codecId
    ) {
        /** An entry without payload details, as pods built before they were added send it. */
        public QueueEntry(String entryId, String state, int attempts, int interruptions, long enqueuedAtMillis,
                          String errorCategory, String lastError) {
            this(entryId, state, attempts, interruptions, enqueuedAtMillis, errorCategory, lastError, null, null, null, null, null);
        }
    }

    /** Server to pod, carried by a 200 answer to a beat. */
    public record BeatReply(boolean sendFull, long intervalMillis, ConfigBlock config, List<Command> commands) {
        public BeatReply {
            commands = commands == null ? List.of() : List.copyOf(commands);
        }

        public static BeatReply empty() {
            return new BeatReply(false, 0, null, List.of());
        }

        /** An empty reply is answered with 204 and no body. */
        public boolean isEmpty() {
            return !sendFull && intervalMillis <= 0 && config == null && commands.isEmpty();
        }
    }

    /**
     * Complete desired overrides for one pod. A method that has no entry runs with its startup
     * (yml) values, so removing an override is simply leaving it out. A method may have one
     * application-wide and one instance-scoped entry.
     */
    public record ConfigBlock(long version, List<MethodOverride> overrides) {
        public ConfigBlock {
            overrides = List.copyOf(overrides);
        }
    }

    /**
     * {@code null} components are not overridden. The pod applies application-wide entries first
     * and then {@code instanceScoped} entries for the same method on top, so the server only
     * relays what was stored and never merges.
     */
    public record MethodOverride(
            int methodIndex,
            Boolean enabled,
            Boolean async,
            Integer maxConcurrency,
            Integer consumerThreads,
            boolean instanceScoped,
            /** Calls per second; 0 means "no limit", which also removes a limit from the application's configuration. */
            Integer rateLimit
    ) {
        public MethodOverride(int methodIndex, Boolean enabled, Boolean async, Integer maxConcurrency, Integer consumerThreads) {
            this(methodIndex, enabled, async, maxConcurrency, consumerThreads, false, null);
        }

        public MethodOverride(int methodIndex, Boolean enabled, Boolean async, Integer maxConcurrency, Integer consumerThreads,
                              boolean instanceScoped) {
            this(methodIndex, enabled, async, maxConcurrency, consumerThreads, instanceScoped, null);
        }

        public boolean isEmpty() {
            return enabled == null && async == null && maxConcurrency == null && consumerThreads == null && rateLimit == null;
        }
    }

    public record Command(long commandId, int type, int methodIndex, String entryId, int limit) {
        public static final int CLEAR_QUEUE = 1;
        public static final int REPLAY_ENTRY = 2;
        public static final int DELETE_ENTRY = 3;
        public static final int BROWSE_QUEUE = 4;
        public static final int RESET_OBSERVATIONS = 5;
    }
}
