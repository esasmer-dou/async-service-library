package com.reactor.asl.spring.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.reactor.asl.core.AsyncPayloadCodec;
import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.JavaObjectStreamAsyncPayloadCodec;
import com.reactor.asl.mapdb.AsyncQueueLimits;
import com.reactor.asl.mapdb.MapDbAsyncExecutionEngine;
import com.reactor.asl.mapdb.MapDbAsyncExecutionOptions;
import com.reactor.asl.mapdb.MapDbStartupRecovery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@AutoConfiguration(after = {AslCoreAutoConfiguration.class, JacksonAutoConfiguration.class})
@ConditionalOnClass(MapDbAsyncExecutionEngine.class)
@ConditionalOnBean(GovernanceRegistry.class)
@EnableConfigurationProperties(AslAsyncMapDbProperties.class)
public class AslMapDbAutoConfiguration {
    private static final Logger log = LoggerFactory.getLogger(AslMapDbAutoConfiguration.class);
    static final String LANE_PREFIX = "asl.async.mapdb.services";

    @Bean
    @ConditionalOnMissingBean
    JacksonAsyncPayloadSchemaRegistry jacksonAsyncPayloadSchemaRegistry() {
        return JacksonAsyncPayloadSchemaRegistry.empty();
    }

    @Bean
    @ConditionalOnMissingBean
    JacksonAsyncPayloadTypePolicy jacksonAsyncPayloadTypePolicy(AslAsyncMapDbProperties properties) {
        return new AllowlistedJacksonAsyncPayloadTypePolicy(
                properties.getPayload().getAllowedTypeNames(),
                properties.getPayload().getAllowedTypePrefixes()
        );
    }

    @Bean
    @ConditionalOnClass(ObjectMapper.class)
    @ConditionalOnMissingBean(AsyncPayloadCodec.class)
    @ConditionalOnProperty(
            prefix = "asl.async.mapdb",
            name = "codec",
            havingValue = "jackson-json",
            matchIfMissing = true
    )
    AsyncPayloadCodec jacksonJsonAsyncPayloadCodec(
            ObjectProvider<ObjectMapper> objectMapperProvider,
            JacksonAsyncPayloadSchemaRegistry schemaRegistry,
            JacksonAsyncPayloadTypePolicy typePolicy
    ) {
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(
                () -> JsonMapper.builder().findAndAddModules().build()
        );
        return new JacksonJsonAsyncPayloadCodec(
                objectMapper,
                Thread.currentThread().getContextClassLoader(),
                schemaRegistry,
                typePolicy
        );
    }

    @Bean
    @ConditionalOnMissingBean(AsyncPayloadCodec.class)
    @ConditionalOnProperty(
            prefix = "asl.async.mapdb",
            name = "codec",
            havingValue = "java-object-stream"
    )
    AsyncPayloadCodec asyncPayloadCodec(AslAsyncMapDbProperties properties) {
        AslAsyncMapDbProperties.Payload payload = properties.getPayload();
        return new JavaObjectStreamAsyncPayloadCodec(
                payload.getAllowedTypeNames(),
                payload.getAllowedTypePrefixes(),
                payload.getMaxDeserializationDepth(),
                payload.getMaxDeserializationReferences(),
                payload.getMaxDeserializationArrayLength(),
                properties.getAdmission().getMaxSinglePayloadBytes()
        );
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
            AslStartupRecoveryState startupRecoveryState,
            Environment environment,
            ObjectProvider<com.reactor.asl.core.AsyncResultHandler> resultHandler
    ) {
        if (!properties.isTransactionsEnabled()) {
            log.warn(
                    "ASL MapDB transactions are disabled. Group commit, rollback, and crash durability guarantees are reduced; use this mode only for explicitly disposable workloads."
            );
        }
        Path databasePath = Path.of(properties.getPath());
        MapDbAsyncExecutionEngine engine = new MapDbAsyncExecutionEngine(
                databasePath,
                asyncPayloadCodec,
                executionOptions(properties, environment)
        );
        engine.startupRecovery().ifPresent(recovery -> recordStartupRecovery(startupRecoveryState, recovery));
        // Before the engine is attached (and starts consuming): outcomes of invocations left from the last run
        // must find the handler.
        resultHandler.ifAvailable(engine::resultHandler);
        governanceRegistry.attachAsyncExecutionEngine(engine);
        return engine;
    }

