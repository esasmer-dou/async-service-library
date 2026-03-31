package com.reactor.asl.consumer.sample;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class ChaosLabServiceImpl implements ChaosLabService {
    private final CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
    private final AsyncScenarioControl emitScenario = new AsyncScenarioControl("chaos.emit");

    @Override
    public void emit(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("payload must not be blank");
        }
        emitScenario.beforeInvocation("Configured async failure for chaos emit: " + payload);
        events.add("CHAOS:" + payload);
    }

    @Override
    public List<String> events() {
        return List.copyOf(events);
    }

    @Override
    public void reset() {
        events.clear();
        emitScenario.reset();
    }

    public AsyncScenarioSnapshot configureEmitScenario(AsyncScenarioRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        emitScenario.configure(request.failuresRemaining(), request.processingDelayMillis());
        return emitScenario.snapshot();
    }

    public AsyncScenarioSnapshot emitScenario() {
        return emitScenario.snapshot();
    }
}
