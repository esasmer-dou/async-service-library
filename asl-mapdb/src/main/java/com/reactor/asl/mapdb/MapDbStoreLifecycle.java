package com.reactor.asl.mapdb;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

final class MapDbStoreLifecycle implements AutoCloseable {
    private static final int MAPDB_HEADER_SIZE = 32_864;
    private static final int MAPDB_HEADER_CHECKSUM_OFFSET = 20;
    private static final int MAPDB_HEADER_CHECKSUM_START = 24;
    private static final int MAPDB_HEADER_CHECKSUM_SEED = 1_142_099_053;
    private static final int MAPDB_MAGIC = 74;
    private static final int MAPDB_STORE_DIRECT_FORMAT = 3;

    private final Path databasePath;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final MapDbStartupRecovery startupRecovery;

    private MapDbStoreLifecycle(
            Path databasePath,
            FileChannel lockChannel,
            FileLock lock,
            MapDbStartupRecovery startupRecovery
    ) {
        this.databasePath = databasePath;
        this.lockChannel = lockChannel;
        this.lock = lock;
        this.startupRecovery = startupRecovery;
    }

    static MapDbStoreLifecycle open(Path databasePath, MapDbCorruptionPolicy corruptionPolicy) {
        Path normalizedPath = databasePath.toAbsolutePath().normalize();
        FileChannel lockChannel = null;
        FileLock lock = null;
        try {
            Path parent = normalizedPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Path lockPath = Path.of(normalizedPath + ".asl.lock");
            lockChannel = FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
            );
            try {
                lock = lockChannel.tryLock();
            } catch (OverlappingFileLockException exception) {
                throw new MapDbStoreLockedException(normalizedPath, exception);
            }
            if (lock == null) {
                throw new MapDbStoreLockedException(normalizedPath, null);
            }

            HeaderInspection inspection = inspectHeader(normalizedPath);
            MapDbStartupRecovery recovery = null;
            if (!inspection.valid()) {
                if (inspection.unsupportedFormat() || corruptionPolicy != MapDbCorruptionPolicy.ARCHIVE_AND_RESET) {
                    throw new MapDbStoreCorruptionException(normalizedPath, inspection.detail());
                }
                Path archivePath = archiveStoreSet(normalizedPath);
                recovery = new MapDbStartupRecovery(normalizedPath, archivePath);
            }
            return new MapDbStoreLifecycle(normalizedPath, lockChannel, lock, recovery);
        } catch (RuntimeException | IOException exception) {
            releaseQuietly(lock);
            closeQuietly(lockChannel);
            if (exception instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Failed to prepare MapDB store: " + normalizedPath, exception);
        }
    }

    Path databasePath() {
        return databasePath;
    }

    Optional<MapDbStartupRecovery> startupRecovery() {
        return Optional.ofNullable(startupRecovery);
    }

    @Override
    public void close() {
        IOException failure = null;
        try {
            if (lock.isValid()) {
                lock.release();
            }
        } catch (IOException exception) {
            failure = exception;
        }
        try {
            lockChannel.close();
        } catch (IOException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw new IllegalStateException("Failed to release MapDB ownership lock: " + databasePath, failure);
        }
    }

