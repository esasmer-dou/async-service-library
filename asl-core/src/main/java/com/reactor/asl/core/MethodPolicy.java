package com.reactor.asl.core;

import java.util.Objects;

public final class MethodPolicy {
    public static final int UNBOUNDED = Integer.MAX_VALUE;

    private final boolean enabled;
    private final int maxConcurrency;
    private final String unavailableMessage;

    private MethodPolicy(boolean enabled, int maxConcurrency, String unavailableMessage) {
        if (maxConcurrency <= 0) {
            throw new IllegalArgumentException("maxConcurrency must be positive");
        }
        this.enabled = enabled;
        this.maxConcurrency = maxConcurrency;
        this.unavailableMessage = Objects.requireNonNull(unavailableMessage, "unavailableMessage");
    }

    public static MethodPolicy enabledUnbounded() {
        return new MethodPolicy(true, UNBOUNDED, MethodRuntime.defaultUnavailableMessage());
    }

    public static MethodPolicy enabled(int maxConcurrency) {
        return new MethodPolicy(true, maxConcurrency, MethodRuntime.defaultUnavailableMessage());
    }

    public static MethodPolicy enabled(int maxConcurrency, String unavailableMessage) {
        return new MethodPolicy(true, maxConcurrency, unavailableMessage);
    }

    public static MethodPolicy disabled(String unavailableMessage, int maxConcurrency) {
        return new MethodPolicy(false, maxConcurrency, unavailableMessage);
    }

    public boolean enabled() {
        return enabled;
    }

    public int maxConcurrency() {
        return maxConcurrency;
    }

    public String unavailableMessage() {
        return unavailableMessage;
    }

    public MethodPolicy withEnabled(boolean enabled) {
        return new MethodPolicy(enabled, maxConcurrency, unavailableMessage);
    }

    public MethodPolicy withMaxConcurrency(int maxConcurrency) {
        return new MethodPolicy(enabled, maxConcurrency, unavailableMessage);
    }

    public MethodPolicy withUnavailableMessage(String unavailableMessage) {
        return new MethodPolicy(enabled, maxConcurrency, unavailableMessage);
    }
}
