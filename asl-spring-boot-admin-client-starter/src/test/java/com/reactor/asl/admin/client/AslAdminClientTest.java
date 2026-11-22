package com.reactor.asl.admin.client;

import com.reactor.asl.admin.protocol.AslAdminCodec;
import com.reactor.asl.admin.protocol.AslAdminMessages.Ack;
import com.reactor.asl.admin.protocol.AslAdminMessages.Beat;
import com.reactor.asl.admin.protocol.AslAdminMessages.BeatReply;
import com.reactor.asl.admin.protocol.AslAdminMessages.Command;
import com.reactor.asl.admin.protocol.AslAdminMessages.ConfigBlock;
import com.reactor.asl.admin.protocol.AslAdminMessages.MethodOverride;
import com.reactor.asl.admin.protocol.AslAdminMessages.MethodSample;
import com.reactor.asl.admin.protocol.AslAdminMessages.Register;
import com.reactor.asl.admin.protocol.AslAdminMessages.RegisterReply;
import com.reactor.asl.admin.protocol.AslAdminProtocol;
import com.reactor.asl.admin.protocol.AslAdminSigner;
import com.reactor.asl.core.AsyncExecutionEngine;
import com.reactor.asl.core.AsyncMethodBinding;
import com.reactor.asl.core.ExecutionMode;
import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.MethodBufferEntryView;
import com.reactor.asl.core.MethodBufferSnapshot;
import com.reactor.asl.core.MethodDescriptor;
import com.reactor.asl.core.MethodRuntime;
import com.reactor.asl.core.ServiceDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AslAdminClientTest {
    private static final String SECRET = "test-secret-0123456789";
    private static final long NOW = 1_800_000_000_000L;

    private GovernanceRegistry registry;
    private FakeEngine engine;
    private ScriptedTransport transport;
    private MethodRuntime create;
    private MethodRuntime find;

    @BeforeEach
    void setUp() {
        registry = new GovernanceRegistry();
        registry.register(new ServiceDescriptor("OrderService", new MethodDescriptor[]{
                new MethodDescriptor("create(java.lang.String)", "create", true, 10, "busy", true, 1),
                new MethodDescriptor("find(long)", "find", true, 5, "busy", false, 0)
        }));
        create = registry.service("OrderService").methodById("create(java.lang.String)");
        find = registry.service("OrderService").methodById("find(long)");
        engine = new FakeEngine();
        transport = new ScriptedTransport();
    }

    private AslAdminClient client(boolean queueBrowse) {
        return client(queueBrowse, List.of(engine));
    }

    private AslAdminClient client(boolean queueBrowse, List<com.reactor.asl.core.BufferAdminProvider> providers) {
        AslAdminClientSettings settings = new AslAdminClientSettings("orders", "orders-1", "test", 15_000, 60_000, queueBrowse, 10, 32);
        return new AslAdminClient(settings, registry, providers, transport, new AslAdminSigner(SECRET), () -> NOW);
    }

    @Test
    void registersThenSendsFullBeatThenOnlyChanges() throws Exception {
        AslAdminClient client = client(false);
        transport.reply(200, AslAdminCodec.encode(new RegisterReply(42, 15_000, null)));
        transport.reply(204, new byte[0]);

        assertEquals(15_000, client.runOnce());
        assertTrue(client.isRegistered());

        Register register = AslAdminCodec.decodeRegister(transport.bodies.get(0));
        assertEquals("orders", register.application());
        assertEquals(2, register.methods().size());
        assertEquals("create(java.lang.String)", register.methods().get(0).methodId());
        assertTrue(register.methods().get(0).hasQueue());
        assertFalse(register.methods().get(1).asyncCapable());
        assertTrue(new AslAdminSigner(SECRET).verify(transport.auths.get(0), transport.bodies.get(0), NOW / 1000));

        Beat first = AslAdminCodec.decodeBeat(transport.bodies.get(1));
        assertTrue(first.full());
        assertEquals(42, first.sessionId());
        assertEquals(2, first.samples().size());
        assertEquals(10, first.samples().get(0).value(MethodSample.MAX_CONCURRENCY));
        assertEquals(3, first.samples().get(0).value(MethodSample.QUEUE_PENDING));

        transport.reply(204, new byte[0]);
        client.runOnce();
        byte[] idle = transport.bodies.get(2);
        Beat idleBeat = AslAdminCodec.decodeBeat(idle);
        assertTrue(idleBeat.samples().isEmpty());
        assertTrue(idle.length <= 32, "idle beat was " + idle.length + " bytes");

        assertTrue(find.tryAcquire());
        find.onSuccess();
        find.release();
        transport.reply(204, new byte[0]);
        client.runOnce();
        Beat changed = AslAdminCodec.decodeBeat(transport.bodies.get(3));
        assertEquals(1, changed.samples().size());
        MethodSample sample = changed.samples().get(0);
        assertEquals(1, sample.methodIndex());
        assertTrue(sample.has(MethodSample.SUCCESS));
        assertFalse(sample.has(MethodSample.MAX_CONCURRENCY));
        assertEquals(1, sample.value(MethodSample.SUCCESS));
    }

    @Test
    void appliesDesiredConfigAndReturnsToBaselineWhenOverrideIsRemoved() throws Exception {
        registry.attachAsyncExecutionEngine(engine);
        AslAdminClient client = client(false);
        ConfigBlock config = new ConfigBlock(7, List.of(
                new MethodOverride(0, null, true, 15, 3, true),
                new MethodOverride(0, true, false, 20, null),
                new MethodOverride(1, false, null, null, null)
        ));
        transport.reply(200, AslAdminCodec.encode(new RegisterReply(5, 15_000, config)));
        transport.reply(204, new byte[0]);
        client.runOnce();

        assertEquals(ExecutionMode.ASYNC, create.executionMode(), "instance-scoped value wins over the application-wide one");
        assertEquals(15, create.policy().maxConcurrency());
        assertEquals(3, create.consumerThreads());
        assertFalse(find.policy().enabled());
        assertEquals(1, engine.applied);
        Beat beat = AslAdminCodec.decodeBeat(transport.bodies.get(1));
        assertEquals(7, beat.appliedConfigVersion());
        assertNull(beat.configError());

        transport.reply(200, AslAdminCodec.encode(new BeatReply(false, 2_000,
                new ConfigBlock(8, List.of(new MethodOverride(0, null, null, 30, null))), List.of())));
        assertEquals(2_000, client.runOnce());
        assertEquals(30, create.policy().maxConcurrency());
        assertEquals(ExecutionMode.SYNC, create.executionMode());
        assertEquals(1, create.consumerThreads());
        assertTrue(find.policy().enabled());
        assertEquals(8, client.appliedConfigVersion());
        assertEquals(2_000, client.currentIntervalMillis());
    }

    @Test
    void reportsConfigErrorsOnce() throws Exception {
        AslAdminClient client = client(false);
        ConfigBlock config = new ConfigBlock(3, List.of(
                new MethodOverride(0, null, true, null, null),
                new MethodOverride(1, null, true, null, null),
                new MethodOverride(9, true, null, null, null)
        ));
        transport.reply(200, AslAdminCodec.encode(new RegisterReply(5, 15_000, config)));
        transport.reply(204, new byte[0]);
        transport.reply(204, new byte[0]);
        client.runOnce();
        client.runOnce();

        Beat withError = AslAdminCodec.decodeBeat(transport.bodies.get(1));
        assertNotNull(withError.configError());
        assertTrue(withError.configError().length() <= 32);
        assertEquals(3, withError.appliedConfigVersion());
        assertNull(AslAdminCodec.decodeBeat(transport.bodies.get(2)).configError());
        assertEquals(ExecutionMode.SYNC, create.executionMode());
    }

    @Test
    void executesCommandsOnceAndAcksInNextBeat() throws Exception {
        registry.attachAsyncExecutionEngine(engine);
        AslAdminClient client = client(true);
        register(client);
        assertTrue(create.tryAcquire());
        create.onError(new IllegalStateException("x"));
        create.release();

        List<Command> commands = List.of(
                new Command(1, Command.BROWSE_QUEUE, 0, null, 100),
                new Command(2, Command.CLEAR_QUEUE, 0, null, 0),
                new Command(3, Command.REPLAY_ENTRY, 0, "7", 0),
                new Command(4, Command.DELETE_ENTRY, 0, "8", 0),
                new Command(5, Command.RESET_OBSERVATIONS, 0, null, 0),
                new Command(6, Command.CLEAR_QUEUE, 1, null, 0),
                new Command(7, 99, 0, null, 0),
                new Command(8, Command.CLEAR_QUEUE, 42, null, 0)
        );
        transport.reply(200, AslAdminCodec.encode(new BeatReply(false, 0, null, commands)));
        assertEquals(200, client.runOnce());
        assertEquals(0, create.errorCount());

        transport.reply(200, AslAdminCodec.encode(new BeatReply(false, 0, null, List.of(new Command(2, Command.CLEAR_QUEUE, 0, null, 0)))));
        client.runOnce();
        Beat acks = AslAdminCodec.decodeBeat(transport.bodies.get(transport.bodies.size() - 1));
        assertEquals(8, acks.acks().size());
        Ack browse = acks.acks().get(0);
        assertEquals(Ack.OK, browse.status());
        assertEquals(1, browse.entries().size());
        assertEquals(10, engine.lastSnapshotLimit);
        assertEquals(32, browse.entries().get(0).lastError().length());
        assertEquals("summary", browse.entries().get(0).summary());
        assertEquals("Order", browse.entries().get(0).payloadType());
        assertEquals("1", browse.entries().get(0).payloadVersion());
        assertEquals("jackson", browse.entries().get(0).codecId());
        assertEquals("IllegalStateException", browse.entries().get(0).lastErrorType());
        assertEquals(Ack.OK, acks.acks().get(1).status());
        assertEquals(3, acks.acks().get(1).value());
        assertEquals(1, acks.acks().get(2).value());
        assertEquals(1, acks.acks().get(3).value());
        assertEquals(Ack.OK, acks.acks().get(4).status());
        assertEquals(Ack.REJECTED, acks.acks().get(5).status());
        assertEquals(Ack.REJECTED, acks.acks().get(6).status());
        assertEquals(Ack.REJECTED, acks.acks().get(7).status());
        assertEquals(1, engine.clears, "a re-delivered command must not run twice");

        transport.reply(204, new byte[0]);
        client.runOnce();
        assertEquals(1, AslAdminCodec.decodeBeat(transport.bodies.get(transport.bodies.size() - 1)).acks().size());
    }

    @Test
    void forgetsExecutedCommandsWhenANewSessionStarts() throws Exception {
        registry.attachAsyncExecutionEngine(engine);
        AslAdminClient client = client(true);
        register(client);
        transport.reply(200, AslAdminCodec.encode(new BeatReply(false, 0, null, List.of(new Command(1, Command.CLEAR_QUEUE, 0, null, 0)))));
        client.runOnce();
        assertEquals(1, engine.clears);

        // The admin server restarted: the old session is gone and the new server may reuse command ids.
        transport.reply(410, new byte[0]);
        client.runOnce();
        transport.reply(200, AslAdminCodec.encode(new RegisterReply(43, 15_000, null)));
        transport.reply(200, AslAdminCodec.encode(new BeatReply(false, 0, null, List.of(new Command(1, Command.CLEAR_QUEUE, 0, null, 0)))));
        client.runOnce();
        assertEquals(2, engine.clears, "a command of a new session runs even if an old one had the same id");
    }

    @Test
    void browseIsRejectedUnlessEnabledAndFailuresAreReported() throws Exception {
        AslAdminClient client = client(false);
        register(client);
        engine.failClear = true;
        transport.reply(200, AslAdminCodec.encode(new BeatReply(false, 0, null, List.of(
                new Command(1, Command.BROWSE_QUEUE, 0, null, 5),
                new Command(2, Command.CLEAR_QUEUE, 0, null, 0)))));
        client.runOnce();
        transport.reply(204, new byte[0]);
        client.runOnce();
        Beat beat = AslAdminCodec.decodeBeat(transport.bodies.get(transport.bodies.size() - 1));
        assertEquals(Ack.REJECTED, beat.acks().get(0).status());
        assertTrue(beat.acks().get(0).message().contains("queue-browse"));
        assertEquals(Ack.FAILED, beat.acks().get(1).status());
    }

    @Test
    void reRegistersAfter410AndBacksOffWhileUnreachable() throws Exception {
        AslAdminClient client = client(false);
        register(client);

        transport.reply(410, new byte[0]);
        assertEquals(1_000, client.runOnce());
        assertFalse(client.isRegistered());

        transport.fail();
        assertEquals(30_000, client.runOnce());
        transport.fail();
        assertEquals(60_000, client.runOnce());
        transport.reply(503, new byte[0]);
        assertEquals(60_000, client.runOnce());

        transport.reply(200, AslAdminCodec.encode(new RegisterReply(43, 15_000, null)));
        transport.reply(204, new byte[0]);
        assertEquals(15_000, client.runOnce());
        Beat beat = AslAdminCodec.decodeBeat(transport.bodies.get(transport.bodies.size() - 1));
        assertEquals(43, beat.sessionId());
        assertTrue(beat.full());
    }

    @Test
    void failedBeatForcesFullSnapshotAndServerCanAskForOne() throws Exception {
        AslAdminClient client = client(false);
        register(client);
        transport.fail();
        client.runOnce();
        transport.reply(200, AslAdminCodec.encode(new BeatReply(true, 0, null, List.of())));
        client.runOnce();
        assertTrue(AslAdminCodec.decodeBeat(transport.bodies.get(transport.bodies.size() - 1)).full());
        transport.reply(204, new byte[0]);
        client.runOnce();
        assertTrue(AslAdminCodec.decodeBeat(transport.bodies.get(transport.bodies.size() - 1)).full());
        transport.reply(204, new byte[0]);
        client.runOnce();
        assertFalse(AslAdminCodec.decodeBeat(transport.bodies.get(transport.bodies.size() - 1)).full());
    }

    @Test
    void newServiceTriggersReRegistrationWithStableIndexes() throws Exception {
        AslAdminClient client = client(false, List.of());
        register(client);
        registry.register(new ServiceDescriptor("AuditService", new MethodDescriptor[]{
                new MethodDescriptor("write()", "write", true, 1, "busy", false, 0)
        }));
        assertEquals(0, client.runOnce());
        transport.reply(200, AslAdminCodec.encode(new RegisterReply(50, 15_000, null)));
        transport.reply(204, new byte[0]);
        client.runOnce();
        Register register = AslAdminCodec.decodeRegister(transport.bodies.get(transport.bodies.size() - 2));
        assertEquals(3, register.methods().size());
        assertEquals("create(java.lang.String)", register.methods().get(0).methodId());
        assertEquals("AuditService", register.methods().get(2).serviceId());
        assertFalse(register.methods().get(0).hasQueue());
    }

    @Test
    void closeSendsLeavingBeatAndStartIsIdempotent() throws Exception {
        AslAdminClient client = client(false);
        register(client);
        transport.reply(204, new byte[0]);
        client.close();
        Beat leaving = AslAdminCodec.decodeBeat(transport.bodies.get(transport.bodies.size() - 1));
        assertTrue(leaving.leaving());
        assertFalse(client.isRegistered());

        AslAdminClient background = client(false);
        transport.failAlways = true;
        background.start();
        background.start();
        background.close();
    }

    private void register(AslAdminClient client) throws Exception {
        transport.reply(200, AslAdminCodec.encode(new RegisterReply(42, 15_000, null)));
        transport.reply(204, new byte[0]);
        client.runOnce();
    }

    private static final class ScriptedTransport implements AslAdminTransport {
        final List<byte[]> bodies = new ArrayList<>();
        final List<String> auths = new ArrayList<>();
        final List<String> paths = new ArrayList<>();
        final Deque<Object> script = new ArrayDeque<>();
        volatile boolean failAlways;

        void reply(int status, byte[] body) {
            script.addLast(new Response(status, body));
        }

        void fail() {
            script.addLast(new IOException("connection refused"));
        }

        @Override
        public synchronized Response post(String path, byte[] body, String authHeader) throws IOException {
            if (failAlways) {
                throw new IOException("down");
            }
            paths.add(path);
            bodies.add(body);
            auths.add(authHeader);
            Object next = script.pollFirst();
            if (next == null) {
                return new Response(204, new byte[0]);
            }
            if (next instanceof IOException exception) {
                throw exception;
            }
            Response response = (Response) next;
            if (path.equals(AslAdminProtocol.REGISTER_PATH) && response.status() == 204) {
                throw new IllegalStateException("script out of order");
            }
            return response;
        }
    }

    private static final class FakeEngine implements AsyncExecutionEngine {
        int applied;
        int clears;
        int lastSnapshotLimit = -1;
        boolean failClear;

        @Override
        public void register(MethodRuntime runtime, AsyncMethodBinding binding) {
        }

        @Override
        public void enqueue(MethodRuntime runtime, Object[] arguments) {
        }

        @Override
        public boolean supports(String serviceId, String methodId) {
            return methodId.startsWith("create");
        }

        @Override
        public MethodBufferSnapshot snapshot(String serviceId, String methodId, int limit) {
            if (limit > 0) {
                lastSnapshotLimit = limit;
            }
            List<MethodBufferEntryView> entries = limit == 0 ? List.of() : List.of(new MethodBufferEntryView(
                    "7", "FAILED", "summary", Instant.ofEpochMilli(5), 3, "x".repeat(100), "IllegalStateException",
                    "BUSINESS", "jackson", "Order", "1", 2));
            return new MethodBufferSnapshot(serviceId, methodId, true, 3, 1, 0, entries);
        }

        @Override
        public int clear(String serviceId, String methodId) {
            if (failClear) {
                throw new IllegalStateException("store unavailable");
            }
            clears++;
            return 3;
        }

        @Override
        public boolean delete(String serviceId, String methodId, String entryId) {
            return "8".equals(entryId);
        }

        @Override
        public boolean replay(String serviceId, String methodId, String entryId) {
            return "7".equals(entryId);
        }

        @Override
        public void applyRuntime(MethodRuntime runtime) {
            applied++;
        }
    }
}
