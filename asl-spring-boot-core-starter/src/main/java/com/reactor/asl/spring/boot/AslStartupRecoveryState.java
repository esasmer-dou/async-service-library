package com.reactor.asl.spring.boot;

import java.nio.file.Path;
import java.util.Objects;

public final class AslStartupRecoveryState {
    private volatile StorageRecoveryNotice currentNotice = StorageRecoveryNotice.none();

    public StorageRecoveryNotice currentNotice() {
        return currentNotice;
    }

    public void clear() {
        currentNotice = StorageRecoveryNotice.none();
    }

    public void record(StorageRecoveryNotice notice) {
        currentNotice = Objects.requireNonNull(notice, "notice");
    }

    public record StorageRecoveryNotice(
            String severity,
            String headline,
            String statusLabel,
            String detail,
            String originalPath,
            String movedToPath,
            String activeStorePath,
            boolean corruptFileArchived
    ) {
        public static StorageRecoveryNotice none() {
            return new StorageRecoveryNotice("", "", "", "", "", "", "", false);
        }

        public static StorageRecoveryNotice archivedCorruptStore(
                Path originalPath,
                Path archivedPath,
                Path activeStorePath
        ) {
            return new StorageRecoveryNotice(
                    "WARN",
                    "Recovered queue store on startup",
                    "Corrupt file archived",
                    "A corrupted queue store was detected during startup. The damaged file was moved aside and the primary queue store was recreated before boot completed.",
                    absolute(originalPath),
                    absolute(archivedPath),
                    absolute(activeStorePath),
                    true
            );
        }

        public static StorageRecoveryNotice recoveryPathFallback(Path originalPath, Path recoveryPath) {
            return new StorageRecoveryNotice(
                    "WARN",
                    "Recovered queue store on startup",
                    "Fresh recovery store created",
                    "A corrupted queue store was detected during startup. The original path could not be reused safely, so ASL continued with a fresh recovery queue file.",
                    absolute(originalPath),
                    absolute(recoveryPath),
                    absolute(recoveryPath),
                    false
            );
        }

        public static StorageRecoveryNotice resetOriginalStore(Path originalPath) {
            String resolved = absolute(originalPath);
            return new StorageRecoveryNotice(
                    "WARN",
                    "Recovered queue store on startup",
                    "Queue store reset",
                    "A recoverable queue store problem was detected during startup. ASL reset the store and continued booting with a clean file at the original location.",
                    resolved,
                    "",
                    resolved,
                    false
            );
        }

        public boolean visible() {
            return !headline.isBlank();
        }

        public boolean hasMovedPath() {
            return !movedToPath.isBlank();
        }

        public boolean hasActiveStorePath() {
            return !activeStorePath.isBlank();
        }

        private static String absolute(Path path) {
            return path.toAbsolutePath().normalize().toString();
        }
    }
}
