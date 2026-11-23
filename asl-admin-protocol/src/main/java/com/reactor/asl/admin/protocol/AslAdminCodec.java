package com.reactor.asl.admin.protocol;

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

import java.util.ArrayList;
import java.util.List;

/**
 * Binary codec for protocol v1. Every message starts with {@code u8 version, u8 type}.
 * Stateless and thread-safe; the {@code WireWriter} overloads let a caller reuse one buffer.
 */
public final class AslAdminCodec {
    public static final int TYPE_REGISTER = 1;
    public static final int TYPE_REGISTER_REPLY = 2;
    public static final int TYPE_BEAT = 3;
    public static final int TYPE_BEAT_REPLY = 4;

    private static final int BEAT_HAS_CONFIG_ERROR = 4;
    /** Queue entries carry five more strings: error type, summary, payload type, payload version, codec. */
    private static final int BEAT_ENTRY_DETAILS = 8;

    private static final int REPLY_SEND_FULL = 1;
    private static final int REPLY_INTERVAL = 2;
    private static final int REPLY_CONFIG = 4;
    private static final int REPLY_COMMANDS = 8;

    private static final int OVERRIDE_ENABLED = 1;
    private static final int OVERRIDE_ASYNC = 2;
    private static final int OVERRIDE_MAX_CONCURRENCY = 4;
    private static final int OVERRIDE_CONSUMER_THREADS = 8;
    private static final int OVERRIDE_ENABLED_VALUE = 16;
    private static final int OVERRIDE_ASYNC_VALUE = 32;
    private static final int OVERRIDE_INSTANCE_SCOPED = 64;
    /** Calls-per-second limit follows (0 removes a limit set in the application's own configuration). */
    private static final int OVERRIDE_RATE_LIMIT = 128;

    private AslAdminCodec() {
    }

    // ---- Register -------------------------------------------------------------------------

    public static byte[] encode(Register message) {
        return encode(message, new WireWriter(256)).toByteArray();
    }

    public static WireWriter encode(Register message, WireWriter out) {
        header(out, TYPE_REGISTER);
        out.str(message.application()).str(message.instance()).str(message.libraryVersion());
        out.uvar(message.methods().size());
        for (MethodInfo method : message.methods()) {
            out.str(method.serviceId()).str(method.methodId())
                    .u8((method.asyncCapable() ? 1 : 0) | (method.hasQueue() ? 2 : 0));
        }
        return out;
    }

