package com.reactor.asl.mapdb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapDbStoreLifecycleTest {
    private static final int WINDOWS_DELETE_ATTEMPTS = 40;
    private static final long WINDOWS_DELETE_RETRY_MILLIS = 25L;

    @TempDir
    Path tempDir;

    @AfterEach
    void verifyStoreArtifactsAreDeletableAfterClose() throws IOException {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= WINDOWS_DELETE_ATTEMPTS; attempt++) {
            try {
                List<Path> artifacts;
                try (var paths = Files.walk(tempDir)) {
                    artifacts = paths
                            .filter(path -> !path.equals(tempDir))
                            .sorted(Comparator.reverseOrder())
                            .toList();
                }
                for (Path artifact : artifacts) {
                    Files.deleteIfExists(artifact);
                }
                try (var remaining = Files.list(tempDir)) {
                    if (remaining.findAny().isEmpty()) {
                        return;
                    }
                }
            } catch (IOException exception) {
                lastFailure = exception;
            }
            sleepBeforeDeleteRetry();
        }
        throw new IOException("MapDB artifacts remained locked after engine shutdown: " + tempDir, lastFailure);
    }

    @Test
    void reopensAValidStoreAfterCleanShutdown() {
        Path databasePath = tempDir.resolve("valid.db");
        try (MapDbAsyncExecutionEngine ignored = new MapDbAsyncExecutionEngine(databasePath)) {
        }
        try (MapDbAsyncExecutionEngine reopened = new MapDbAsyncExecutionEngine(databasePath)) {
            assertTrue(reopened.startupRecovery().isEmpty());
        }
    }

    @Test
    void failsFastAndPreservesTruncatedStoreByDefault() throws IOException {
        Path databasePath = tempDir.resolve("truncated.db");
        byte[] damaged = {1, 2, 3, 4, 5};
        Files.write(databasePath, damaged);

        MapDbStoreCorruptionException exception = assertThrows(
                MapDbStoreCorruptionException.class,
                () -> new MapDbAsyncExecutionEngine(databasePath)
        );

        assertEquals(databasePath.toAbsolutePath().normalize(), exception.databasePath());
        assertEquals(List.of(1, 2, 3, 4, 5), toUnsigned(Files.readAllBytes(databasePath)));
        assertTrue(corruptArchives().isEmpty());
    }

    @Test
    void archivesVerifiedCorruptionOnlyWhenExplicitlyConfigured() throws IOException {
        Path databasePath = tempDir.resolve("recover.db");
        Files.write(databasePath, new byte[]{1, 2, 3, 4, 5});

        Path archivePath;
        try (MapDbAsyncExecutionEngine recovered = new MapDbAsyncExecutionEngine(
                databasePath,
                MapDbCorruptionPolicy.ARCHIVE_AND_RESET
        )) {
            MapDbStartupRecovery recovery = recovered.startupRecovery().orElseThrow();
            assertEquals(databasePath.toAbsolutePath().normalize(), recovery.originalPath());
            archivePath = recovery.archivedPath();
            assertTrue(Files.exists(archivePath));
            assertTrue(Files.exists(databasePath));
        }

        assertEquals(List.of(1, 2, 3, 4, 5), toUnsigned(Files.readAllBytes(archivePath)));
        try (MapDbAsyncExecutionEngine reopened = new MapDbAsyncExecutionEngine(databasePath)) {
            assertTrue(reopened.startupRecovery().isEmpty());
        }
    }

    @Test
    void rejectsConcurrentStoreOwnershipWithoutCreatingFallbackStore() throws IOException {
        Path databasePath = tempDir.resolve("locked.db");
        try (MapDbAsyncExecutionEngine owner = new MapDbAsyncExecutionEngine(databasePath)) {
            assertThrows(
                    MapDbStoreLockedException.class,
                    () -> new MapDbAsyncExecutionEngine(databasePath, MapDbCorruptionPolicy.ARCHIVE_AND_RESET)
            );
            assertTrue(corruptArchives().isEmpty());
            try (var paths = Files.list(tempDir)) {
                assertFalse(paths.anyMatch(path -> path.getFileName().toString().contains(".recovered-")));
            }
        }
    }

    @Test
    void detectsChecksumDamageBeforeMapDbAcquiresItsOwnFileLock() throws IOException {
        Path databasePath = tempDir.resolve("checksum.db");
        try (MapDbAsyncExecutionEngine ignored = new MapDbAsyncExecutionEngine(databasePath)) {
        }
        try (RandomAccessFile file = new RandomAccessFile(databasePath.toFile(), "rw")) {
            file.seek(128);
            file.writeInt(file.readInt() ^ 0x01010101);
        }

        assertThrows(MapDbStoreCorruptionException.class, () -> new MapDbAsyncExecutionEngine(databasePath));
        try (MapDbAsyncExecutionEngine recovered = new MapDbAsyncExecutionEngine(
                databasePath,
                MapDbCorruptionPolicy.ARCHIVE_AND_RESET
        )) {
            assertTrue(recovered.startupRecovery().isPresent());
        }
    }

    @Test
    void neverResetsUnsupportedFutureFormat() throws IOException {
        Path databasePath = tempDir.resolve("future.db");
        ByteBuffer bytes = ByteBuffer.allocate(32_864).order(ByteOrder.BIG_ENDIAN);
        bytes.putLong(0, ((long) 74 << 56) | ((long) 3 << 48) | ((long) 1 << 32));
        Files.write(databasePath, bytes.array());

        assertThrows(
                MapDbStoreCorruptionException.class,
                () -> new MapDbAsyncExecutionEngine(databasePath, MapDbCorruptionPolicy.ARCHIVE_AND_RESET)
        );
        assertTrue(Files.exists(databasePath));
        assertTrue(corruptArchives().isEmpty());
    }

    private List<Path> corruptArchives() throws IOException {
        try (var paths = Files.list(tempDir)) {
            return paths.filter(path -> path.getFileName().toString().contains(".corrupt-")).toList();
        }
    }

    private static List<Integer> toUnsigned(byte[] bytes) {
        return java.util.stream.IntStream.range(0, bytes.length)
                .map(index -> Byte.toUnsignedInt(bytes[index]))
                .boxed()
                .toList();
    }

    private static void sleepBeforeDeleteRetry() throws IOException {
        try {
            Thread.sleep(WINDOWS_DELETE_RETRY_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while verifying MapDB artifact cleanup", exception);
        }
    }
}
