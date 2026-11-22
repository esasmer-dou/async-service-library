package com.reactor.asl.mapdb;

import java.nio.file.Path;

public final class MapDbStoreCorruptionException extends IllegalStateException {
    private final Path databasePath;

    MapDbStoreCorruptionException(Path databasePath, String detail) {
        super("MapDB store validation failed at " + databasePath + ": " + detail);
        this.databasePath = databasePath;
    }

    public Path databasePath() {
        return databasePath;
    }
}
