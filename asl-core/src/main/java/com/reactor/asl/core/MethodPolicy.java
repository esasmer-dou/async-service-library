package com.reactor.asl.core;

import java.util.Objects;

public final class MethodPolicy {
    public static final int UNBOUNDED = Integer.MAX_VALUE;
    /** {@link #rateLimitPerSecond()} value meaning "no rate limit". */
    public static final int NO_RATE_LIMIT = 0;

    private final boolean enabled;
    private final int maxConcurrency;
    private final String unavailableMessage;
    private final int rateLimitPerSecond;

    private MethodPolicy(boolean enabled, int maxConcurrency, String unavailableMessage, int rateLimitPerSecond) {
        if (maxConcurrency <= 0) {
            throw new IllegalArgumentException("maxConcurrency must be positive");
        }
        if (rateLimitPerSecond < 0) {
            throw new IllegalArgumentException("rateLimitPerSecond must be zero (no limit) or positive");
        }
        this.enabled = enabled;
        this.maxConcurrency = maxConcurrency;
        this.unavailableMessage = Objects.requireNonNull(unavailableMessage, "unavailableMessage");
        this.rateLimitPerSecond = rateLimitPerSecond;
    }

    public static MethodPolicy enabledUnbounded() {
        return new MethodPolicy(true, UNBOUNDED, RuntimeDefaults.STANDARD.unavailableMessage(), NO_RATE_LIMIT);
    }

    public static MethodPolicy enabled(int maxConcurrency) {
        return new MethodPolicy(true, maxConcurrency, RuntimeDefaults.STANDARD.unavailableMessage(), NO_RATE_LIMIT);
    }

    public static MethodPolicy enabled(int maxConcurrency, String unavailableMessage) {
        return new MethodPolicy(true, maxConcurrency, unavailableMessage, NO_RATE_LIMIT);
    }

    public static MethodPolicy disabled(String unavailableMessage, int maxConcurrency) {
        return new MethodPolicy(false, maxConcurrency, unavailableMessage, NO_RATE_LIMIT);
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

    /** Calls per second allowed on this replica; {@link #NO_RATE_LIMIT} when unlimited. */
    public int rateLimitPerSecond() {
        return rateLimitPerSecond;
    }

    public boolean rateLimited() {
        return rateLimitPerSecond > 0;
    }

    public MethodPolicy withEnabled(boolean enabled) {
        return new MethodPolicy(enabled, maxConcurrency, unavailableMessage, rateLimitPerSecond);
    }

    public MethodPolicy withMaxConcurrency(int maxConcurrency) {
        return new MethodPolicy(enabled, maxConcurrency, unavailableMessage, rateLimitPerSecond);
    }

    public MethodPolicy withUnavailableMessage(String unavailableMessage) {
        return new MethodPolicy(enabled, maxConcurrency, unavailableMessage, rateLimitPerSecond);
    }

    public MethodPolicy withRateLimitPerSecond(int rateLimitPerSecond) {
        return new MethodPolicy(enabled, maxConcurrency, unavailableMessage, rateLimitPerSecond);
    }
}