    private MapDbAsyncExecutionOptions executionOptions(AslAsyncMapDbProperties properties, Environment environment) {
        AslAsyncMapDbProperties.Admission admission = properties.getAdmission();
        AslAsyncMapDbProperties.Writer writer = properties.getWriter();
        AslAsyncMapDbProperties.Workers workers = properties.getWorkers();
        AslAsyncMapDbProperties.Retry retry = properties.getRetry();
        AslAsyncMapDbProperties.Idempotency idempotency = properties.getIdempotency();
        AslAsyncMapDbProperties.Payload payload = properties.getPayload();
        MapDbAsyncExecutionOptions.Builder builder = MapDbAsyncExecutionOptions.builder()
                .workerShutdownAwaitMillis(properties.getWorkerShutdownAwaitMillis())
                .registrationIdleSleepMillis(properties.getRegistrationIdleSleepMillis())
                .emptyQueueSleepMillis(properties.getEmptyQueueSleepMillis())
                .requeueDelayMillis(properties.getRequeueDelayMillis())
                .recoveredInProgressMessage(properties.getRecoveredInProgressMessage())
                .transactionsEnabled(properties.isTransactionsEnabled())
                .memoryMappedEnabled(properties.isMemoryMappedEnabled())
                .corruptionPolicy(properties.getCorruptionPolicy())
                .globalLimits(new AsyncQueueLimits(
                        admission.getMaxTotalEntries(),
                        admission.getMaxTotalPayloadBytes()
                ))
                .defaultLaneLimits(new AsyncQueueLimits(
                        admission.getDefaultLaneMaxEntries(),
                        admission.getDefaultLaneMaxPayloadBytes()
                ))
                .maxPayloadBytes(admission.getMaxSinglePayloadBytes())
                .maxConcurrentEnqueues(admission.getMaxConcurrentEnqueues())
                .overflowPolicy(admission.getOverflowPolicy())
                .enqueueTimeoutMillis(admission.getEnqueueTimeoutMillis())
                .writerQueueCapacity(writer.getQueueCapacity())
                .commitBatchSize(writer.getCommitBatchSize())
                .commitBatchWindowMillis(writer.getCommitBatchWindowMillis())
                .writerOperationTimeoutMillis(writer.getOperationTimeoutMillis())
                .maxWorkerThreads(workers.getMaxThreads())
                .workerQueueCapacity(workers.getTaskQueueCapacity())
                .leaseDurationMillis(workers.getLeaseDurationMillis())
                .leaseRenewalIntervalMillis(workers.getLeaseRenewalIntervalMillis())
                .executionTimeoutMillis(workers.getExecutionTimeoutMillis())
                .drainBacklogInSyncMode(workers.isDrainBacklogInSyncMode())
                .completionTimeoutMillis(workers.getCompletionTimeoutMillis())
                .resultWaitTimeoutMillis(workers.getResultWaitTimeoutMillis())
                .maxPendingResults(workers.getMaxPendingResults())
                .maxInterruptions(retry.getMaxInterruptions())
                .maxAttempts(retry.getMaxAttempts())
                .retryInitialBackoffMillis(retry.getInitialBackoffMillis())
                .retryMaxBackoffMillis(retry.getMaxBackoffMillis())
                .retryJitterRatio(retry.getJitterRatio())
                .idempotencyRetentionMillis(idempotency.getRetentionMillis())
                .maxIdempotencyEntries(idempotency.getMaxEntries())
                .maxIdempotencyKeyCharacters(idempotency.getMaxKeyCharacters())
                .maxSummaryCharacters(payload.getMaxSummaryCharacters())
                .maxErrorCharacters(payload.getMaxErrorCharacters())
                .payloadSummaryPolicy(payload.getSummaryPolicy())
                .errorDetailPolicy(payload.getErrorDetailPolicy())
                .legacyJavaMigrationEnabled(payload.isLegacyJavaMigrationEnabled());

        // Without brackets Spring Boot splits "mail.service" at the dot or drops "send(java.lang.String)"
        // while binding; the lane limit would then silently never apply.
        Map<String, Set<String>> bound = new LinkedHashMap<>();
        properties.getServices().forEach((service, configuration) -> bound.put(service, configuration.getMethods().keySet()));
        AslServiceKeys.requireBound(environment, LANE_PREFIX, bound, "max-entries");
        for (var serviceEntry : properties.getServices().entrySet()) {
            for (String methodId : serviceEntry.getValue().getMethods().keySet()) {
                if (methodId.indexOf('(') < 0 || !methodId.endsWith(")")) {
                    throw new IllegalStateException(LANE_PREFIX + "['" + serviceEntry.getKey() + "'] method key '"
                            + methodId + "' is not a method signature." + AslServiceKeys.hint(LANE_PREFIX, "max-entries"));
                }
            }
            for (var methodEntry : serviceEntry.getValue().getMethods().entrySet()) {
                AslAsyncMapDbProperties.LaneConfiguration lane = methodEntry.getValue();
                long maxEntries = lane.getMaxEntries() == null
                        ? admission.getDefaultLaneMaxEntries()
                        : lane.getMaxEntries();
                long maxPayloadBytes = lane.getMaxPayloadBytes() == null
                        ? admission.getDefaultLaneMaxPayloadBytes()
                        : lane.getMaxPayloadBytes();
                builder.laneLimits(
                        serviceEntry.getKey(),
                        methodEntry.getKey(),
                        new AsyncQueueLimits(maxEntries, maxPayloadBytes)
                );
            }
        }
        return builder.build();
    }

    private void recordStartupRecovery(
            AslStartupRecoveryState startupRecoveryState,
            MapDbStartupRecovery recovery
    ) {
        startupRecoveryState.record(
                AslStartupRecoveryState.StorageRecoveryNotice.archivedCorruptStore(
                        recovery.originalPath(),
                        recovery.archivedPath(),
                        recovery.originalPath()
                )
        );
        log.warn(
                "Validated MapDB corruption at {}. Archived the complete damaged store set to {} and opened a new empty store because corruption-policy=ARCHIVE_AND_RESET.",
                recovery.originalPath(),
                recovery.archivedPath()
        );
    }
}
