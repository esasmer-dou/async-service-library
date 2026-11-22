package com.reactor.asl.mapdb;

import java.nio.file.Path;

public record MapDbStartupRecovery(Path originalPath, Path archivedPath) {
}
