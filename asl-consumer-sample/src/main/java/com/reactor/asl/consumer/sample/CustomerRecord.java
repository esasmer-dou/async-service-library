package com.reactor.asl.consumer.sample;

import java.time.Instant;

final class CustomerRecord {
    private final String id;
    private final String email;
    private final String fullName;
    private final Instant createdAt;
    private volatile boolean active;
    private volatile Instant activatedAt;

    CustomerRecord(String id, String email, String fullName, Instant createdAt) {
        this.id = id;
        this.email = email;
        this.fullName = fullName;
        this.createdAt = createdAt;
    }

    String id() {
        return id;
    }

    String email() {
        return email;
    }

    String fullName() {
        return fullName;
    }

    Instant createdAt() {
        return createdAt;
    }

    boolean active() {
        return active;
    }

    Instant activatedAt() {
        return activatedAt;
    }

    void activate(Instant instant) {
        this.active = true;
        this.activatedAt = instant;
    }
}
