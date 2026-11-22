package com.reactor.asl.consumer.sample;

import com.reactor.asl.mapdb.MapDbAsyncExecutionEngine;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chaos")
public class ChaosLabController {
    private final ChaosLabService chaosLabService;
    private final ChaosLabServiceImpl delegate;
    private final ObjectProvider<MapDbAsyncExecutionEngine> engine;

    public ChaosLabController(
            ChaosLabService chaosLabService,
            ChaosLabServiceImpl delegate,
            ObjectProvider<MapDbAsyncExecutionEngine> engine
    ) {
        this.chaosLabService = chaosLabService;
        this.delegate = delegate;
        this.engine = engine;
    }

    @PostMapping("/emit/{payload}")
    public Map<String, String> emit(@PathVariable("payload") String payload) {
        chaosLabService.emit(payload);
        return Map.of("status", "accepted", "payload", payload);
    }

    @GetMapping("/events")
    public List<String> events() {
        return delegate.events();
    }

    /** Whether the MapDB store was archived and recreated at startup; used by the recovery stress suite. */
    @GetMapping("/storage-recovery")
    public Map<String, Object> storageRecovery() {
        Map<String, Object> result = new LinkedHashMap<>();
        MapDbAsyncExecutionEngine current = engine.getIfAvailable();
        result.put("recovered", false);
        if (current != null) {
            current.startupRecovery().ifPresent(recovery -> {
                result.put("recovered", true);
                result.put("originalPath", String.valueOf(recovery.originalPath()));
                result.put("archivedPath", String.valueOf(recovery.archivedPath()));
            });
        }
        return result;
    }
}
