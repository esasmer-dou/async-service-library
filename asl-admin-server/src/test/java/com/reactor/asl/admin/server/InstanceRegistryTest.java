package com.reactor.asl.admin.server;

import com.reactor.asl.admin.protocol.AslAdminMessages.Ack;
import com.reactor.asl.admin.protocol.AslAdminMessages.Beat;
import com.reactor.asl.admin.protocol.AslAdminMessages.BeatReply;
import com.reactor.asl.admin.protocol.AslAdminMessages.Command;
import com.reactor.asl.admin.protocol.AslAdminMessages.MethodInfo;
import com.reactor.asl.admin.protocol.AslAdminMessages.MethodOverride;
import com.reactor.asl.admin.protocol.AslAdminMessages.MethodSample;
import com.reactor.asl.admin.protocol.AslAdminMessages.QueueEntry;
import com.reactor.asl.admin.protocol.AslAdminMessages.Register;
import com.reactor.asl.admin.protocol.AslAdminMessages.RegisterReply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceRegistryTest {
    private static final MethodKey CREATE = new MethodKey("OrderService", "create(java.lang.String)");
    private static final MethodKey FIND = new MethodKey("OrderService", "find(long)");
    private static final List<MethodInfo> CATALOG = List.of(
            new MethodInfo(CREATE.serviceId(), CREATE.methodId(), true, true),
            new MethodInfo(FIND.serviceId(), FIND.methodId(), false, false));

    @TempDir
    Path directory;

    private ServerConfig config;
    private AdminStore store;
    private InstanceRegistry registry;
    private final long now = 1_800_000_000_000L;

    @BeforeEach
    void setUp() {
        config = config(Map.of("storage.path", directory.resolve("admin.db").toString()));
        store = new AdminStore(config);
        registry = new InstanceRegistry(config, store, now);
    }

    @AfterEach
    void closeStore() {
        store.close();
    }

    @Test
    void historySamplesAreTheRawValues() {
        RegisterReply a = register("orders-a");
        registry.beat(new Beat(a.sessionId(), 1, 0, Beat.FULL, null, List.of(), List.of(full(0, true, false, 9, 4))), now);
        List<AdminStore.HistorySample> samples = registry.historySamples();
        assertEquals(1, samples.size());
        assertEquals("orders-a", samples.get(0).instance());
        assertEquals(4, samples.get(0).values()[MethodSample.IN_FLIGHT]);
    }

    private static ServerConfig config(Map<String, String> extra) {
        Map<String, String> values = new HashMap<>(Map.of("secret", "0123456789abcdef-x", "security.mode", "NONE",
                "commands.max-pending", "3", "commands.ttl", "10s"));
        values.putAll(extra);
        return ServerConfig.of(values);
    }

    private RegisterReply register(String instance) {
        return registry.register(new Register("orders", instance, "test", CATALOG), now);
    }

    private static Beat beat(long session, long seq, long applied, int flags) {
        return new Beat(session, seq, applied, flags, null, List.of(), List.of());
    }

    private static MethodSample full(int index, boolean enabled, boolean async, long max, long inFlight) {
        long[] values = new long[MethodSample.FIELD_COUNT];
        values[MethodSample.STATE] = (enabled ? MethodSample.STATE_ENABLED : 0) | (async ? MethodSample.STATE_ASYNC : 0);
        values[MethodSample.MAX_CONCURRENCY] = max;
        values[MethodSample.IN_FLIGHT] = inFlight;
        values[MethodSample.QUEUE_FAILED] = 1;
        return new MethodSample(index, (1 << MethodSample.FIELD_COUNT) - 1, values);
    }

    private String detail() {
        Json json = new Json();
        registry.writeApplication(json, "orders", now);
        return json.toString();
    }

    @Test
    void relaysRawValuesWithoutComputingTotals() {
        RegisterReply a = register("orders-a");
        assertNull(a.config());
        assertEquals(15_000, a.intervalMillis());
        assertTrue(registry.beat(new Beat(a.sessionId(), 1, 0, Beat.FULL, null, List.of(), List.of(full(0, true, true, 20, 3))), now).isEmpty());
        assertTrue(registry.beat(beat(a.sessionId(), 2, 0, 0), now).isEmpty());

        String json = detail();
        assertTrue(json.contains("\"samples\":[[3,20,0,3,0,0,0,0,1,0],null]"), json);
        Json apps = new Json();
        registry.writeApplications(apps);
        assertTrue(apps.toString().contains("\"queueFailed\":1"), apps.toString());
        assertEquals(1, registry.instanceCount());
        assertEquals(1, registry.applicationCount());
    }

    @Test
    void storesAndRelaysOverridesWithoutMergingThem() {
        RegisterReply a = register("orders-a");
        registry.setOverride("orders", CREATE, null, new MethodSetting(null, true, 20, 2), "alice", now);
        registry.setOverride("orders", CREATE, "orders-a", new MethodSetting(false, null, null, null), "alice", now);

        BeatReply reply = registry.beat(beat(a.sessionId(), 1, 0, Beat.FULL), now);
        List<MethodOverride> overrides = reply.config().overrides();
        assertEquals(2, overrides.size());
        assertFalse(overrides.get(0).instanceScoped());
        assertEquals(20, overrides.get(0).maxConcurrency());
        assertTrue(overrides.get(1).instanceScoped());
        assertEquals(Boolean.FALSE, overrides.get(1).enabled());
        assertNull(overrides.get(1).maxConcurrency(), "the server does not merge; the pod overlays");

        long version = reply.config().version();
        assertTrue(registry.beat(beat(a.sessionId(), 2, version, 0), now).isEmpty());
        assertTrue(detail().contains("\"inSync\":true"));

        RegisterReply late = register("orders-late");
        assertEquals(1, late.config().overrides().size(), "a new replica gets the application-wide override at registration");

        registry.beat(new Beat(a.sessionId(), 3, version + 99, 0, "boom", List.of(), List.of()), now);
        assertTrue(detail().contains("\"configError\":\"boom\""));

        InstanceRegistry restarted = new InstanceRegistry(config, store, now);
        RegisterReply afterRestart = restarted.register(new Register("orders", "orders-a", "t", CATALOG), now);
        assertEquals(1, afterRestart.config().overrides().size(), "only application-wide overrides are persisted");

        registry.setOverride("orders", CREATE, null, MethodSetting.NONE, "alice", now);
        assertTrue(store.loadOverrides().isEmpty());
        assertEquals(3, store.recentAudit(10).size());
    }

    @Test
    void validatesOverrides() {
        register("orders-a");
        assertThrows(IllegalArgumentException.class, () -> registry.setOverride("nope", CREATE, null, MethodSetting.NONE, "a", now));
        assertThrows(IllegalArgumentException.class, () -> registry.setOverride("orders", new MethodKey("X", "y()"), null, MethodSetting.NONE, "a", now));
        assertThrows(IllegalArgumentException.class, () -> registry.setOverride("orders", FIND, null, new MethodSetting(null, true, null, null), "a", now));
        assertThrows(IllegalArgumentException.class, () -> registry.setOverride("orders", CREATE, "ghost", new MethodSetting(true, null, null, null), "a", now));
        registry.setOverride("orders", CREATE, "orders-a", new MethodSetting(true, null, null, null), "a", now);
        registry.setOverride("orders", CREATE, "orders-a", MethodSetting.NONE, "a", now);
        assertTrue(detail().contains("\"overrides\":[null,null]"));
    }

    @Test
    void commandsAreRelayedResentAndAcknowledged() {
        RegisterReply a = register("orders-a");
        RegisterReply b = register("orders-b");
        List<Long> ids = registry.enqueueCommand("orders", "*", CREATE, Command.BROWSE_QUEUE, null, 10, "bob", now);
        assertEquals(2, ids.size());

        BeatReply first = registry.beat(beat(a.sessionId(), 1, 0, Beat.FULL), now);
        assertEquals(1, first.commands().size());
        assertTrue(registry.beat(beat(a.sessionId(), 2, 0, 0), now + 1_000).isEmpty(), "not resent too early");
        assertEquals(1, registry.beat(beat(a.sessionId(), 3, 0, 0), now + 31_000).commands().size());

        Ack ack = new Ack(ids.get(0), Ack.OK, 1, null, List.of(new QueueEntry("9", "FAILED", 3, 0, 5, "TIMEOUT", "late")));
        assertTrue(registry.beat(new Beat(a.sessionId(), 4, 0, 0, null, List.of(ack), List.of()), now + 32_000).isEmpty());
        String json = detail();
        assertTrue(json.contains("\"status\":\"OK\""), json);
        assertTrue(json.contains("\"status\":\"PENDING\""), json);
        assertTrue(json.contains("\"entryId\":\"9\""), json);

        registry.beat(beat(b.sessionId(), 1, 0, Beat.FULL), now);
        registry.maintain(now + 11_000);
        assertTrue(detail().contains("\"status\":\"EXPIRED\""));

        assertThrows(IllegalArgumentException.class, () -> registry.enqueueCommand("orders", "orders-a", FIND, 99, null, 0, "b", now));
        assertThrows(IllegalArgumentException.class, () -> registry.enqueueCommand("orders", "orders-a", FIND, Command.REPLAY_ENTRY, " ", 0, "b", now));
        assertThrows(IllegalArgumentException.class, () -> registry.enqueueCommand("orders", "ghost", FIND, Command.CLEAR_QUEUE, null, 0, "b", now));
        assertThrows(IllegalArgumentException.class, () -> registry.enqueueCommand("orders", "orders-a", new MethodKey("X", "y()"), Command.CLEAR_QUEUE, null, 0, "b", now));
        for (int i = 0; i < 3; i++) {
            registry.enqueueCommand("orders", "orders-a", CREATE, Command.RESET_OBSERVATIONS, null, 0, "b", now);
        }
        assertThrows(InstanceRegistry.CapacityExceededException.class,
                () -> registry.enqueueCommand("orders", "orders-a", CREATE, Command.RESET_OBSERVATIONS, null, 0, "b", now));
    }

    @Test
    void sequenceGapAndUnknownSession() {
        RegisterReply a = register("orders-a");
        registry.beat(beat(a.sessionId(), 1, 0, Beat.FULL), now);
        assertTrue(registry.beat(beat(a.sessionId(), 5, 0, 0), now).sendFull());
        assertTrue(registry.beat(new Beat(a.sessionId(), 6, 0, 0, null, List.of(), List.of(full(7, true, false, 1, 0))), now).sendFull());
        assertNull(registry.beat(beat(123, 1, 0, 0), now));
    }

    @Test
    void watchModeShortensIntervalUntilIdle() {
        RegisterReply a = register("orders-a");
        registry.watch("orders", now);
        registry.watch("unknown", now);
        assertEquals(2_000, registry.beat(beat(a.sessionId(), 1, 0, Beat.FULL), now).intervalMillis());
        assertTrue(registry.beat(beat(a.sessionId(), 2, 0, 0), now + 1).isEmpty());
        assertEquals(15_000, registry.beat(beat(a.sessionId(), 3, 0, 0), now + 61_000).intervalMillis());
    }

    @Test
    void silentLeavingAndReplacedInstances() {
        RegisterReply a = register("orders-a");
        RegisterReply b = register("orders-b");
        assertTrue(registry.beat(beat(a.sessionId(), 1, 0, Beat.LEAVING), now).isEmpty());
        assertNull(registry.beat(beat(a.sessionId(), 2, 0, 0), now));

        RegisterReply b2 = register("orders-b");
        assertNull(registry.beat(beat(b.sessionId(), 1, 0, 0), now));
        assertNotNull(registry.beat(beat(b2.sessionId(), 1, 0, Beat.FULL), now));

        registry.maintain(now + 45_001);
        assertEquals(0, registry.instanceCount());
        assertEquals(0, registry.applicationCount());

        ServerConfig smallConfig = config(Map.of("instance.max-instances", "1", "storage.mode", "MEMORY"));
        InstanceRegistry small = new InstanceRegistry(smallConfig, new AdminStore(smallConfig), now);
        small.register(new Register("orders", "x", "t", CATALOG), now);
        assertThrows(InstanceRegistry.CapacityExceededException.class, () -> small.register(new Register("orders", "y", "t", CATALOG), now));
    }
}
