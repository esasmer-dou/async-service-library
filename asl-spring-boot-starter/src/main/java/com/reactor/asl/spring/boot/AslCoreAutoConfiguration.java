package com.reactor.asl.spring.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactor.asl.core.AsyncPayloadCodec;
import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.JavaObjectStreamAsyncPayloadCodec;
import com.reactor.asl.mapdb.MapDbAsyncExecutionEngine;
import org.mapdb.DBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

@AutoConfiguration
@ConditionalOnClass(GovernanceRegistry.class)
@EnableConfigurationProperties({AslAsyncMapDbProperties.class, AslRuntimeProperties.class})
public class AslCoreAutoConfiguration {
    private static final Logger log = LoggerFactory.getLogger(AslCoreAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    GovernanceRegistry governanceRegistry() {
        return new GovernanceRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    AslCoreRuntimeDefaultsApplier aslCoreRuntimeDefaultsApplier(
            GovernanceRegistry governanceRegistry,
            AslRuntimeProperties properties
    ) {
        return new AslCoreRuntimeDefaultsApplier(governanceRegistry, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    JacksonAsyncPayloadSchemaRegistry jacksonAsyncPayloadSchemaRegistry() {
        return JacksonAsyncPayloadSchemaRegistry.empty();
    }

    @Bean
    @ConditionalOnClass(ObjectMapper.class)
    @ConditionalOnBean(ObjectMapper.class)
    @ConditionalOnMissingBean(AsyncPayloadCodec.class)
    @ConditionalOnProperty(prefix = "asl.async.mapdb", name = "codec", havingValue = "jackson-json")
    AsyncPayloadCodec jacksonJsonAsyncPayloadCodec(
            ObjectMapper objectMapper,
            JacksonAsyncPayloadSchemaRegistry schemaRegistry
    ) {
        return new JacksonJsonAsyncPayloadCodec(
                objectMapper,
                Thread.currentThread().getContextClassLoader(),
                schemaRegistry
        );
    }

    @Bean
    @ConditionalOnMissingBean
    AsyncPayloadCodec asyncPayloadCodec() {
        return new JavaObjectStreamAsyncPayloadCodec();
    }

    @Bean
    @ConditionalOnMissingBean
    AslStartupRecoveryState aslStartupRecoveryState() {
        return new AslStartupRecoveryState();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "asl.async.mapdb", name = "enabled", havingValue = "true")
    MapDbAsyncExecutionEngine mapDbAsyncExecutionEngine(
            GovernanceRegistry governanceRegistry,
            AslAsyncMapDbProperties properties,
            AsyncPayloadCodec asyncPayloadCodec,
            AslStartupRecoveryState startupRecoveryState
    ) {
        Path databasePath = Path.of(properties.getPath());
        MapDbAsyncExecutionEngine engine = createMapDbAsyncExecutionEngine(databasePath, properties, asyncPayloadCodec, startupRecoveryState);
        governanceRegistry.attachAsyncExecutionEngine(engine);
        return engine;
    }

    private MapDbAsyncExecutionEngine createMapDbAsyncExecutionEngine(
            Path databasePath,
            AslAsyncMapDbProperties properties,
            AsyncPayloadCodec asyncPayloadCodec,
            AslStartupRecoveryState startupRecoveryState
    ) {
        try {
            return newEngine(databasePath, properties, asyncPayloadCodec, false);
        } catch (DBException corruption) {
            if (!properties.isResetIfCorrupt() || !isRecoverableMapDbFailure(corruption)) {
                throw corruption;
            }
            if (isHeaderCorruption(corruption)) {
                Path archivedPath = archiveCorruptStore(databasePath);
                if (isRecoveryPath(archivedPath)) {
                    startupRecoveryState.record(
                            AslStartupRecoveryState.StorageRecoveryNotice.recoveryPathFallback(databasePath, archivedPath)
                    );
                    log.warn(
                        "Detected MapDB header corruption at {} but could not archive the damaged store cleanly. Starting with fresh recovery path {} instead.",
                        databasePath,
                        archivedPath,
                        corruption
                    );
                    return newEngine(archivedPath, properties, asyncPayloadCodec, false);
                }
                startupRecoveryState.record(
                        AslStartupRecoveryState.StorageRecoveryNotice.archivedCorruptStore(databasePath, archivedPath, databasePath)
                );
                log.warn(
                        "Detected MapDB header corruption at {}. Archived damaged store to {} and starting with a fresh queue store.",
                        databasePath,
                        archivedPath,
                        corruption
                );
                return newEngine(databasePath, properties, asyncPayloadCodec, false);
            }
            Path recoveryPath = resetCorruptStore(databasePath);
            if (!recoveryPath.equals(databasePath)) {
                startupRecoveryState.record(
                        AslStartupRecoveryState.StorageRecoveryNotice.recoveryPathFallback(databasePath, recoveryPath)
                );
                log.warn(
                        "MapDB store at {} could not be deleted cleanly. Starting with recovery path {} instead.",
                        databasePath,
                        recoveryPath
                );
            } else {
                startupRecoveryState.record(
                        AslStartupRecoveryState.StorageRecoveryNotice.resetOriginalStore(databasePath)
                );
                log.warn("MapDB store at {} was reset after corruption and will start clean.", databasePath);
            }
            return newEngine(recoveryPath, properties, asyncPayloadCodec, false);
        }
    }

    private MapDbAsyncExecutionEngine newEngine(
            Path databasePath,
            AslAsyncMapDbProperties properties,
            AsyncPayloadCodec asyncPayloadCodec,
            boolean checksumHeaderBypass
    ) {
        return new MapDbAsyncExecutionEngine(
                databasePath,
                asyncPayloadCodec,
                properties.getWorkerShutdownAwaitMillis(),
                properties.getRegistrationIdleSleepMillis(),
                properties.getEmptyQueueSleepMillis(),
                properties.getRequeueDelayMillis(),
                properties.getRecoveredInProgressMessage(),
                properties.isTransactionsEnabled(),
                properties.isMemoryMappedEnabled(),
                checksumHeaderBypass
        );
    }

    private Path resetCorruptStore(Path databasePath) {
        boolean baseDeleted = deleteIfExists(databasePath);
        deleteKnownSidecars(databasePath);
        if (baseDeleted || !Files.exists(databasePath)) {
            return databasePath;
        }
        return fallbackRecoveryPath(databasePath);
    }

    private Path archiveCorruptStore(Path databasePath) {
        Path archivePath = corruptArchivePath(databasePath);
        try {
            Files.createDirectories(databasePath.toAbsolutePath().getParent());
            if (Files.exists(databasePath)) {
                Files.move(databasePath, archivePath, StandardCopyOption.REPLACE_EXISTING);
            }
            archiveKnownSidecars(databasePath, archivePath);
            return archivePath;
        } catch (IOException moveFailure) {
            deleteKnownSidecars(databasePath);
            return fallbackRecoveryPath(databasePath);
        }
    }

    private boolean deleteIfExists(Path path) {
        try {
            return Files.deleteIfExists(path);
        } catch (IOException exception) {
            return false;
        }
    }

    private void deleteKnownSidecars(Path databasePath) {
        deleteIfExists(Path.of(databasePath + ".p"));
        deleteIfExists(Path.of(databasePath + ".t"));
        moveOrDeleteWalSidecars(databasePath, null);
    }

    private void archiveKnownSidecars(Path databasePath, Path archiveBasePath) {
        moveIfExists(Path.of(databasePath + ".p"), Path.of(archiveBasePath + ".p"));
        moveIfExists(Path.of(databasePath + ".t"), Path.of(archiveBasePath + ".t"));
        moveOrDeleteWalSidecars(databasePath, archiveBasePath);
    }

    private void moveOrDeleteWalSidecars(Path databasePath, Path archiveBasePath) {
        Path parent = databasePath.toAbsolutePath().getParent();
        if (parent == null || !Files.exists(parent)) {
            return;
        }
        String prefix = databasePath.getFileName().toString() + ".wal";
        try (Stream<Path> files = Files.list(parent)) {
            files
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .forEach(path -> {
                        if (archiveBasePath == null) {
                            deleteIfExists(path);
                        } else {
                            String suffix = path.getFileName().toString().substring(databasePath.getFileName().toString().length());
                            moveIfExists(path, archiveBasePath.resolveSibling(archiveBasePath.getFileName().toString() + suffix));
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    private void moveIfExists(Path source, Path target) {
        try {
            if (Files.exists(source)) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
        }
    }

    private boolean isRecoverableMapDbFailure(DBException exception) {
        Throwable cause = exception.getCause();
        return exception instanceof DBException.DataCorruption
                || exception instanceof DBException.VolumeIOError
                || cause instanceof IOException
                || (exception.getMessage() != null && !exception.getMessage().isBlank());
    }

    private boolean isHeaderCorruption(DBException exception) {
        return exception instanceof DBException.DataCorruption;
    }

    private Path fallbackRecoveryPath(Path databasePath) {
        String fileName = databasePath.getFileName().toString();
        String recoveredName = fileName + ".recovered-" + System.currentTimeMillis();
        return databasePath.resolveSibling(recoveredName);
    }

    private Path corruptArchivePath(Path databasePath) {
        String fileName = databasePath.getFileName().toString();
        String archivedName = fileName + ".corrupt-" + System.currentTimeMillis();
        return databasePath.resolveSibling(archivedName);
    }

    private boolean isRecoveryPath(Path path) {
        return path.getFileName().toString().contains(".recovered-");
    }
}