    private static HeaderInspection inspectHeader(Path databasePath) throws IOException {
        if (!Files.exists(databasePath)) {
            return HeaderInspection.validHeader();
        }
        long size = Files.size(databasePath);
        if (size < MAPDB_HEADER_SIZE) {
            return HeaderInspection.corrupt("store is truncated (" + size + " bytes)");
        }

        ByteBuffer header = ByteBuffer.allocate(MAPDB_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        try (FileChannel channel = FileChannel.open(databasePath, StandardOpenOption.READ)) {
            while (header.hasRemaining()) {
                int read = channel.read(header);
                if (read < 0) {
                    return HeaderInspection.corrupt("store header ended before " + MAPDB_HEADER_SIZE + " bytes");
                }
            }
        }
        header.flip();

        long fileHeader = header.getLong(0);
        int magic = (int) (fileHeader >>> 56);
        if (magic != MAPDB_MAGIC) {
            return HeaderInspection.corrupt("file does not contain a MapDB header");
        }
        int storeFormat = (int) ((fileHeader >>> 48) & 0xffL);
        if (storeFormat != MAPDB_STORE_DIRECT_FORMAT) {
            return HeaderInspection.unsupported("unsupported MapDB store format: " + storeFormat);
        }
        int version = (int) ((fileHeader >>> 32) & 0xffffL);
        if (version != 0) {
            return HeaderInspection.unsupported("store was created by a newer MapDB format version: " + version);
        }

        int expectedChecksum = header.getInt(MAPDB_HEADER_CHECKSUM_OFFSET);
        int actualChecksum = MAPDB_HEADER_CHECKSUM_SEED;
        for (int offset = MAPDB_HEADER_CHECKSUM_START; offset < MAPDB_HEADER_SIZE; offset += Integer.BYTES) {
            actualChecksum += header.getInt(offset);
        }
        if (expectedChecksum != actualChecksum) {
            return HeaderInspection.corrupt(
                    "header checksum mismatch (expected=" + expectedChecksum + ", actual=" + actualChecksum + ")"
            );
        }
        return HeaderInspection.validHeader();
    }

    private static Path archiveStoreSet(Path databasePath) throws IOException {
        Path archivePath = databasePath.resolveSibling(
                databasePath.getFileName() + ".corrupt-" + System.currentTimeMillis()
        );
        List<Move> moves = archiveMoves(databasePath, archivePath);
        List<Move> completed = new ArrayList<>(moves.size());
        try {
            for (Move move : moves) {
                moveAtomicallyIfPossible(move.source(), move.target());
                completed.add(move);
            }
            return archivePath;
        } catch (IOException archiveFailure) {
            IOException rollbackFailure = rollback(completed);
            if (rollbackFailure != null) {
                archiveFailure.addSuppressed(rollbackFailure);
            }
            throw new IOException(
                    "Could not archive the complete damaged MapDB store set at " + databasePath,
                    archiveFailure
            );
        }
    }

    private static List<Move> archiveMoves(Path databasePath, Path archivePath) throws IOException {
        List<Path> sources = new ArrayList<>();
        if (Files.exists(databasePath)) {
            sources.add(databasePath);
        }
        addIfExists(sources, Path.of(databasePath + ".p"));
        addIfExists(sources, Path.of(databasePath + ".t"));

        Path parent = databasePath.getParent();
        if (parent != null && Files.exists(parent)) {
            String walPrefix = databasePath.getFileName() + ".wal";
            try (Stream<Path> paths = Files.list(parent)) {
                paths.filter(path -> path.getFileName().toString().startsWith(walPrefix))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .forEach(sources::add);
            }
        }

        List<Move> moves = new ArrayList<>(sources.size());
        String baseName = databasePath.getFileName().toString();
        String archiveName = archivePath.getFileName().toString();
        for (Path source : sources) {
            String sourceName = source.getFileName().toString();
            String suffix = sourceName.substring(baseName.length());
            Path target = archivePath.resolveSibling(archiveName + suffix);
            if (Files.exists(target)) {
                throw new IOException("Archive target already exists: " + target);
            }
            moves.add(new Move(source, target));
        }
        return moves;
    }

    private static void addIfExists(List<Path> paths, Path candidate) {
        if (Files.exists(candidate)) {
            paths.add(candidate);
        }
    }

    private static void moveAtomicallyIfPossible(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static IOException rollback(List<Move> completed) {
        IOException failure = null;
        for (int index = completed.size() - 1; index >= 0; index--) {
            Move move = completed.get(index);
            try {
                if (Files.exists(move.target())) {
                    moveAtomicallyIfPossible(move.target(), move.source());
                }
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        return failure;
    }

    private static void releaseQuietly(FileLock lock) {
        if (lock == null) {
            return;
        }
        try {
            lock.release();
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }

    private record HeaderInspection(boolean valid, boolean unsupportedFormat, String detail) {
        private static HeaderInspection validHeader() {
            return new HeaderInspection(true, false, "valid");
        }

        private static HeaderInspection corrupt(String detail) {
            return new HeaderInspection(false, false, detail);
        }

        private static HeaderInspection unsupported(String detail) {
            return new HeaderInspection(false, true, detail);
        }
    }

    private record Move(Path source, Path target) {
    }
}
