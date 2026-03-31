package com.reactor.asl.consumer.sample;

import java.time.Instant;

final class InventoryRecord {
    private final String sku;
    private final String title;
    private final Instant createdAt;
    private volatile int available;
    private volatile int reserved;
    private volatile Instant updatedAt;

    InventoryRecord(String sku, String title, int available, Instant createdAt) {
        this.sku = sku;
        this.title = title;
        this.available = available;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    String sku() {
        return sku;
    }

    String title() {
        return title;
    }

    int available() {
        return available;
    }

    int reserved() {
        return reserved;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant updatedAt() {
        return updatedAt;
    }

    void replaceAvailable(int available, Instant updatedAt) {
        this.available = available;
        this.updatedAt = updatedAt;
    }

    void reserveOne(Instant updatedAt) {
        if (available <= 0) {
            throw new IllegalStateException("No available inventory for sku: " + sku);
        }
        this.available -= 1;
        this.reserved += 1;
        this.updatedAt = updatedAt;
    }
}
