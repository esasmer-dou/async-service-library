package com.reactor.asl.consumer.sample;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chaos")
public class ChaosLabController {
    private final ChaosLabService chaosLabService;
    private final ChaosLabServiceImpl delegate;

    public ChaosLabController(ChaosLabService chaosLabService, ChaosLabServiceImpl delegate) {
        this.chaosLabService = chaosLabService;
        this.delegate = delegate;
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
}
