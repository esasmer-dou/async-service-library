package com.reactor.asl.consumer.sample;

import com.reactor.asl.core.ExecutionMode;
import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.MethodBufferSnapshot;
import com.reactor.asl.core.MethodRuntime;
import com.reactor.asl.core.MethodUnavailableException;
import com.reactor.asl.core.ServiceRuntime;
import com.reactor.asl.mapdb.MapDbAsyncExecutionEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = AslConsumerSampleApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "asl.async.mapdb.enabled=true",
                "asl.async.mapdb.path=./target/test-data/asl-consumer-sample-queue.db"
        }
)
class AslConsumerSampleIntegrationTest {
    private static final String MAIL_SERVICE_ID = "mail.service";
    private static final String MAIL_SEND_METHOD_ID = "send(java.lang.String)";
    private static final String MAIL_PUBLISH_METHOD_ID = "publishAudit(java.lang.String)";
    private static final String CUSTOMER_SERVICE_ID = "customer.service";
    private static final String CUSTOMER_ACTIVATE_METHOD_ID = "activate(java.lang.String)";
    private static final String CUSTOMER_PUBLISH_METHOD_ID = "publishWelcome(java.lang.String)";
    private static final String INVENTORY_SERVICE_ID = "inventory.service";
    private static final String INVENTORY_RESERVE_METHOD_ID = "reserve(java.lang.String)";
    private static final String INVENTORY_PUBLISH_METHOD_ID = "publishSnapshot(java.lang.String)";
    private static final String CHAOS_SERVICE_ID = "chaos.service";
    private static final String BILLING_SERVICE_ID = "billing.service";
    private static final String PAYMENT_SERVICE_ID = "payment.service";
    private static final String NOTIFICATION_SERVICE_ID = "notification.service";
    private static final String REPORTING_SERVICE_ID = "reporting.service";
    private static final String PRICING_SERVICE_ID = "pricing.service";
    private static final String FRAUD_SERVICE_ID = "fraud.service";
    private static final String SHIPMENT_SERVICE_ID = "shipment.service";
    private static final String LEDGER_SERVICE_ID = "ledger.service";
    private static final String CATALOG_SERVICE_ID = "catalog.service";
    private static final String SEARCH_SERVICE_ID = "search.service";

    @Autowired
    private MailService mailService;

    @Autowired
    private MailServiceImpl delegate;

    @Autowired
    private CustomerServiceImpl customerDelegate;

    @Autowired
    private InventoryServiceImpl inventoryDelegate;

    @Autowired
    private GovernanceRegistry registry;

    @Autowired
    private MapDbAsyncExecutionEngine asyncExecutionEngine;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void resetState() throws Exception {
        Path dbFile = Path.of("./target/test-data/asl-consumer-sample-queue.db");
        Files.createDirectories(dbFile.toAbsolutePath().getParent());
        resetMethod(MAIL_SERVICE_ID, MAIL_SEND_METHOD_ID);
        resetMethod(MAIL_SERVICE_ID, MAIL_PUBLISH_METHOD_ID);
        resetMethod(CUSTOMER_SERVICE_ID, CUSTOMER_ACTIVATE_METHOD_ID);
        resetMethod(CUSTOMER_SERVICE_ID, CUSTOMER_PUBLISH_METHOD_ID);
        resetMethod(INVENTORY_SERVICE_ID, INVENTORY_RESERVE_METHOD_ID);
        resetMethod(INVENTORY_SERVICE_ID, INVENTORY_PUBLISH_METHOD_ID);
        asyncExecutionEngine.clear(MAIL_SERVICE_ID, MAIL_PUBLISH_METHOD_ID);
        asyncExecutionEngine.clear(CUSTOMER_SERVICE_ID, CUSTOMER_PUBLISH_METHOD_ID);
        asyncExecutionEngine.clear(INVENTORY_SERVICE_ID, INVENTORY_PUBLISH_METHOD_ID);
        delegate.reset();
        customerDelegate.reset();
        inventoryDelegate.reset();
    }

