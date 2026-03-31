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
        if (initialMaxConcurrency <= 0) {
            throw new IllegalArgumentException("initialMaxConcurrency must be positive");
        }
        if (initialConsumerThreads < 0) {
            throw new IllegalArgumentException("initialConsumerThreads must be zero or positive");
        }
    }
}
