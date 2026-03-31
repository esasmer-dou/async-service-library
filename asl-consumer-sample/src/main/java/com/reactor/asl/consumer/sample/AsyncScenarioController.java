package com.reactor.asl.consumer.sample;

import com.reactor.asl.core.ExecutionMode;
import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.MethodRuntime;
import com.reactor.asl.mapdb.MapDbAsyncExecutionEngine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/test/scenarios")
public class AsyncScenarioController {
    private final MailServiceImpl mailService;
    private final CustomerServiceImpl customerService;
    private final InventoryServiceImpl inventoryService;
    private final ChaosLabServiceImpl chaosLabService;
    private final GovernanceRegistry registry;
    private final MapDbAsyncExecutionEngine asyncExecutionEngine;

    public AsyncScenarioController(
            MailServiceImpl mailService,
            CustomerServiceImpl customerService,
            InventoryServiceImpl inventoryService,
            ChaosLabServiceImpl chaosLabService,
            GovernanceRegistry registry,
            MapDbAsyncExecutionEngine asyncExecutionEngine
    ) {
        this.mailService = mailService;
        this.customerService = customerService;
        this.inventoryService = inventoryService;
        this.chaosLabService = chaosLabService;
        this.registry = registry;
        this.asyncExecutionEngine = asyncExecutionEngine;
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        mailService.reset();
        customerService.reset();
        inventoryService.reset();
        chaosLabService.reset();

        int resetMethods = 0;
        int clearedEntries = 0;
        for (var service : registry.services()) {
            for (MethodRuntime method : service.methods()) {
                method.enable();
                if (method.asyncCapable()) {
                    method.switchMode(ExecutionMode.SYNC);
                    method.setConsumerThreads(0);
                    asyncExecutionEngine.applyRuntime(method);
                    clearedEntries += asyncExecutionEngine.clear(method.serviceId(), method.methodId());
                }
                method.resetObservations();
                resetMethods++;
            }
        }
        return Map.of(
                "status", "reset",
                "resetMethods", resetMethods,
                "clearedEntries", clearedEntries
        );
    }

    @PostMapping("/mail/audit")
    public AsyncScenarioSnapshot configureMailAudit(@RequestBody AsyncScenarioRequest request) {
        return mailService.configureAuditScenario(request);
    }

    @GetMapping("/mail/audit")
    public AsyncScenarioSnapshot mailAudit() {
        return mailService.auditScenario();
    }

    @PostMapping("/customer/welcome")
    public AsyncScenarioSnapshot configureCustomerWelcome(@RequestBody AsyncScenarioRequest request) {
        return customerService.configureWelcomeScenario(request);
    }

    @GetMapping("/customer/welcome")
    public AsyncScenarioSnapshot customerWelcome() {
        return customerService.welcomeScenario();
    }

    @PostMapping("/inventory/snapshot")
    public AsyncScenarioSnapshot configureInventorySnapshot(@RequestBody AsyncScenarioRequest request) {
        return inventoryService.configureSnapshotScenario(request);
    }

    @GetMapping("/inventory/snapshot")
    public AsyncScenarioSnapshot inventorySnapshot() {
        return inventoryService.snapshotScenario();
    }

    @PostMapping("/chaos/emit")
    public AsyncScenarioSnapshot configureChaosEmit(@RequestBody AsyncScenarioRequest request) {
        return chaosLabService.configureEmitScenario(request);
    }

    @GetMapping("/chaos/emit")
    public AsyncScenarioSnapshot chaosEmit() {
        return chaosLabService.emitScenario();
    }
}