    @Test
    void appliesStartupValuesFromApplicationYaml() {
        // application.yml sets max-concurrency 6 over the annotation's 4; the keys only bind when written in brackets.
        assertEquals(6, registry.service(MAIL_SERVICE_ID).methodById(MAIL_SEND_METHOD_ID).policy().maxConcurrency());
    }

    @Test
    void injectsGovernedWrapperAndSupportsRuntimeControl() {
        assertTrue(mailService.getClass().getName().endsWith("AslGoverned"));

        MailMessageView draft = mailService.createDraft(new CreateMailRequest("user@company.com", "hello", "payload"));
        assertEquals(MailMessageStatus.SENT, mailService.send(draft.id()).status());

        MethodRuntime sendRuntime = registry.service(MAIL_SERVICE_ID).methodById(MAIL_SEND_METHOD_ID);
        sendRuntime.disable("blocked");

        MethodUnavailableException exception = assertThrows(MethodUnavailableException.class, () -> mailService.send(draft.id()));
        assertTrue(exception.getMessage().contains("blocked"));
    }

    @Test
    void exposesControllerAndAdminPlane() {
        ResponseEntity<MailMessageView> createResponse = restTemplate.postForEntity(
                "/api/mails",
                new CreateMailRequest("user@company.com", "welcome", "hello from sample"),
                MailMessageView.class
        );
        assertEquals(HttpStatus.CREATED, createResponse.getStatusCode());
        String mailId = createResponse.getBody().id();

        ResponseEntity<List<MailMessageView>> listResponse = restTemplate.exchange(
                "/api/mails",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {
                }
        );
        assertEquals(HttpStatus.OK, listResponse.getStatusCode());
        assertEquals(1, listResponse.getBody().size());

        ResponseEntity<MailMessageView> sendResponse = restTemplate.postForEntity(
                "/api/mails/" + mailId + "/send",
                null,
                MailMessageView.class
        );
        assertEquals(HttpStatus.OK, sendResponse.getStatusCode());
        assertEquals(MailMessageStatus.SENT, sendResponse.getBody().status());

        MethodRuntime publishRuntime = registry.service(MAIL_SERVICE_ID).methodById(MAIL_PUBLISH_METHOD_ID);
        publishRuntime.switchMode(ExecutionMode.ASYNC);
        publishRuntime.setConsumerThreads(1);
        asyncExecutionEngine.applyRuntime(publishRuntime);

        ResponseEntity<Map> publishResponse = restTemplate.postForEntity(
                "/api/mails/" + mailId + "/publish-audit",
                null,
                Map.class
        );
        assertEquals(HttpStatus.OK, publishResponse.getStatusCode());
        waitFor(() -> !delegate.auditEvents().isEmpty(), 5);

        ResponseEntity<List<String>> auditResponse = restTemplate.exchange(
                "/api/mails/audit-events",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {
                }
        );
        assertEquals(HttpStatus.OK, auditResponse.getStatusCode());
        assertTrue(auditResponse.getBody().getFirst().startsWith("AUDIT:" + mailId));

        ResponseEntity<Map> healthResponse = restTemplate.getForEntity("/api/health", Map.class);
        assertEquals(HttpStatus.OK, healthResponse.getStatusCode());
        assertEquals("UP", healthResponse.getBody().get("status"));

        ResponseEntity<CustomerView> customerCreateResponse = restTemplate.postForEntity(
                "/api/customers",
                new CreateCustomerRequest("mustafa@company.com", "Mustafa Korkmaz"),
                CustomerView.class
        );
        assertEquals(HttpStatus.CREATED, customerCreateResponse.getStatusCode());
        String customerId = customerCreateResponse.getBody().id();

        ResponseEntity<CustomerView> customerActivateResponse = restTemplate.postForEntity(
                "/api/customers/" + customerId + "/activate",
                null,
                CustomerView.class
        );
        assertEquals(HttpStatus.OK, customerActivateResponse.getStatusCode());
        assertTrue(customerActivateResponse.getBody().active());

        MethodRuntime customerPublishRuntime = registry.service(CUSTOMER_SERVICE_ID).methodById(CUSTOMER_PUBLISH_METHOD_ID);
        customerPublishRuntime.switchMode(ExecutionMode.ASYNC);
        customerPublishRuntime.setConsumerThreads(1);
        asyncExecutionEngine.applyRuntime(customerPublishRuntime);
        restTemplate.postForEntity("/api/customers/" + customerId + "/publish-welcome", null, Map.class);
        waitFor(() -> !customerDelegate.welcomeEvents().isEmpty(), 5);

        ResponseEntity<InventoryItemView> inventoryCreateResponse = restTemplate.postForEntity(
                "/api/inventory",
                new UpsertInventoryRequest("SKU-1", "Thermal Printer", 7),
                InventoryItemView.class
        );
        assertEquals(HttpStatus.CREATED, inventoryCreateResponse.getStatusCode());

        ResponseEntity<InventoryItemView> inventoryReserveResponse = restTemplate.postForEntity(
                "/api/inventory/SKU-1/reserve",
                null,
                InventoryItemView.class
        );
        assertEquals(HttpStatus.OK, inventoryReserveResponse.getStatusCode());
        assertEquals(6, inventoryReserveResponse.getBody().available());

        MethodRuntime inventoryPublishRuntime = registry.service(INVENTORY_SERVICE_ID).methodById(INVENTORY_PUBLISH_METHOD_ID);
        inventoryPublishRuntime.switchMode(ExecutionMode.ASYNC);
        inventoryPublishRuntime.setConsumerThreads(1);
        asyncExecutionEngine.applyRuntime(inventoryPublishRuntime);
        restTemplate.postForEntity("/api/inventory/SKU-1/publish-snapshot", null, Map.class);
        waitFor(() -> !inventoryDelegate.snapshotEvents().isEmpty(), 5);

        List<String> serviceIds = registry.services().stream().map(ServiceRuntime::serviceId).toList();
        assertEquals(14, serviceIds.size());
        assertTrue(serviceIds.containsAll(List.of(
                MAIL_SERVICE_ID, CUSTOMER_SERVICE_ID, INVENTORY_SERVICE_ID, CHAOS_SERVICE_ID,
                BILLING_SERVICE_ID, PAYMENT_SERVICE_ID, NOTIFICATION_SERVICE_ID, REPORTING_SERVICE_ID,
                PRICING_SERVICE_ID, FRAUD_SERVICE_ID, SHIPMENT_SERVICE_ID, LEDGER_SERVICE_ID,
                CATALOG_SERVICE_ID, SEARCH_SERVICE_ID
        )));
        assertTrue(registry.services().stream().mapToInt(service -> service.methods().size()).sum() >= 14);

        ResponseEntity<String> removedPortal = restTemplate.getForEntity("/asl/api/summary", String.class);
        assertEquals(HttpStatus.NOT_FOUND, removedPortal.getStatusCode());
    }

