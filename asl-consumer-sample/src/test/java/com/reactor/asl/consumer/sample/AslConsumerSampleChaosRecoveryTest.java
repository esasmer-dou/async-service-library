package com.reactor.asl.consumer.sample;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AslConsumerSampleChaosRecoveryTest {
    private static final String CHAOS_SERVICE_ID = "chaos.service";
    private static final String CHAOS_METHOD_ID = "emit(java.lang.String)";

    @TempDir
    Path tempDir;

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
            ResponseEntity<String> response = restTemplate.postForEntity(
                    baseUrl + adminUri("/asl/api/services/{serviceId}/methods/{methodId}/mode", serviceId, methodId),
                    Map.of("executionMode", executionMode),
                    String.class
            );
            assertEquals(HttpStatus.OK, response.getStatusCode());
        }

        void resizeConsumers(String serviceId, String methodId, int consumerThreads) {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    baseUrl + adminUri("/asl/api/services/{serviceId}/methods/{methodId}/consumer-threads", serviceId, methodId),
                    Map.of("consumerThreads", consumerThreads),
                    String.class
            );
            assertEquals(HttpStatus.OK, response.getStatusCode());
        }

        void replay(String serviceId, String methodId, String entryId) {
            ResponseEntity<Void> response = restTemplate.postForEntity(
                    baseUrl + adminUri("/asl/api/services/{serviceId}/methods/{methodId}/buffer/{entryId}/replay", serviceId, methodId, entryId),
                    null,
                    Void.class
            );
            assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        }

        int pendingCount(String serviceId, String methodId) {
            return ((Number) bufferSnapshot(serviceId, methodId).get("pendingCount")).intValue();
        }

        int failedCount(String serviceId, String methodId) {
            return ((Number) bufferSnapshot(serviceId, methodId).get("failedCount")).intValue();
        }

        int inProgressCount(String serviceId, String methodId) {
            return ((Number) bufferSnapshot(serviceId, methodId).get("inProgressCount")).intValue();
        }

        int totalBuffered(String serviceId, String methodId) {
            Map<String, Object> snapshot = bufferSnapshot(serviceId, methodId);
            return ((Number) snapshot.get("pendingCount")).intValue()
                    + ((Number) snapshot.get("failedCount")).intValue()
                    + ((Number) snapshot.get("inProgressCount")).intValue();
        }

        @SuppressWarnings("unchecked")
        String firstEntryId(String serviceId, String methodId) {
            List<Map<String, Object>> entries = (List<Map<String, Object>>) bufferSnapshot(serviceId, methodId).get("entries");
            assertFalse(entries.isEmpty());
            return String.valueOf(entries.getFirst().get("entryId"));
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> bufferSnapshot(String serviceId, String methodId) {
            ResponseEntity<Map> response = restTemplate.exchange(
                    baseUrl + adminUri("/asl/api/services/{serviceId}/methods/{methodId}/buffer", serviceId, methodId),
                    HttpMethod.GET,
                    HttpEntity.EMPTY,
                    Map.class
            );
            assertEquals(HttpStatus.OK, response.getStatusCode());
            return response.getBody();
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

        private static String adminUri(String template, Object... variables) {
            return UriComponentsBuilder.fromPath(template)
                    .buildAndExpand(variables)
                    .encode()
                    .toUriString();
        }
    }

    @FunctionalInterface
    private interface Check {
        boolean matches();
    }
}