    public static Register decodeRegister(byte[] body) {
        WireReader in = open(body, TYPE_REGISTER);
        String application = required(in.str(), "application");
        String instance = required(in.str(), "instance");
        String libraryVersion = in.str();
        int count = in.count();
        List<MethodInfo> methods = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String serviceId = required(in.str(), "serviceId");
            String methodId = required(in.str(), "methodId");
            int caps = in.u8();
            methods.add(new MethodInfo(serviceId, methodId, (caps & 1) != 0, (caps & 2) != 0));
        }
        in.expectEnd();
        return new Register(application, instance, libraryVersion, methods);
    }

    public static byte[] encode(RegisterReply message) {
        WireWriter out = new WireWriter(64);
        header(out, TYPE_REGISTER_REPLY);
        out.uvar(message.sessionId()).uvar(message.intervalMillis()).bool(message.config() != null);
        if (message.config() != null) {
            writeConfig(out, message.config());
        }
        return out.toByteArray();
    }

    public static RegisterReply decodeRegisterReply(byte[] body) {
        WireReader in = open(body, TYPE_REGISTER_REPLY);
        long sessionId = in.uvar();
        long interval = in.uvar();
        ConfigBlock config = in.bool() ? readConfig(in) : null;
        in.expectEnd();
        return new RegisterReply(sessionId, interval, config);
    }

    // ---- Beat -----------------------------------------------------------------------------

    public static byte[] encode(Beat message) {
        return encode(message, new WireWriter(64)).toByteArray();
    }

    public static WireWriter encode(Beat message, WireWriter out) {
        header(out, TYPE_BEAT);
        int flags = message.flags() & (Beat.FULL | Beat.LEAVING);
        if (message.configError() != null) {
            flags |= BEAT_HAS_CONFIG_ERROR;
        }
        for (Ack ack : message.acks()) {
            if (!ack.entries().isEmpty()) {
                flags |= BEAT_ENTRY_DETAILS;
                break;
            }
        }
        out.uvar(message.sessionId()).uvar(message.seq()).uvar(message.appliedConfigVersion()).u8(flags);
        if (message.configError() != null) {
            out.str(message.configError());
        }
        out.uvar(message.acks().size());
        for (Ack ack : message.acks()) {
            out.uvar(ack.commandId()).u8(ack.status()).svar(ack.value()).str(ack.message());
            out.uvar(ack.entries().size());
            for (QueueEntry entry : ack.entries()) {
                out.str(entry.entryId()).str(entry.state())
                        .uvar(nonNegative(entry.attempts())).uvar(nonNegative(entry.interruptions()))
                        .svar(entry.enqueuedAtMillis()).str(entry.errorCategory()).str(entry.lastError())
                        .str(entry.lastErrorType()).str(entry.summary()).str(entry.payloadType())
                        .str(entry.payloadVersion()).str(entry.codecId());
            }
        }
        out.uvar(message.samples().size());
        for (MethodSample sample : message.samples()) {
            out.uvar(sample.methodIndex()).uvar(sample.mask());
            for (int field = 0; field < MethodSample.FIELD_COUNT; field++) {
                if (!sample.has(field)) {
                    continue;
                }
                if (field == MethodSample.STATE) {
                    out.u8((int) sample.value(field));
                } else {
                    out.uvar(nonNegative(sample.value(field)));
                }
            }
        }
        return out;
    }

    public static Beat decodeBeat(byte[] body) {
        WireReader in = open(body, TYPE_BEAT);
        long sessionId = in.uvar();
        long seq = in.uvar();
        long applied = in.uvar();
        int flags = in.u8();
        String configError = (flags & BEAT_HAS_CONFIG_ERROR) != 0 ? in.str() : null;
        int ackCount = in.count();
        List<Ack> acks = new ArrayList<>(ackCount);
        for (int i = 0; i < ackCount; i++) {
            long commandId = in.uvar();
            int status = in.u8();
            long value = in.svar();
            String message = in.str();
            int entryCount = in.count();
            List<QueueEntry> entries = new ArrayList<>(entryCount);
            for (int e = 0; e < entryCount; e++) {
                QueueEntry entry = new QueueEntry(in.str(), in.str(), in.uint(), in.uint(), in.svar(), in.str(), in.str());
                if ((flags & BEAT_ENTRY_DETAILS) != 0) {
                    entry = new QueueEntry(entry.entryId(), entry.state(), entry.attempts(), entry.interruptions(),
                            entry.enqueuedAtMillis(), entry.errorCategory(), entry.lastError(),
                            in.str(), in.str(), in.str(), in.str(), in.str());
                }
                entries.add(entry);
            }
            acks.add(new Ack(commandId, status, value, message, entries));
        }
        int sampleCount = in.count();
        List<MethodSample> samples = new ArrayList<>(sampleCount);
        for (int i = 0; i < sampleCount; i++) {
            int index = in.uint();
            int mask = in.uint();
            if (mask >= (1 << MethodSample.FIELD_COUNT)) {
                throw new AslProtocolException("Unknown sample fields: " + Integer.toBinaryString(mask));
            }
            long[] values = new long[MethodSample.FIELD_COUNT];
            for (int field = 0; field < MethodSample.FIELD_COUNT; field++) {
                if ((mask & (1 << field)) != 0) {
                    values[field] = field == MethodSample.STATE ? in.u8() : in.uvar();
                }
            }
            samples.add(new MethodSample(index, mask, values));
        }
        in.expectEnd();
        return new Beat(sessionId, seq, applied, flags & (Beat.FULL | Beat.LEAVING), configError, acks, samples);
    }

    public static byte[] encode(BeatReply message) {
        WireWriter out = new WireWriter(64);
        header(out, TYPE_BEAT_REPLY);
        int flags = (message.sendFull() ? REPLY_SEND_FULL : 0)
                | (message.intervalMillis() > 0 ? REPLY_INTERVAL : 0)
                | (message.config() != null ? REPLY_CONFIG : 0)
                | (message.commands().isEmpty() ? 0 : REPLY_COMMANDS);
        out.u8(flags);
        if (message.intervalMillis() > 0) {
            out.uvar(message.intervalMillis());
        }
        if (message.config() != null) {
            writeConfig(out, message.config());
        }
        if (!message.commands().isEmpty()) {
            out.uvar(message.commands().size());
            for (Command command : message.commands()) {
                out.uvar(command.commandId()).u8(command.type()).uvar(command.methodIndex())
                        .str(command.entryId()).uvar(nonNegative(command.limit()));
            }
        }
        return out.toByteArray();
    }

    public static BeatReply decodeBeatReply(byte[] body) {
        WireReader in = open(body, TYPE_BEAT_REPLY);
        int flags = in.u8();
        long interval = (flags & REPLY_INTERVAL) != 0 ? in.uvar() : 0;
        ConfigBlock config = (flags & REPLY_CONFIG) != 0 ? readConfig(in) : null;
        List<Command> commands = List.of();
        if ((flags & REPLY_COMMANDS) != 0) {
            int count = in.count();
            List<Command> decoded = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                decoded.add(new Command(in.uvar(), in.u8(), in.uint(), in.str(), in.uint()));
            }
            commands = decoded;
        }
        in.expectEnd();
        return new BeatReply((flags & REPLY_SEND_FULL) != 0, interval, config, commands);
    }

    // ---- Config block (also used by the server for persistence) ---------------------------

    public static void writeConfig(WireWriter out, ConfigBlock config) {
        out.uvar(config.version()).uvar(config.overrides().size());
        for (MethodOverride override : config.overrides()) {
            int mask = 0;
            if (override.enabled() != null) {
                mask |= OVERRIDE_ENABLED | (override.enabled() ? OVERRIDE_ENABLED_VALUE : 0);
            }
            if (override.async() != null) {
                mask |= OVERRIDE_ASYNC | (override.async() ? OVERRIDE_ASYNC_VALUE : 0);
            }
            if (override.maxConcurrency() != null) {
                mask |= OVERRIDE_MAX_CONCURRENCY;
            }
            if (override.consumerThreads() != null) {
                mask |= OVERRIDE_CONSUMER_THREADS;
            }
            if (override.instanceScoped()) {
                mask |= OVERRIDE_INSTANCE_SCOPED;
            }
            if (override.rateLimit() != null) {
                mask |= OVERRIDE_RATE_LIMIT;
            }
            out.uvar(override.methodIndex()).u8(mask);
            if (override.maxConcurrency() != null) {
                out.uvar(override.maxConcurrency());
            }
            if (override.consumerThreads() != null) {
                out.uvar(override.consumerThreads());
            }
            if (override.rateLimit() != null) {
                out.uvar(override.rateLimit());
            }
        }
    }

    public static ConfigBlock readConfig(WireReader in) {
        long version = in.uvar();
        int count = in.count();
        List<MethodOverride> overrides = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int index = in.uint();
            int mask = in.u8();
            Boolean enabled = (mask & OVERRIDE_ENABLED) != 0 ? (mask & OVERRIDE_ENABLED_VALUE) != 0 : null;
            Boolean async = (mask & OVERRIDE_ASYNC) != 0 ? (mask & OVERRIDE_ASYNC_VALUE) != 0 : null;
            Integer max = (mask & OVERRIDE_MAX_CONCURRENCY) != 0 ? in.uint() : null;
            Integer consumers = (mask & OVERRIDE_CONSUMER_THREADS) != 0 ? in.uint() : null;
            Integer rate = (mask & OVERRIDE_RATE_LIMIT) != 0 ? in.uint() : null;
            overrides.add(new MethodOverride(index, enabled, async, max, consumers, (mask & OVERRIDE_INSTANCE_SCOPED) != 0, rate));
        }
        return new ConfigBlock(version, overrides);
    }

    // ---- helpers --------------------------------------------------------------------------

    private static void header(WireWriter out, int type) {
        out.u8(AslAdminProtocol.VERSION).u8(type);
    }

    private static WireReader open(byte[] body, int expectedType) {
        WireReader in = new WireReader(body);
        int version = in.u8();
        if (version != AslAdminProtocol.VERSION) {
            throw new AslProtocolException("Unsupported protocol version: " + version);
        }
        int type = in.u8();
        if (type != expectedType) {
            throw new AslProtocolException("Expected message type " + expectedType + " but was " + type);
        }
        return in;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AslProtocolException(field + " is required");
        }
        return value;
    }

    private static long nonNegative(long value) {
        return Math.max(0L, value);
    }
}