    @Test
    void mailAuditAsyncFailureReplayFlowIsVisibleThroughAdminBuffer() {
        String mailId = createMail("async-failure@company.com", "failure", "replay");

        configureScenario("/api/test/scenarios/mail/audit", 1, 0L);
        switchMethodMode(MAIL_SERVICE_ID, MAIL_PUBLISH_METHOD_ID, "ASYNC");
        resizeConsumers(MAIL_SERVICE_ID, MAIL_PUBLISH_METHOD_ID, 1);

        ResponseEntity<Map> publishResponse = restTemplate.postForEntity(
                "/api/mails/" + mailId + "/publish-audit",
                null,
                Map.class
        );
        assertEquals(HttpStatus.OK, publishResponse.getStatusCode());

        waitFor(() -> failedCount(MAIL_SERVICE_ID, MAIL_PUBLISH_METHOD_ID) == 1, 5);
        assertTrue(delegate.auditEvents().isEmpty());

        String failedEntryId = firstEntryId(MAIL_SERVICE_ID, MAIL_PUBLISH_METHOD_ID);
        configureScenario("/api/test/scenarios/mail/audit", 0, 0L);

        assertTrue(asyncExecutionEngine.replay(MAIL_SERVICE_ID, MAIL_PUBLISH_METHOD_ID, failedEntryId));

        waitFor(() -> delegate.auditEvents().size() == 1, 5);
        waitFor(() -> totalBuffered(MAIL_SERVICE_ID, MAIL_PUBLISH_METHOD_ID) == 0, 5);
        assertTrue(delegate.auditEvents().getFirst().startsWith("AUDIT:" + mailId));
    }

