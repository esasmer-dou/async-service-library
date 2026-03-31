package com.reactor.asl.consumer.sample;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

abstract class AbstractSyntheticServiceSupport {
    private final String domain;
    private final ConcurrentMap<String, String> states = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<String> asyncEvents = new CopyOnWriteArrayList<>();

    protected AbstractSyntheticServiceSupport(String domain) {
        this.domain = domain;
    }

    protected String register(String id, String state) {
        validate(id);
        states.put(id, state);
        return view(id);
    }

    protected String transition(String id, String state) {
        validate(id);
        states.put(id, state);
        return view(id);
    }

    protected void publishPrimary(String id) {
        validate(id);
        asyncEvents.add(domain + ":primary:" + id);
    }

    protected void publishSecondary(String id) {
        validate(id);
        asyncEvents.add(domain + ":secondary:" + id);
    }

    protected String healthStatus() {
        return "UP";
    }

    protected List<String> events() {
        return List.copyOf(asyncEvents);
    }

    private String view(String id) {
        return domain + ":" + id + ":" + states.get(id);
    }

    private static void validate(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
    }
}
