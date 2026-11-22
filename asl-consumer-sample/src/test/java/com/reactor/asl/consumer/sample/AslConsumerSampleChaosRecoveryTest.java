package com.reactor.asl.consumer.sample;

import com.reactor.asl.core.ExecutionMode;
import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.MethodBufferSnapshot;
import com.reactor.asl.core.MethodRuntime;
import com.reactor.asl.mapdb.MapDbAsyncExecutionEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AslConsumerSampleChaosRecoveryTest {
    private static final String CHAOS_SERVICE_ID = "chaos.service";
    private static final String CHAOS_METHOD_ID = "emit(java.lang.String)";
    private static final int WINDOWS_DELETE_ATTEMPTS = 40;
    private static final long WINDOWS_DELETE_RETRY_MILLIS = 25L;

    @TempDir
    Path tempDir;

    @AfterEach
    void verifyStoreArtifactsAreDeletableAfterApplicationShutdown() throws IOException {
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
        throw new IOException("MapDB artifacts remained locked after application shutdown: " + tempDir, lastFailure);
    }

    @Test
    void pendingQueueSurvivesRestartAndProcessesAfterRecovery() {
        Path dbPath = tempDir.resolve("chaos-pending-restart.db");
        String payload = "restart-pending";

        try (SampleAppHandle app = SampleAppHandle.start(dbPath)) {
            app.switchMode(CHAOS_SERVICE_ID, CHAOS_METHOD_ID, "ASYNC");
            app.resizeConsumers(CHAOS_SERVICE_ID, CHAOS_METHOD_ID, 0);
            app.emitChaos(payload);
            app.waitFor(() -> app.pendingCount(CHAOS_SERVICE_ID, CHAOS_METHOD_ID) == 1, 5);
        }

        try (SampleAppHandle app = SampleAppHandle.start(dbPath)) {
            app.switchMode(CHAOS_SERVICE_ID, CHAOS_METHOD_ID, "ASYNC");
            app.resizeConsumers(CHAOS_SERVICE_ID, CHAOS_METHOD_ID, 1);
            app.waitFor(() -> app.chaosEvents().contains("CHAOS:" + payload), 8);
            app.waitFor(() -> app.totalBuffered(CHAOS_SERVICE_ID, CHAOS_METHOD_ID) == 0, 8);
        }
    }

    @Test
    void failedQueueSurvivesRestartAndReplayProcessesSuccessfully() {
        Path dbPath = tempDir.resolve("chaos-failed-restart.db");
        String payload = "restart-failed";

        try (SampleAppHandle app = SampleAppHandle.start(dbPath)) {
            app.configureChaosScenario(1, 0L);
            app.switchMode(CHAOS_SERVICE_ID, CHAOS_METHOD_ID, "ASYNC");
            app.resizeConsumers(CHAOS_SERVICE_ID, CHAOS_METHOD_ID, 1);
            app.emitChaos(payload);
            app.waitFor(() -> app.failedCount(CHAOS_SERVICE_ID, CHAOS_METHOD_ID) == 1, 8);
            assertFalse(app.chaosEvents().contains("CHAOS:" + payload));
        }

        try (SampleAppHandle app = SampleAppHandle.start(dbPath)) {
            app.configureChaosScenario(0, 0L);
            app.switchMode(CHAOS_SERVICE_ID, CHAOS_METHOD_ID, "ASYNC");
            String entryId = app.firstEntryId(CHAOS_SERVICE_ID, CHAOS_METHOD_ID);
            app.replay(CHAOS_SERVICE_ID, CHAOS_METHOD_ID, entryId);
            app.resizeConsumers(CHAOS_SERVICE_ID, CHAOS_METHOD_ID, 1);
            app.waitFor(() -> app.chaosEvents().contains("CHAOS:" + payload), 8);
            app.waitFor(() -> app.totalBuffered(CHAOS_SERVICE_ID, CHAOS_METHOD_ID) == 0, 8);
        }
    }

    @Test
    void gracefulShutdownDrainsInFlightAsyncInvocation() {
        Path dbPath = tempDir.resolve("chaos-graceful-drain.db");
        String payload = "shutdown-drain";

        try (SampleAppHandle app = SampleAppHandle.start(dbPath)) {
            app.configureChaosScenario(0, 900L);
            app.switchMode(CHAOS_SERVICE_ID, CHAOS_METHOD_ID, "ASYNC");
            app.resizeConsumers(CHAOS_SERVICE_ID, CHAOS_METHOD_ID, 1);
            app.emitChaos(payload);
            app.waitFor(() -> app.inProgressCount(CHAOS_SERVICE_ID, CHAOS_METHOD_ID) >= 1, 5);
        }

        try (SampleAppHandle app = SampleAppHandle.start(dbPath)) {
            app.waitFor(() -> app.totalBuffered(CHAOS_SERVICE_ID, CHAOS_METHOD_ID) == 0, 5);
            assertTrue(app.chaosEvents().isEmpty());
        }
    }

    private static final class SampleAppHandle implements AutoCloseable {
        private final ConfigurableApplicationContext context;
        private final RestTemplate restTemplate;
        private final String baseUrl;

        private SampleAppHandle(ConfigurableApplicationContext context, int port) {
            this.context = context;
            this.restTemplate = new RestTemplate();
            this.baseUrl = "http://localhost:" + port;
        }

        static SampleAppHandle start(Path dbPath) {
            ConfigurableApplicationContext context = new SpringApplicationBuilder(AslConsumerSampleApplication.class)
                    .run(
                            "--server.port=0",
                            "--asl.async.mapdb.enabled=true",
                            "--asl.async.mapdb.path=" + dbPath.toAbsolutePath().toString().replace("\\", "/")
                    );
            int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
            return new SampleAppHandle(context, port);
        }

        void configureChaosScenario(int failuresRemaining, long processingDelayMillis) {
            ResponseEntity<AsyncScenarioSnapshot> response = restTemplate.postForEntity(
                    baseUrl + "/api/test/scenarios/chaos/emit",
                    new AsyncScenarioRequest(failuresRemaining, processingDelayMillis),
                    AsyncScenarioSnapshot.class
            );
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(failuresRemaining, response.getBody().failuresRemaining());
        }

        void emitChaos(String payload) {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    baseUrl + "/api/chaos/emit/" + payload,
                    null,
                    Map.class
            );
            assertEquals(HttpStatus.OK, response.getStatusCode());
        }

        List<String> chaosEvents() {
            ResponseEntity<List<String>> response = restTemplate.exchange(
                    baseUrl + "/api/chaos/events",
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<>() {
                    }
            );
            assertEquals(HttpStatus.OK, response.getStatusCode());
            return response.getBody();
        }

        void switchMode(String serviceId, String methodId, String executionMode) {
            MethodRuntime runtime = registry().service(serviceId).methodById(methodId);
            runtime.switchMode(ExecutionMode.valueOf(executionMode));
            engine().applyRuntime(runtime);
        }

        void resizeConsumers(String serviceId, String methodId, int consumerThreads) {
            MethodRuntime runtime = registry().service(serviceId).methodById(methodId);
            runtime.setConsumerThreads(consumerThreads);
            engine().applyRuntime(runtime);
        }

        void replay(String serviceId, String methodId, String entryId) {
            assertTrue(engine().replay(serviceId, methodId, entryId));
        }

        long pendingCount(String serviceId, String methodId) {
            return bufferSnapshot(serviceId, methodId).pendingCount();
        }

        long failedCount(String serviceId, String methodId) {
            return bufferSnapshot(serviceId, methodId).failedCount();
        }

        long inProgressCount(String serviceId, String methodId) {
            return bufferSnapshot(serviceId, methodId).inProgressCount();
        }

        long totalBuffered(String serviceId, String methodId) {
            MethodBufferSnapshot snapshot = bufferSnapshot(serviceId, methodId);
            return snapshot.pendingCount() + snapshot.failedCount() + snapshot.inProgressCount();
        }

        String firstEntryId(String serviceId, String methodId) {
            MethodBufferSnapshot snapshot = bufferSnapshot(serviceId, methodId);
            assertFalse(snapshot.entries().isEmpty());
            return snapshot.entries().getFirst().entryId();
        }

        private MethodBufferSnapshot bufferSnapshot(String serviceId, String methodId) {
            return engine().snapshot(serviceId, methodId, 50);
        }

        private GovernanceRegistry registry() {
            return context.getBean(GovernanceRegistry.class);
        }

        private MapDbAsyncExecutionEngine engine() {
            return context.getBean(MapDbAsyncExecutionEngine.class);
        }

        void waitFor(Check check, int timeoutSeconds) {
            Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
            while (Instant.now().isBefore(deadline)) {
                if (check.matches()) {
                    return;
                }
                try {
                    Thread.sleep(Duration.ofMillis(25));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
            throw new AssertionError("Condition was not met within timeout");
        }

        @Override
        public void close() {
            context.close();
        }
    }

    @FunctionalInterface
    private interface Check {
        boolean matches();
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