    @Test
    void customerWelcomeQueueSupportsDeleteAndClearWhileConsumersAreStopped() {
        String customerId = createCustomer("queue@company.com", "Queue Customer");

        switchMethodMode(CUSTOMER_SERVICE_ID, CUSTOMER_PUBLISH_METHOD_ID, "ASYNC");
        resizeConsumers(CUSTOMER_SERVICE_ID, CUSTOMER_PUBLISH_METHOD_ID, 0);

        restTemplate.postForEntity("/api/customers/" + customerId + "/publish-welcome", null, Map.class);
        restTemplate.postForEntity("/api/customers/" + customerId + "/publish-welcome", null, Map.class);
        restTemplate.postForEntity("/api/customers/" + customerId + "/publish-welcome", null, Map.class);

        waitFor(() -> pendingCount(CUSTOMER_SERVICE_ID, CUSTOMER_PUBLISH_METHOD_ID) == 3, 5);
        String entryId = firstEntryId(CUSTOMER_SERVICE_ID, CUSTOMER_PUBLISH_METHOD_ID);

        assertTrue(asyncExecutionEngine.delete(CUSTOMER_SERVICE_ID, CUSTOMER_PUBLISH_METHOD_ID, entryId));
        waitFor(() -> pendingCount(CUSTOMER_SERVICE_ID, CUSTOMER_PUBLISH_METHOD_ID) == 2, 5);

        assertEquals(2, asyncExecutionEngine.clear(CUSTOMER_SERVICE_ID, CUSTOMER_PUBLISH_METHOD_ID));

        waitFor(() -> totalBuffered(CUSTOMER_SERVICE_ID, CUSTOMER_PUBLISH_METHOD_ID) == 0, 5);
        assertTrue(customerDelegate.welcomeEvents().isEmpty());
    }

    @Test
    void inventorySnapshotQueueBuildsUpShowsInProgressAndDrainsAfterConsumerResize() {
        createInventory("SKU-ASYNC-1", "Async Inventory", 9);

        configureScenario("/api/test/scenarios/inventory/snapshot", 0, 400L);
        switchMethodMode(INVENTORY_SERVICE_ID, INVENTORY_PUBLISH_METHOD_ID, "ASYNC");
        resizeConsumers(INVENTORY_SERVICE_ID, INVENTORY_PUBLISH_METHOD_ID, 0);

        restTemplate.postForEntity("/api/inventory/SKU-ASYNC-1/publish-snapshot", null, Map.class);
        restTemplate.postForEntity("/api/inventory/SKU-ASYNC-1/publish-snapshot", null, Map.class);
        restTemplate.postForEntity("/api/inventory/SKU-ASYNC-1/publish-snapshot", null, Map.class);

        waitFor(() -> pendingCount(INVENTORY_SERVICE_ID, INVENTORY_PUBLISH_METHOD_ID) == 3, 5);

        resizeConsumers(INVENTORY_SERVICE_ID, INVENTORY_PUBLISH_METHOD_ID, 1);
        waitFor(() -> inProgressCount(INVENTORY_SERVICE_ID, INVENTORY_PUBLISH_METHOD_ID) >= 1, 5);

        resizeConsumers(INVENTORY_SERVICE_ID, INVENTORY_PUBLISH_METHOD_ID, 2);
        waitFor(() -> inventoryDelegate.snapshotEvents().size() == 3, 8);
        waitFor(() -> totalBuffered(INVENTORY_SERVICE_ID, INVENTORY_PUBLISH_METHOD_ID) == 0, 8);
    }

