package com.reactor.asl.mapdb;

import java.nio.file.Path;

public final class MapDbStoreLockedException extends IllegalStateException {
    private final Path databasePath;

    MapDbStoreLockedException(Path databasePath, Throwable cause) {
        super("MapDB store is already owned by another process: " + databasePath, cause);
        this.databasePath = databasePath;
    }

    public Path databasePath() {
        return databasePath;
    }
}
