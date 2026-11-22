package com.reactor.asl.mapdb;

import com.reactor.asl.core.AsyncAdmissionRejectedException;
import org.junit.jupiter.api.Test;
import org.mapdb.DB;
import org.mapdb.DBMaker;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedStoreWriterTest {
    @Test
    void closeCanBeRetriedAfterAnInFlightMutationMissesTheShutdownDeadline() throws Exception {
        MapDbAsyncExecutionOptions options = MapDbAsyncExecutionOptions.builder()
                .workerShutdownAwaitMillis(25L)
                .writerOperationTimeoutMillis(5_000L)
                .build();
        DB db = DBMaker.memoryDB().transactionEnable().make();
        BoundedStoreWriter writer = new BoundedStoreWriter(new MapDbQueueStore(db, options), options);
        CountDownLatch mutationStarted = new CountDownLatch(1);
        CountDownLatch releaseMutation = new CountDownLatch(1);

        try {
            CompletableFuture<String> admitted = CompletableFuture.supplyAsync(() -> writer.execute(() -> {
                mutationStarted.countDown();
                awaitUninterruptibly(releaseMutation);
                return "committed";
            }, 0L));
            assertTrue(mutationStarted.await(5, TimeUnit.SECONDS));

            assertThrows(IllegalStateException.class, writer::close);
            assertFalse(writer.isAccepting());

            releaseMutation.countDown();
            assertEquals("committed", admitted.get(5, TimeUnit.SECONDS));
            writer.close();
        } finally {
            releaseMutation.countDown();
            try {
                writer.close();
            } catch (IllegalStateException ignored) {
                // The assertion above covers the bounded timeout; release allows the retry to finish.
            }
            db.close();
        }
    }

    @Test
    void blockedAdmissionDoesNotPreventShutdownFromClosingTheGate() throws Exception {
        MapDbAsyncExecutionOptions options = MapDbAsyncExecutionOptions.builder()
                .writerQueueCapacity(1)
                .commitBatchSize(1)
                .workerShutdownAwaitMillis(5_000L)
                .writerOperationTimeoutMillis(10_000L)
                .build();
        DB db = DBMaker.memoryDB().transactionEnable().make();
        BoundedStoreWriter writer = new BoundedStoreWriter(new MapDbQueueStore(db, options), options);
        CountDownLatch mutationStarted = new CountDownLatch(1);
        CountDownLatch releaseMutation = new CountDownLatch(1);

        try {
            CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> writer.execute(() -> {
                mutationStarted.countDown();
                awaitUninterruptibly(releaseMutation);
                return "first";
            }, 0L));
            assertTrue(mutationStarted.await(5, TimeUnit.SECONDS));

            CompletableFuture<String> second = CompletableFuture.supplyAsync(
                    () -> writer.execute(() -> "second", 0L)
            );
            waitForQueueDepth(writer, 1);
            CompletableFuture<String> blockedAdmission = CompletableFuture.supplyAsync(
                    () -> writer.execute(() -> "third", 30_000L)
            );
            Thread.sleep(50L);
            assertFalse(blockedAdmission.isDone());

            CompletableFuture<Void> closing = CompletableFuture.runAsync(writer::close);
            waitUntilStoppedAccepting(writer);

            releaseMutation.countDown();
            assertEquals("first", first.get(5, TimeUnit.SECONDS));
            assertEquals("second", second.get(5, TimeUnit.SECONDS));
            try {
                blockedAdmission.join();
            } catch (CompletionException exception) {
                assertTrue(exception.getCause() instanceof AsyncAdmissionRejectedException);
                assertEquals(
                        AsyncAdmissionRejectedException.Reason.ENGINE_CLOSED,
                        ((AsyncAdmissionRejectedException) exception.getCause()).reason()
                );
            }
            closing.get(5, TimeUnit.SECONDS);
        } finally {
            releaseMutation.countDown();
            if (writer.isAccepting()) {
                writer.close();
            }
            db.close();
        }
    }

    @Test
    void closeDrainsAnAdmittedCommandAndRejectsLateAdmissions() throws Exception {
        MapDbAsyncExecutionOptions options = MapDbAsyncExecutionOptions.builder()
                .workerShutdownAwaitMillis(5_000L)
                .build();
        DB db = DBMaker.memoryDB().transactionEnable().make();
        BoundedStoreWriter writer = new BoundedStoreWriter(new MapDbQueueStore(db, options), options);
        CountDownLatch mutationStarted = new CountDownLatch(1);
        CountDownLatch releaseMutation = new CountDownLatch(1);

        try {
            CompletableFuture<String> admitted = CompletableFuture.supplyAsync(() -> writer.execute(() -> {
                mutationStarted.countDown();
                awaitUninterruptibly(releaseMutation);
                return "committed";
            }, 0L));
            assertTrue(mutationStarted.await(5, TimeUnit.SECONDS));

            CompletableFuture<Void> closing = CompletableFuture.runAsync(writer::close);
            waitUntilStoppedAccepting(writer);

            AsyncAdmissionRejectedException rejection = assertThrows(
                    AsyncAdmissionRejectedException.class,
                    () -> writer.execute(() -> "late", 0L)
            );
            assertEquals(AsyncAdmissionRejectedException.Reason.ENGINE_CLOSED, rejection.reason());

            releaseMutation.countDown();
            assertEquals("committed", admitted.get(5, TimeUnit.SECONDS));
            closing.get(5, TimeUnit.SECONDS);
            assertFalse(writer.isAccepting());
        } finally {
            releaseMutation.countDown();
            if (writer.isAccepting()) {
                writer.close();
            }
            db.close();
        }
    }

    private static void waitUntilStoppedAccepting(BoundedStoreWriter writer) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (writer.isAccepting() && System.nanoTime() < deadline) {
            Thread.sleep(1L);
        }
        assertFalse(writer.isAccepting());
    }

    private static void waitForQueueDepth(BoundedStoreWriter writer, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (writer.metrics().queueDepth() != expected && System.nanoTime() < deadline) {
            Thread.sleep(1L);
        }
        assertEquals(expected, writer.metrics().queueDepth());
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