    @Test
    void scenarioResetRestoresCleanIdleBaseline() {
        String mailId = createMail("baseline-reset-mail@company.com", "baseline", "failure");
        String customerId = createCustomer("baseline-reset-customer@company.com", "Baseline Reset");

        configureScenario("/api/test/scenarios/mail/audit", 1, 0L);
        switchMethodMode(MAIL_SERVICE_ID, MAIL_PUBLISH_METHOD_ID, "ASYNC");
        resizeConsumers(MAIL_SERVICE_ID, MAIL_PUBLISH_METHOD_ID, 1);
        restTemplate.postForEntity("/api/mails/" + mailId + "/publish-audit", null, Map.class);
        waitFor(() -> failedCount(MAIL_SERVICE_ID, MAIL_PUBLISH_METHOD_ID) == 1, 5);

        switchMethodMode(CUSTOMER_SERVICE_ID, CUSTOMER_PUBLISH_METHOD_ID, "ASYNC");
        resizeConsumers(CUSTOMER_SERVICE_ID, CUSTOMER_PUBLISH_METHOD_ID, 0);
        restTemplate.postForEntity("/api/customers/" + customerId + "/publish-welcome", null, Map.class);
        restTemplate.postForEntity("/api/customers/" + customerId + "/publish-welcome", null, Map.class);
        waitFor(() -> pendingCount(CUSTOMER_SERVICE_ID, CUSTOMER_PUBLISH_METHOD_ID) == 2, 5);

        ResponseEntity<Map> resetResponse = restTemplate.postForEntity("/api/test/scenarios/reset", null, Map.class);
        assertEquals(HttpStatus.OK, resetResponse.getStatusCode());
        assertEquals("reset", resetResponse.getBody().get("status"));
        assertTrue(((Number) resetResponse.getBody().get("resetMethods")).intValue() >= 14);
        assertTrue(((Number) resetResponse.getBody().get("clearedEntries")).intValue() >= 3);

        waitFor(() -> totalBuffered(MAIL_SERVICE_ID, MAIL_PUBLISH_METHOD_ID) == 0, 5);
        waitFor(() -> totalBuffered(CUSTOMER_SERVICE_ID, CUSTOMER_PUBLISH_METHOD_ID) == 0, 5);
        assertEquals(0, failedCount(MAIL_SERVICE_ID, MAIL_PUBLISH_METHOD_ID));
    }

