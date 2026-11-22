package com.reactor.asl.mapdb;

public record AsyncQueueLimits(long maxEntries, long maxPayloadBytes) {
    public AsyncQueueLimits {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        if (maxPayloadBytes <= 0) {
            throw new IllegalArgumentException("maxPayloadBytes must be positive");
        }
    }
}
