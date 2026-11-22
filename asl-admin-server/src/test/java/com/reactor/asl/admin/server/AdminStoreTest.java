package com.reactor.asl.admin.server;

import com.reactor.asl.admin.protocol.AslAdminMessages.MethodSample;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminStoreTest {
    private static final MethodKey CREATE = new MethodKey("com.acme.OrderService", "create(java.lang.String, int[])");
    private static final MethodKey FIND = new MethodKey("com.acme.OrderService", "find(long)");

    @TempDir
    Path directory;

    private ServerConfig config(Map<String, String> extra) {
        Map<String, String> values = new HashMap<>(Map.of("secret", "0123456789abcdef-x", "security.mode", "NONE",
                "storage.path", directory.resolve("admin.db").toString(), "audit.max-entries", "3",
                "history.resolution", "10s", "history.retention", "30s", "history.max-series", "4"));
        values.putAll(extra);
        return ServerConfig.of(values);
    }

    private static long[] values(long inFlight, long success) {
        long[] values = new long[MethodSample.FIELD_COUNT];
        values[MethodSample.STATE] = MethodSample.STATE_ENABLED;
        values[MethodSample.IN_FLIGHT] = inFlight;
        values[MethodSample.SUCCESS] = success;
        return values;
    }

    @Test
    void overridesAndAuditSurviveRestartAndStayBounded() {
        ServerConfig config = config(Map.of());
        try (AdminStore store = new AdminStore(config)) {
            store.saveOverrides("orders", Map.of(CREATE, new MethodSetting(true, false, 12, 0)), 77);
            store.saveOverrides("billing", Map.of(CREATE, new MethodSetting(null, null, null, 3)), 78);
            store.saveOverrides("billing", Map.of(), 79);
            for (int i = 0; i < 5; i++) {
                store.appendAudit(new AdminStore.AuditEntry(1_000 + i, "alice", "orders", "override.set", "detail " + i));
            }
            assertEquals(3, store.recentAudit(10).size());
            assertEquals("detail 4", store.recentAudit(1).get(0).detail());
            assertThrows(IllegalArgumentException.class, () -> store.saveOverrides("x",
                    Map.of(new MethodKey("bad\tservice", "m()"), new MethodSetting(true, null, null, null)), 80));
        }
        try (AdminStore reopened = new AdminStore(config)) {
            assertEquals(79, reopened.loadVersion());
            Map<String, Map<MethodKey, MethodSetting>> overrides = reopened.loadOverrides();
            assertEquals(1, overrides.size());
            assertEquals(new MethodSetting(true, false, 12, 0), overrides.get("orders").get(CREATE));
            reopened.trimAudit(1_004);
            assertEquals(1, reopened.recentAudit(10).size());
            assertTrue(reopened.persistent());
            assertTrue(reopened.sizeBytes() >= AdminStore.MIN_FILE_BYTES);
        }
    }

    @Test
    void historyIsAFixedRingOfRawValuesPerReplica() {
        ServerConfig config = config(Map.of());
        try (AdminStore store = new AdminStore(config)) {
            assertEquals(3, store.slots());
            for (long bucket = 100; bucket < 105; bucket++) {
                store.recordHistory(bucket, bucket * 10_000, List.of(
                        new AdminStore.HistorySample("orders", "pod-a", CREATE, values(bucket, bucket * 10)),
                        new AdminStore.HistorySample("orders", "pod-b", CREATE, values(1, bucket)),
                        new AdminStore.HistorySample("orders", "pod-a", FIND, values(0, 0))));
            }
            List<AdminStore.Series> series = store.history("orders", CREATE, 10_000);
            assertEquals(2, series.size());
            assertEquals("pod-a", series.get(0).instance());
            List<AdminStore.Point> points = series.get(0).points();
            assertEquals(3, points.size(), "only retention / resolution points are kept");
            assertEquals(1_020_000, points.get(0).timeMillis());
            assertEquals(104, points.get(2).values()[MethodSample.IN_FLIGHT]);
            assertEquals(1_040, points.get(2).values()[MethodSample.SUCCESS]);
            assertEquals(3, store.seriesCount());
            assertTrue(store.history("billing", CREATE, 10_000).isEmpty());

            long sizeAfterWarmup = store.sizeBytes();
            for (long bucket = 105; bucket < 600; bucket++) {
                store.recordHistory(bucket, bucket * 10_000, List.of(new AdminStore.HistorySample("orders", "pod-a", CREATE, values(bucket, bucket))));
            }
            assertEquals(sizeAfterWarmup, store.sizeBytes(), "overwriting the ring does not grow the file");
            assertEquals(1, store.seriesCount(), "series without new points expire after the retention");
        }
    }

    @Test
    void historySurvivesRestart() {
        try (AdminStore store = new AdminStore(config(Map.of()))) {
            store.recordHistory(7, 70_000, List.of(new AdminStore.HistorySample("orders", "pod-a", CREATE, values(5, 50))));
        }
        try (AdminStore reopened = new AdminStore(config(Map.of()))) {
            assertEquals(1, reopened.seriesCount());
            assertEquals(5, reopened.history("orders", CREATE, 10_000).get(0).points().get(0).values()[MethodSample.IN_FLIGHT]);
        }
    }

    @Test
    void seriesLimitAndLayoutChangeAreHandled() {
        try (AdminStore store = new AdminStore(config(Map.of()))) {
            List<AdminStore.HistorySample> many = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                many.add(new AdminStore.HistorySample("orders", "pod-" + i, CREATE, values(i, i)));
            }
            store.recordHistory(1, 10_000, many);
            assertEquals(4, store.seriesCount());
            assertTrue(store.seriesLimitReached());
        }
        try (AdminStore changed = new AdminStore(config(Map.of("history.retention", "60s")))) {
            assertEquals(0, changed.seriesCount(), "a different ring layout starts empty");
        }
    }

    @Test
    void memoryModeAndDisabledFeaturesTouchNothing() {
        ServerConfig memory = config(Map.of("storage.mode", "MEMORY", "history.enabled", "false", "audit.enabled", "false"));
        try (AdminStore store = new AdminStore(memory)) {
            assertFalse(store.persistent());
            assertEquals(0, store.sizeBytes());
            assertFalse(store.historyEnabled());
            store.recordHistory(1, 1, List.of(new AdminStore.HistorySample("a", "b", CREATE, values(1, 1))));
            assertTrue(store.history("a", CREATE, 1_000).isEmpty());
            store.appendAudit(new AdminStore.AuditEntry(1, "a", "b", "c", "d"));
            assertTrue(store.recentAudit(5).isEmpty());
            store.saveOverrides("a", Map.of(CREATE, new MethodSetting(true, null, null, null)), 5);
            assertEquals(1, store.loadOverrides().size());
        }
        assertFalse(directory.resolve("admin.db").toFile().exists());
    }

    @Test
    void settingTextFormAndJsonEscaping() {
        assertEquals("", MethodSetting.NONE.toText());
        assertEquals(new MethodSetting(false, null, null, 2), MethodSetting.parse("enabled=false; consumers=2"));
        assertThrows(IllegalArgumentException.class, () -> MethodSetting.parse("enabled"));
        assertThrows(IllegalArgumentException.class, () -> MethodSetting.parse("colour=red"));
        String json = new Json().beginObject().field("a", "q\"<\\\n\t\u0001").field("n", (String) null).field("b", Boolean.TRUE)
                .field("i", (Integer) null).name("list").beginArray().value(1).value(2).endArray()
                .setting("s", null).setting("t", new MethodSetting(true, null, 3, null)).endObject().toString();
        assertEquals("{\"a\":\"q\\\"\\u003c\\\\\\n\\t\\u0001\",\"n\":null,\"b\":true,\"i\":null,\"list\":[1,2],\"s\":null,"
                + "\"t\":{\"enabled\":true,\"async\":null,\"maxConcurrency\":3,\"consumerThreads\":null}}", json);
        assertArrayEquals(new long[]{1}, new long[]{1});
    }
}
