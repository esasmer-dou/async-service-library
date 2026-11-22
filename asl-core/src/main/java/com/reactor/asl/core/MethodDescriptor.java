package com.reactor.asl.core;

import java.util.Objects;

public record MethodDescriptor(
        String methodId,
        String methodName,
        boolean initiallyEnabled,
        int initialMaxConcurrency,
        String unavailableMessage,
        boolean asyncCapable,
        int initialConsumerThreads
) {
    public MethodDescriptor {
        Objects.requireNonNull(methodId, "methodId");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(unavailableMessage, "unavailableMessage");
        if (methodId.isBlank()) {
            throw new IllegalArgumentException("methodId must not be blank");
        }
        if (methodName.isBlank()) {
            throw new IllegalArgumentException("methodName must not be blank");
        }
        if (initialMaxConcurrency <= 0) {
            throw new IllegalArgumentException("initialMaxConcurrency must be positive");
        }
        if (initialConsumerThreads < 0) {
            throw new IllegalArgumentException("initialConsumerThreads must be zero or positive");
        }
        if (!asyncCapable && initialConsumerThreads != 0) {
            throw new IllegalArgumentException("initialConsumerThreads must be zero for sync-only methods");
        }
    }
}
