package com.reactor.asl.mapdb;

import com.reactor.asl.core.AsyncAdmissionRejectedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.HTreeMap;
import org.mapdb.Serializer;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapDbIdempotencyCounterTest {
    private static final String SERVICE_ID = "counter.service";
    private static final String METHOD_ID = "publish(java.lang.String)";

    @TempDir
    Path tempDir;

    @Test
    void counterTracksIndexThroughEnqueueCompleteDeleteAndRestart() {
        Path path = tempDir.resolve("counter.db");
        MapDbAsyncExecutionOptions options = MapDbAsyncExecutionOptions.builder().build();
        try (DB db = DBMaker.fileDB(path.toFile()).transactionEnable().make()) {
            MapDbQueueStore store = new MapDbQueueStore(db, options);
            long first = store.enqueue(invocation("k1")).invocationId();
            long second = store.enqueue(invocation("k2")).invocationId();
            store.enqueue(invocation("k1")); // deduplicated: no new entry
            store.enqueue(invocation(null)); // non-idempotent: not counted
            assertEquals(2L, store.idempotencyEntries());

            QueueStore.ClaimedInvocation claimed = store.claimNext(SERVICE_ID, METHOD_ID, System.currentTimeMillis())
                    .orElseThrow();
            assertEquals(first, claimed.invocationId());
            assertTrue(store.complete(claimed.invocationId(), claimed.leaseToken()));
            assertEquals(2L, store.idempotencyEntries(), "tombstone keeps the key reserved");

            assertTrue(store.delete(SERVICE_ID, METHOD_ID, second));
            assertEquals(1L, store.idempotencyEntries());
        }
        try (DB db = DBMaker.fileDB(path.toFile()).transactionEnable().make()) {
            MapDbQueueStore reopened = new MapDbQueueStore(db, options);
            assertEquals(1L, reopened.idempotencyEntries());
        }
    }

    @Test
    void migratesCounterForStoresCreatedWithoutIt() {
        Path path = tempDir.resolve("migrate.db");
        MapDbAsyncExecutionOptions options = MapDbAsyncExecutionOptions.builder().build();
        try (DB db = DBMaker.fileDB(path.toFile()).transactionEnable().make()) {
            MapDbQueueStore store = new MapDbQueueStore(db, options);
            store.enqueue(invocation("a"));
            store.enqueue(invocation("b"));
            store.enqueue(invocation("c"));
            // Simulate a pre-counter store by deleting the metadata key.
            HTreeMap<String, Long> metadata = db.hashMap("asl.v2.metadata", Serializer.STRING, Serializer.LONG)
                    .createOrOpen();
            Optional.ofNullable(metadata.remove("idempotencyEntries"))
                    .orElseThrow(() -> new AssertionError("metadata map name changed"));
            db.commit();
        }
        try (DB db = DBMaker.fileDB(path.toFile()).transactionEnable().make()) {
            assertEquals(3L, new MapDbQueueStore(db, options).idempotencyEntries());
        }
    }

    @Test
    void capacityLimitUsesCounter() {
        MapDbAsyncExecutionOptions options = MapDbAsyncExecutionOptions.builder().maxIdempotencyEntries(2).build();
        try (DB db = DBMaker.memoryDB().transactionEnable().make()) {
            MapDbQueueStore store = new MapDbQueueStore(db, options);
            store.enqueue(invocation("a"));
            store.enqueue(invocation("b"));
            AsyncAdmissionRejectedException rejected = assertThrows(
                    AsyncAdmissionRejectedException.class,
                    () -> store.enqueue(invocation("c"))
            );
            assertEquals(AsyncAdmissionRejectedException.Reason.IDEMPOTENCY_CAPACITY, rejected.reason());
        }
    }

    @Test
    void idempotentEnqueueCostDoesNotGrowWithIndexSize() {
        MapDbAsyncExecutionOptions options = MapDbAsyncExecutionOptions.builder()
                .maxIdempotencyEntries(200_000)
                .globalLimits(new AsyncQueueLimits(200_000, Long.MAX_VALUE))
                .defaultLaneLimits(new AsyncQueueLimits(200_000, Long.MAX_VALUE))
                .build();
        try (DB db = DBMaker.memoryDB().make()) {
            MapDbQueueStore store = new MapDbQueueStore(db, options);
            for (int i = 0; i < 50_000; i++) {
                store.enqueue(invocation("warm-" + i));
            }
            long started = System.nanoTime();
            for (int i = 0; i < 200; i++) {
                store.enqueue(invocation("measured-" + i));
            }
            long perEnqueueMicros = (System.nanoTime() - started) / 200 / 1_000;
            // Before the counter, HTreeMap.size() alone cost ~10 ms at this size.
            assertTrue(perEnqueueMicros < 2_000, () -> "idempotent enqueue took " + perEnqueueMicros + " us");
        }
    }

    private static QueueStore.NewInvocation invocation(String idempotencyKey) {
        return new QueueStore.NewInvocation(
                SERVICE_ID, METHOD_ID, "codec", new byte[]{1}, null, null, null,
                idempotencyKey, System.currentTimeMillis()
        );
    }
}
