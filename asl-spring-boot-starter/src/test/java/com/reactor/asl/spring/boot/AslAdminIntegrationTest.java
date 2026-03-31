package com.reactor.asl.spring.boot;

import com.reactor.asl.core.AsyncExecutionEngine;
import com.reactor.asl.core.AsyncMethodBinding;
import com.reactor.asl.core.ExecutionMode;
import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.MethodBufferEntryView;
import com.reactor.asl.core.MethodBufferSnapshot;
import com.reactor.asl.core.MethodDescriptor;
import com.reactor.asl.core.MethodRuntime;
import com.reactor.asl.core.ServiceDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = AslAdminIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class AslAdminIntegrationTest {
    private static final String SERVICE_ID = "demo.service";
    private static final String METHOD_ID = "process(java.lang.String)";
    private static final String ASYNC_METHOD_ID = "publish(java.lang.String)";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private GovernanceRegistry registry;

    @Autowired
    private InMemoryAsyncExecutionEngine bufferAdminProvider;

    @Autowired
    private AslAdminRuntimeConfiguration runtimeConfiguration;

    @Autowired
    private AslStartupRecoveryState startupRecoveryState;

    @BeforeEach
    void resetState() {
        registry.service(SERVICE_ID).methodById(METHOD_ID).enable();
        registry.service(SERVICE_ID).methodById(METHOD_ID).setMaxConcurrency(3);
        registry.service(SERVICE_ID).methodById(ASYNC_METHOD_ID).enable();
        registry.service(SERVICE_ID).methodById(ASYNC_METHOD_ID).setMaxConcurrency(2);
        registry.service(SERVICE_ID).methodById(ASYNC_METHOD_ID).setConsumerThreads(1);
        registry.service(SERVICE_ID).methodById(ASYNC_METHOD_ID).switchMode(ExecutionMode.SYNC);
        bufferAdminProvider.reset();
        runtimeConfiguration.setBufferPreviewLimit(50);
        startupRecoveryState.clear();
        AslAdminRuntimeConfiguration.UiPatch patch = new AslAdminRuntimeConfiguration.UiPatch();
        patch.setHeroTitle("ASL Control Plane");
        patch.setQueueBufferTitle("Queue Buffer");
        runtimeConfiguration.updateUi(patch);
    }

    @Test
    void exposesRegisteredServicesOverRest() {
        ResponseEntity<String> response = restTemplate.getForEntity("/asl/api/services", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains(SERVICE_ID));
        assertTrue(response.getBody().contains(METHOD_ID));

        ResponseEntity<Map> summaryResponse = restTemplate.getForEntity("/asl/api/summary", Map.class);
        assertEquals(HttpStatus.OK, summaryResponse.getStatusCode());
        assertTrue(((Number) summaryResponse.getBody().get("serviceCount")).intValue() >= 1);
        assertTrue(((Number) summaryResponse.getBody().get("methodCount")).intValue() >= 2);
        assertTrue(summaryResponse.getBody().containsKey("attentionItems"));
        assertTrue(summaryResponse.getBody().containsKey("storageRecovery"));
    }

    @Test
    void updatesMethodStateAndConcurrencyOverRest() {
        String disableUri = UriComponentsBuilder.fromUriString(uri("/asl/api/services/{serviceId}/methods/{methodId}/disable"))
                .queryParam("message", "manual-stop")
                .toUriString();

        ResponseEntity<String> disableResponse = restTemplate.postForEntity(disableUri, null, String.class);
        assertEquals(HttpStatus.OK, disableResponse.getStatusCode());
        assertTrue(disableResponse.getBody().contains("manual-stop"));
        assertFalse(registry.service(SERVICE_ID).methodById(METHOD_ID).policy().enabled());

        ResponseEntity<String> enableResponse = restTemplate.postForEntity(
                uri("/asl/api/services/{serviceId}/methods/{methodId}/enable"),
                null,
                String.class
        );

        assertEquals(HttpStatus.OK, enableResponse.getStatusCode());
        assertTrue(registry.service(SERVICE_ID).methodById(METHOD_ID).policy().enabled());

        String concurrencyUri = uri("/asl/api/services/{serviceId}/methods/{methodId}/concurrency");

        ResponseEntity<String> concurrencyResponse = restTemplate.postForEntity(
                concurrencyUri,
                Map.of("maxConcurrency", 7),
                String.class
        );

        assertEquals(HttpStatus.OK, concurrencyResponse.getStatusCode());
        assertEquals(7, registry.service(SERVICE_ID).methodById(METHOD_ID).policy().maxConcurrency());
        assertTrue(concurrencyResponse.getBody().contains("\"maxConcurrency\":7"));
    }

    @Test
    void exposesServiceDetailAndBufferOperationsOverRest() {
        ResponseEntity<String> serviceResponse = restTemplate.getForEntity(
                uri("/asl/api/services/{serviceId}", SERVICE_ID),
                String.class
        );

        assertEquals(HttpStatus.OK, serviceResponse.getStatusCode());
        assertTrue(serviceResponse.getBody().contains(METHOD_ID));

        String bufferUri = uri("/asl/api/services/{serviceId}/methods/{methodId}/buffer");
        ResponseEntity<String> bufferResponse = restTemplate.getForEntity(bufferUri, String.class);

        assertEquals(HttpStatus.OK, bufferResponse.getStatusCode());
        assertTrue(bufferResponse.getBody().contains("\"available\":true"));
        assertTrue(bufferResponse.getBody().contains("buffer-1"));

        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                uri("/asl/api/services/{serviceId}/methods/{methodId}/buffer/{entryId}", SERVICE_ID, METHOD_ID, "buffer-1"),
                org.springframework.http.HttpMethod.DELETE,
                null,
                Void.class
        );

        assertEquals(HttpStatus.NO_CONTENT, deleteResponse.getStatusCode());
        assertTrue(bufferAdminProvider.snapshot(SERVICE_ID, METHOD_ID, 10).entries().isEmpty());

        bufferAdminProvider.reset();
        ResponseEntity<String> clearResponse = restTemplate.exchange(
                uri("/asl/api/services/{serviceId}/methods/{methodId}/buffer"),
                org.springframework.http.HttpMethod.DELETE,
                null,
                String.class
        );

        assertEquals(HttpStatus.OK, clearResponse.getStatusCode());
        assertTrue(clearResponse.getBody().contains("\"cleared\":1"));
    }

    @Test
    void rendersAdminPageAndProcessesUiActions() {
        ResponseEntity<String> response = restTemplate.getForEntity("/asl", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("ASL Control Plane"));
        assertTrue(response.getBody().contains("Queue Buffer"));
        assertTrue(response.getBody().contains("buffer-1"));
        assertTrue(response.getBody().contains("Payload Type"));
        assertTrue(response.getBody().contains("jackson-json-v1"));
        assertTrue(response.getBody().contains("Load Overview"));
        assertTrue(response.getBody().contains("Active Work"));
        assertTrue(response.getBody().contains("Live Refresh"));
        assertTrue(response.getBody().contains("Refresh Interval"));
        assertTrue(response.getBody().contains("Refresh Buffer"));
        assertTrue(response.getBody().contains("Live Buffer"));
        assertTrue(response.getBody().contains("data-live-refresh"));
        assertTrue(response.getBody().contains("data-live-buffer"));
        assertTrue(response.getBody().contains("data-default-live-refresh-enabled"));
        assertTrue(response.getBody().contains("data-default-live-buffer-enabled"));
        assertTrue(response.getBody().contains("data-default-refresh-interval-ms"));
        assertTrue(response.getBody().contains("data-change-flash-ms"));
        assertTrue(response.getBody().contains("data-success-message-auto-hide-ms"));
        assertTrue(response.getBody().contains("data-error-message-auto-hide-ms"));
        assertTrue(response.getBody().contains("detailScrollTop"));
        assertTrue(response.getBody().contains("sidebarScrollTop"));
        assertTrue(response.getBody().contains("Search services"));
        assertTrue(response.getBody().contains("Operations Digest"));
        assertTrue(response.getBody().contains("Attention Queue"));
        assertTrue(response.getBody().contains("Open Highest-Priority Lane"));
        assertTrue(response.getBody().contains("/asl/api/summary"));

        ResponseEntity<String> disablePageResponse = restTemplate.postForEntity(
                UriComponentsBuilder.fromUriString(uri("/asl/services/{serviceId}/methods/{methodId}/disable"))
                        .queryParam("message", "ui-stop")
                        .toUriString(),
                null,
                String.class
        );

        assertTrue(disablePageResponse.getStatusCode().is2xxSuccessful() || disablePageResponse.getStatusCode().is3xxRedirection());
        assertFalse(registry.service(SERVICE_ID).methodById(METHOD_ID).policy().enabled());

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("maxConcurrency", "9");
        ResponseEntity<String> concurrencyPageResponse = restTemplate.postForEntity(
                uri("/asl/services/{serviceId}/methods/{methodId}/concurrency"),
                form,
                String.class
        );

        assertTrue(concurrencyPageResponse.getStatusCode().is2xxSuccessful() || concurrencyPageResponse.getStatusCode().is3xxRedirection());
        assertEquals(9, registry.service(SERVICE_ID).methodById(METHOD_ID).policy().maxConcurrency());

        ResponseEntity<String> clearPageResponse = restTemplate.postForEntity(
                uri("/asl/services/{serviceId}/methods/{methodId}/buffer/clear"),
                null,
                String.class
        );

        assertTrue(clearPageResponse.getStatusCode().is2xxSuccessful() || clearPageResponse.getStatusCode().is3xxRedirection());
        assertEquals(0, bufferAdminProvider.snapshot(SERVICE_ID, METHOD_ID, 10).pendingCount());
    }

    @Test
    void rendersStorageRecoveryBannerAndExposesItOverSummaryApi() {
        startupRecoveryState.record(
                AslStartupRecoveryState.StorageRecoveryNotice.archivedCorruptStore(
                        java.nio.file.Path.of("data", "asl-consumer-sample-queue.db"),
                        java.nio.file.Path.of("data", "asl-consumer-sample-queue.db.corrupt-123"),
                        java.nio.file.Path.of("data", "asl-consumer-sample-queue.db")
                )
        );

        ResponseEntity<String> summaryResponse = restTemplate.getForEntity("/asl/api/summary", String.class);
        assertEquals(HttpStatus.OK, summaryResponse.getStatusCode());
        assertTrue(summaryResponse.getBody().contains("Recovered queue store on startup"));
        assertTrue(summaryResponse.getBody().contains("Corrupt file archived"));
        assertTrue(summaryResponse.getBody().contains("asl-consumer-sample-queue.db.corrupt-123"));

        ResponseEntity<String> pageResponse = restTemplate.getForEntity("/asl", String.class);
        assertEquals(HttpStatus.OK, pageResponse.getStatusCode());
        assertTrue(pageResponse.getBody().contains("Startup Storage Recovery"));
        assertTrue(pageResponse.getBody().contains("Recovered queue store on startup"));
        assertTrue(pageResponse.getBody().contains("Corrupt file archived"));
        assertTrue(pageResponse.getBody().contains("Archived To"));
        assertTrue(pageResponse.getBody().contains("asl-consumer-sample-queue.db.corrupt-123"));
        assertTrue(pageResponse.getBody().contains("Active Store"));
    }

    @Test
    void exposesAndUpdatesAdminConfigurationOverRest() {
        ResponseEntity<String> configResponse = restTemplate.getForEntity("/asl/api/config", String.class);

        assertEquals(HttpStatus.OK, configResponse.getStatusCode());
        assertTrue(configResponse.getBody().contains("\"bufferPreviewLimit\":50"));
        assertTrue(configResponse.getBody().contains("\"attentionLimit\":8"));
        assertTrue(configResponse.getBody().contains("\"mediumUtilizationPercent\":40"));
        assertTrue(configResponse.getBody().contains("\"highUtilizationPercent\":80"));
        assertTrue(configResponse.getBody().contains("\"defaultIntervalMs\":5000"));
        assertTrue(configResponse.getBody().contains("\"pagePath\":\"/asl\""));

        ResponseEntity<String> uiUpdateResponse = restTemplate.postForEntity(
                "/asl/api/config/ui",
                Map.of(
                        "heroTitle", "Reactor Operations Console",
                        "queueBufferTitle", "Queue State"
                ),
                String.class
        );

        assertEquals(HttpStatus.OK, uiUpdateResponse.getStatusCode());
        assertTrue(uiUpdateResponse.getBody().contains("Reactor Operations Console"));
        assertTrue(uiUpdateResponse.getBody().contains("Queue State"));

        ResponseEntity<String> runtimeUpdateResponse = restTemplate.postForEntity(
                "/asl/api/config/runtime",
                Map.ofEntries(
                        Map.entry("bufferPreviewLimit", 3),
                        Map.entry("attentionLimit", 4),
                        Map.entry("mediumUtilizationPercent", 30),
                        Map.entry("highUtilizationPercent", 70),
                        Map.entry("liveRefreshEnabled", false),
                        Map.entry("liveBufferEnabled", false),
                        Map.entry("defaultIntervalMs", 10000),
                        Map.entry("intervalOptionsMs", java.util.List.of(5000, 10000, 15000)),
                        Map.entry("changeFlashMs", 900),
                        Map.entry("successMessageAutoHideMs", 1800),
                        Map.entry("errorMessageAutoHideMs", 3600)
                ),
                String.class
        );

        assertEquals(HttpStatus.OK, runtimeUpdateResponse.getStatusCode());
        assertTrue(runtimeUpdateResponse.getBody().contains("\"bufferPreviewLimit\":3"));
        assertTrue(runtimeUpdateResponse.getBody().contains("\"attentionLimit\":4"));
        assertTrue(runtimeUpdateResponse.getBody().contains("\"mediumUtilizationPercent\":30"));
        assertTrue(runtimeUpdateResponse.getBody().contains("\"highUtilizationPercent\":70"));
        assertTrue(runtimeUpdateResponse.getBody().contains("\"liveRefreshEnabled\":false"));
        assertTrue(runtimeUpdateResponse.getBody().contains("\"liveBufferEnabled\":false"));
        assertTrue(runtimeUpdateResponse.getBody().contains("\"defaultIntervalMs\":10000"));
        assertTrue(runtimeUpdateResponse.getBody().contains("\"changeFlashMs\":900"));
        assertTrue(runtimeUpdateResponse.getBody().contains("\"successMessageAutoHideMs\":1800"));
        assertTrue(runtimeUpdateResponse.getBody().contains("\"errorMessageAutoHideMs\":3600"));

        ResponseEntity<String> pageResponse = restTemplate.getForEntity("/asl", String.class);
        assertTrue(pageResponse.getBody().contains("Reactor Operations Console"));
        assertTrue(pageResponse.getBody().contains("Queue State"));
        assertTrue(pageResponse.getBody().contains("data-default-live-refresh-enabled"));
        assertTrue(pageResponse.getBody().contains("data-default-live-buffer-enabled"));
        assertTrue(pageResponse.getBody().contains("data-default-refresh-interval-ms"));
        assertTrue(pageResponse.getBody().contains("<option value=\"15000\">15s</option>"));
    }

    @Test
    void updatesAsyncModeConsumerThreadsAndReplayOverRest() {
        String modeUri = uri("/asl/api/services/{serviceId}/methods/{methodId}/mode", SERVICE_ID, ASYNC_METHOD_ID);
        ResponseEntity<String> modeResponse = restTemplate.postForEntity(
                modeUri,
                Map.of("executionMode", "ASYNC"),
                String.class
        );

        assertEquals(HttpStatus.OK, modeResponse.getStatusCode());
        assertTrue(modeResponse.getBody().contains("\"executionMode\":\"ASYNC\""));
        assertEquals(ExecutionMode.ASYNC, registry.service(SERVICE_ID).methodById(ASYNC_METHOD_ID).executionMode());

        String threadsUri = uri("/asl/api/services/{serviceId}/methods/{methodId}/consumer-threads", SERVICE_ID, ASYNC_METHOD_ID);
        ResponseEntity<String> threadsResponse = restTemplate.postForEntity(
                threadsUri,
                Map.of("consumerThreads", 5),
                String.class
        );

        assertEquals(HttpStatus.OK, threadsResponse.getStatusCode());
        assertTrue(threadsResponse.getBody().contains("\"consumerThreads\":5"));
        assertEquals(5, registry.service(SERVICE_ID).methodById(ASYNC_METHOD_ID).consumerThreads());

        ResponseEntity<Void> replayResponse = restTemplate.postForEntity(
                uri("/asl/api/services/{serviceId}/methods/{methodId}/buffer/{entryId}/replay", SERVICE_ID, ASYNC_METHOD_ID, "async-failed-1"),
                null,
                Void.class
        );

        assertEquals(HttpStatus.NO_CONTENT, replayResponse.getStatusCode());
        MethodBufferSnapshot snapshot = bufferAdminProvider.snapshot(SERVICE_ID, ASYNC_METHOD_ID, 10);
        assertEquals(1, snapshot.pendingCount());
        assertEquals(0, snapshot.failedCount());
    }

    @Test
    void appliesConsolidatedMethodConfigurationOverRest() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                uri("/asl/api/services/{serviceId}/methods/{methodId}/configuration", SERVICE_ID, ASYNC_METHOD_ID),
                Map.of(
                        "enabled", false,
                        "unavailableMessage", "paused-by-config",
                        "maxConcurrency", 7,
                        "executionMode", "ASYNC",
                        "consumerThreads", 6
                ),
                String.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("\"enabled\":false"));
        assertTrue(response.getBody().contains("\"maxConcurrency\":7"));
        assertTrue(response.getBody().contains("\"consumerThreads\":6"));
        assertTrue(response.getBody().contains("\"executionMode\":\"ASYNC\""));
        assertEquals("paused-by-config", registry.service(SERVICE_ID).methodById(ASYNC_METHOD_ID).snapshot().unavailableMessage());
    }

    private String uri(String template, Object... variables) {
        return UriComponentsBuilder.fromPath(template)
                .buildAndExpand(variables)
                .encode()
                .toUriString();
    }

    private String uri(String template) {
        return uri(template, SERVICE_ID, METHOD_ID);
    }

    @SpringBootApplication
    static class TestApplication {
        public static void main(String[] args) {
            SpringApplication.run(TestApplication.class, args);
        }

        @Bean
        GovernanceRegistry governanceRegistry() {
            GovernanceRegistry registry = new GovernanceRegistry();
            registry.register(new ServiceDescriptor(
                    SERVICE_ID,
                    new MethodDescriptor[]{
                            new MethodDescriptor(METHOD_ID, "process", true, 3, "Method is disabled", false, 0),
                            new MethodDescriptor(ASYNC_METHOD_ID, "publish", true, 2, "Publish is disabled", true, 1)
                    }
            ));
            return registry;
        }

        @Bean
        InMemoryAsyncExecutionEngine bufferAdminProvider(GovernanceRegistry registry) {
            InMemoryAsyncExecutionEngine engine = new InMemoryAsyncExecutionEngine();
            registry.attachAsyncExecutionEngine(engine);
            return engine;
        }
    }

    static final class InMemoryAsyncExecutionEngine implements AsyncExecutionEngine {
        private final Map<String, List<MethodBufferEntryView>> entries = new ConcurrentHashMap<>();

        InMemoryAsyncExecutionEngine() {
            reset();
        }

        void reset() {
            entries.put(key(SERVICE_ID, METHOD_ID), new ArrayList<>(List.of(
                    new MethodBufferEntryView("buffer-1", "QUEUED", "demo payload", Instant.parse("2026-03-24T09:00:00Z"), 0, null, null, null, "jackson-json-v1", "demo.process", "v2")
            )));
            entries.put(key(SERVICE_ID, ASYNC_METHOD_ID), new ArrayList<>(List.of(
                    new MethodBufferEntryView("async-failed-1", "FAILED", "async payload", Instant.parse("2026-03-24T09:01:00Z"), 1, "java.lang.IllegalStateException: boom", "java.lang.IllegalStateException", "BUSINESS", "jackson-json-v1", "demo.publish", "v2")
            )));
        }

        @Override
        public void register(MethodRuntime runtime, AsyncMethodBinding binding) {
        }

        @Override
        public void enqueue(MethodRuntime runtime, Object[] arguments) {
        }

        @Override
        public void applyRuntime(MethodRuntime runtime) {
        }

        @Override
        public boolean supports(String serviceId, String methodId) {
            return entries.containsKey(key(serviceId, methodId));
        }

        @Override
        public MethodBufferSnapshot snapshot(String serviceId, String methodId, int limit) {
            List<MethodBufferEntryView> current = entries.getOrDefault(key(serviceId, methodId), List.of());
            List<MethodBufferEntryView> preview = current.stream().limit(limit).toList();
            long pending = current.stream().filter(entry -> "QUEUED".equals(entry.state()) || "PENDING".equals(entry.state())).count();
            long failed = current.stream().filter(entry -> "FAILED".equals(entry.state())).count();
            long inProgress = current.stream().filter(entry -> "IN_PROGRESS".equals(entry.state())).count();
            return new MethodBufferSnapshot(serviceId, methodId, true, pending, failed, inProgress, preview);
        }

        @Override
        public int clear(String serviceId, String methodId) {
            List<MethodBufferEntryView> current = entries.get(key(serviceId, methodId));
            if (current == null) {
                return 0;
            }
            int cleared = current.size();
            current.clear();
            return cleared;
        }

        @Override
        public boolean delete(String serviceId, String methodId, String entryId) {
            List<MethodBufferEntryView> current = entries.get(key(serviceId, methodId));
            if (current == null) {
                return false;
            }
            return current.removeIf(entry -> entry.entryId().equals(entryId));
        }

        @Override
        public boolean replay(String serviceId, String methodId, String entryId) {
            List<MethodBufferEntryView> current = entries.get(key(serviceId, methodId));
            if (current == null) {
                return false;
            }
            for (int i = 0; i < current.size(); i++) {
                MethodBufferEntryView entry = current.get(i);
                if (entry.entryId().equals(entryId) && "FAILED".equals(entry.state())) {
                    current.set(i, new MethodBufferEntryView(
                            entry.entryId(),
                            "PENDING",
                            entry.summary(),
                            entry.enqueuedAt(),
                            entry.attempts(),
                            null,
                            null,
                            null,
                            entry.codecId(),
                            entry.payloadType(),
                            entry.payloadVersion()
                    ));
                    return true;
                }
            }
            return false;
        }

        private static String key(String serviceId, String methodId) {
            return serviceId + "::" + methodId;
        }
    }
}