    @Test
    void stoppedAsyncMethodRejectsImmediatelyAndDoesNotQueue() {
        String mailId = createMail("stop@company.com", "stop", "reject");

        registry.service(MAIL_SERVICE_ID).methodById(MAIL_PUBLISH_METHOD_ID).disable("maintenance-window");

        ResponseEntity<String> publishResponse = restTemplate.postForEntity(
                "/api/mails/" + mailId + "/publish-audit",
                null,
                String.class
        );
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, publishResponse.getStatusCode());
        assertTrue(publishResponse.getBody().contains("maintenance-window"));
        assertEquals(0, totalBuffered(MAIL_SERVICE_ID, MAIL_PUBLISH_METHOD_ID));
        assertTrue(delegate.auditEvents().isEmpty());
    }

    @Test
    void asyncCapableMethodInSyncModeFailsInlineAndDoesNotUseBuffer() {
        String mailId = createMail("sync-inline@company.com", "sync", "inline");

        configureScenario("/api/test/scenarios/mail/audit", 1, 0L);
        switchMethodMode(MAIL_SERVICE_ID, MAIL_PUBLISH_METHOD_ID, "SYNC");
        resizeConsumers(MAIL_SERVICE_ID, MAIL_PUBLISH_METHOD_ID, 0);

        ResponseEntity<String> publishResponse = restTemplate.postForEntity(
                "/api/mails/" + mailId + "/publish-audit",
                null,
                String.class
        );
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, publishResponse.getStatusCode());
        assertEquals(0, totalBuffered(MAIL_SERVICE_ID, MAIL_PUBLISH_METHOD_ID));
        assertTrue(delegate.auditEvents().isEmpty());

        configureScenario("/api/test/scenarios/mail/audit", 0, 0L);
        ResponseEntity<Map> retryResponse = restTemplate.postForEntity(
                "/api/mails/" + mailId + "/publish-audit",
                null,
                Map.class
        );
        assertEquals(HttpStatus.OK, retryResponse.getStatusCode());
        assertEquals(1, delegate.auditEvents().size());
    }

    private void resetMethod(String serviceId, String methodId) {
        MethodRuntime runtime = registry.service(serviceId).methodById(methodId);
        runtime.enable();
        runtime.switchMode(ExecutionMode.SYNC);
        runtime.setConsumerThreads(0);
    }

    private String createMail(String recipient, String subject, String body) {
        ResponseEntity<MailMessageView> response = restTemplate.postForEntity(
                "/api/mails",
                new CreateMailRequest(recipient, subject, body),
                MailMessageView.class
        );
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        return response.getBody().id();
    }

    private String createCustomer(String email, String fullName) {
        ResponseEntity<CustomerView> response = restTemplate.postForEntity(
                "/api/customers",
                new CreateCustomerRequest(email, fullName),
                CustomerView.class
        );
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        return response.getBody().id();
    }

    private void createInventory(String sku, String title, int available) {
        ResponseEntity<InventoryItemView> response = restTemplate.postForEntity(
                "/api/inventory",
                new UpsertInventoryRequest(sku, title, available),
                InventoryItemView.class
        );
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
    }

    private void configureScenario(String path, int failuresRemaining, long processingDelayMillis) {
        ResponseEntity<AsyncScenarioSnapshot> response = restTemplate.postForEntity(
                path,
                new AsyncScenarioRequest(failuresRemaining, processingDelayMillis),
                AsyncScenarioSnapshot.class
        );
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(failuresRemaining, response.getBody().failuresRemaining());
        assertEquals(processingDelayMillis, response.getBody().processingDelayMillis());
    }

    private void switchMethodMode(String serviceId, String methodId, String executionMode) {
        MethodRuntime runtime = registry.service(serviceId).methodById(methodId);
        runtime.switchMode(ExecutionMode.valueOf(executionMode));
        asyncExecutionEngine.applyRuntime(runtime);
        assertEquals(ExecutionMode.valueOf(executionMode), runtime.snapshot().executionMode());
    }

    private void resizeConsumers(String serviceId, String methodId, int consumerThreads) {
        MethodRuntime runtime = registry.service(serviceId).methodById(methodId);
        runtime.setConsumerThreads(consumerThreads);
        asyncExecutionEngine.applyRuntime(runtime);
        assertEquals(consumerThreads, runtime.snapshot().consumerThreads());
    }

    private long pendingCount(String serviceId, String methodId) {
        return bufferSnapshot(serviceId, methodId).pendingCount();
    }

    private long failedCount(String serviceId, String methodId) {
        return bufferSnapshot(serviceId, methodId).failedCount();
    }

    private long inProgressCount(String serviceId, String methodId) {
        return bufferSnapshot(serviceId, methodId).inProgressCount();
    }

    private long totalBuffered(String serviceId, String methodId) {
        MethodBufferSnapshot snapshot = bufferSnapshot(serviceId, methodId);
        return snapshot.pendingCount() + snapshot.failedCount() + snapshot.inProgressCount();
    }

    private String firstEntryId(String serviceId, String methodId) {
        MethodBufferSnapshot snapshot = bufferSnapshot(serviceId, methodId);
        assertFalse(snapshot.entries().isEmpty());
        return snapshot.entries().getFirst().entryId();
    }

    private MethodBufferSnapshot bufferSnapshot(String serviceId, String methodId) {
        return asyncExecutionEngine.snapshot(serviceId, methodId, 50);
    }

    private static void waitFor(Check check, int timeoutSeconds) {
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

    @FunctionalInterface
    private interface Check {
        boolean matches();
    }
}
