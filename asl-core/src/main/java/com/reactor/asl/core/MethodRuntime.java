package com.reactor.asl.core;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

public final class MethodRuntime {
    private static volatile RuntimeDefaults legacyDefaults = RuntimeDefaults.STANDARD;

    private final String serviceId;
    private final String methodId;
    private final String methodName;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger peakInFlight = new AtomicInteger();
    private final LongAdder successCount = new LongAdder();
    private final LongAdder errorCount = new LongAdder();
    private final LongAdder rejectedCount = new LongAdder();
    private final AtomicLong successBaseline = new AtomicLong();
    private final AtomicLong errorBaseline = new AtomicLong();
    private final AtomicLong rejectedBaseline = new AtomicLong();
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final boolean asyncCapable;
    private final boolean usesDefaultUnavailableMessage;

    private volatile MethodPolicy policy;
    private volatile ExecutionMode executionMode;
    private volatile int consumerThreads;
    private volatile RuntimeDefaults defaults;
    private volatile MethodLatencyRecorder latencyRecorder;
    private volatile LimitMessage limitMessage;

    public MethodRuntime(String serviceId, MethodDescriptor descriptor) {
        this(serviceId, descriptor, legacyDefaults);
    }

    MethodRuntime(String serviceId, MethodDescriptor descriptor, RuntimeDefaults defaults) {
        this.serviceId = Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(descriptor, "descriptor");
        this.defaults = Objects.requireNonNull(defaults, "defaults");
        this.methodId = descriptor.methodId();
        this.methodName = descriptor.methodName();
        this.asyncCapable = descriptor.asyncCapable();
        this.usesDefaultUnavailableMessage = descriptor.unavailableMessage().isBlank();
        this.policy = descriptor.initiallyEnabled()
                ? MethodPolicy.enabled(descriptor.initialMaxConcurrency(), defaultUnavailableMessage(descriptor))
                : MethodPolicy.disabled(defaultUnavailableMessage(descriptor), descriptor.initialMaxConcurrency());
        this.executionMode = ExecutionMode.SYNC;
        this.consumerThreads = descriptor.initialConsumerThreads();
    }

    /**
     * @deprecated Configure {@link GovernanceRegistry} with {@link RuntimeDefaults} instead.
     * This method only affects directly constructed legacy {@link MethodRuntime} instances.
     */
    @Deprecated(forRemoval = true)
    public static synchronized void configureDefaults(
            String defaultUnavailableMessage,
            String maxConcurrencyExceededMessageTemplate
    ) {
        legacyDefaults = new RuntimeDefaults(defaultUnavailableMessage, maxConcurrencyExceededMessageTemplate);
    }

    /** @deprecated Use {@link GovernanceRegistry#runtimeDefaults()}. */
    @Deprecated(forRemoval = true)
    public static String defaultUnavailableMessage() {
        return legacyDefaults.unavailableMessage();
    }

    private String defaultUnavailableMessage(MethodDescriptor descriptor) {
        if (!descriptor.unavailableMessage().isBlank()) {
            return descriptor.unavailableMessage();
        }
        return defaults.unavailableMessage();
    }

    public String serviceId() {
        return serviceId;
    }

    public String methodId() {
        return methodId;
    }

    public String methodName() {
        return methodName;
    }

    public MethodPolicy policy() {
        return policy;
    }

    public boolean asyncCapable() {
        return asyncCapable;
    }

    public ExecutionMode executionMode() {
        return executionMode;
    }

    public int consumerThreads() {
        return consumerThreads;
    }

    public synchronized void updatePolicy(MethodPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public synchronized void enable() {
        updatePolicy(policy.withEnabled(true));
    }

    public synchronized void disable(String unavailableMessage) {
        String message = unavailableMessage == null || unavailableMessage.isBlank()
                ? defaults.unavailableMessage()
                : unavailableMessage;
        updatePolicy(policy.withEnabled(false).withUnavailableMessage(message));
    }

    public synchronized void setUnavailableMessage(String unavailableMessage) {
        String message = unavailableMessage == null || unavailableMessage.isBlank()
                ? defaults.unavailableMessage()
                : unavailableMessage;
        updatePolicy(policy.withUnavailableMessage(message));
    }

    public synchronized void setMaxConcurrency(int maxConcurrency) {
        updatePolicy(policy.withMaxConcurrency(maxConcurrency));
    }

    /**
     * Calls per second on this replica; {@link MethodPolicy#NO_RATE_LIMIT} removes the limit. A SYNC
     * call over the limit is rejected; in ASYNC mode the queue accepts the call and consumers start
     * at most this many executions per second.
     */
    public synchronized void setRateLimitPerSecond(int rateLimitPerSecond) {
        updatePolicy(policy.withRateLimitPerSecond(rateLimitPerSecond));
    }

    public void switchMode(ExecutionMode executionMode) {
        Objects.requireNonNull(executionMode, "executionMode");
        if (executionMode == ExecutionMode.ASYNC && !asyncCapable) {
            throw new IllegalStateException("Method is not async-capable: " + methodId);
        }
        this.executionMode = executionMode;
    }

    public void setConsumerThreads(int consumerThreads) {
        if (consumerThreads < 0) {
            throw new IllegalArgumentException("consumerThreads must be zero or positive");
        }
        this.consumerThreads = consumerThreads;
    }

    public boolean tryAcquire() {
        MethodPolicy current = policy;
        if (!current.enabled()) {
            rejectedCount.increment();
            return false;
        }
        int maxConcurrency = current.maxConcurrency();
        while (true) {
            int currentInFlight = inFlight.get();
            if (currentInFlight >= maxConcurrency) {
                rejectedCount.increment();
                return false;
            }
            if (inFlight.compareAndSet(currentInFlight, currentInFlight + 1)) {
                // The rate permit is taken only once a concurrency slot is held, so a concurrency
                // rejection never burns a permit.
                if (current.rateLimited() && !tryTakeRatePermit(current.rateLimitPerSecond(), System.nanoTime())) {
                    inFlight.decrementAndGet();
                    rejectedCount.increment();
                    return false;
                }
                updatePeakInFlight(currentInFlight + 1);
                return true;
            }
        }
    }

    // ---- rate limit: GCRA (virtual scheduling), lock-free, one CAS per permit ----
    // tat is the theoretical arrival time of the next permit. A burst of up to one second of
    // permits is allowed (e.g. 50 at once for 50/s), then permits come at the configured rate.
    private final AtomicLong rateTat = new AtomicLong(Long.MIN_VALUE);

    private boolean tryTakeRatePermit(int ratePerSecond, long nowNanos) {
        return reserveRatePermitNanos(ratePerSecond, nowNanos) == 0L;
    }

    /** Takes a permit and returns 0, or returns how long to wait (nanoseconds) without taking one. */
    private long reserveRatePermitNanos(int ratePerSecond, long nowNanos) {
        long interval = Math.max(1L, 1_000_000_000L / ratePerSecond);
        long tolerance = 1_000_000_000L - interval;
        while (true) {
            long tat = rateTat.get();
            long base = tat == Long.MIN_VALUE || tat - nowNanos < 0 ? nowNanos : tat;
            long ahead = base - nowNanos;
            if (ahead > tolerance) {
                return ahead - tolerance;
            }
            if (rateTat.compareAndSet(tat, base + interval)) {
                return 0L;
            }
        }
    }

    /**
     * For async consumers: takes a rate permit and returns 0, or returns the nanoseconds to wait
     * before the next one. Always 0 when the method has no rate limit.
     */
    public long reserveAsyncRatePermitNanos() {
        MethodPolicy current = policy;
        return current.rateLimited() ? reserveRatePermitNanos(current.rateLimitPerSecond(), System.nanoTime()) : 0L;
    }

    /** Gives back a permit taken with {@link #reserveAsyncRatePermitNanos()} that was not used. */
    public void returnAsyncRatePermit() {
        MethodPolicy current = policy;
        if (!current.rateLimited()) {
            return;
        }
        long interval = Math.max(1L, 1_000_000_000L / current.rateLimitPerSecond());
        long now = System.nanoTime();
        while (true) {
            long tat = rateTat.get();
            if (tat == Long.MIN_VALUE || tat - now <= 0L) {
                return; // already fully replenished
            }
            if (rateTat.compareAndSet(tat, tat - interval)) {
                return;
            }
        }
    }

    private boolean rateExhausted(MethodPolicy current, long nowNanos) {
        if (!current.rateLimited()) {
            return false;
        }
        long interval = Math.max(1L, 1_000_000_000L / current.rateLimitPerSecond());
        long tat = rateTat.get();
        return tat != Long.MIN_VALUE && tat - nowNanos > 1_000_000_000L - interval;
    }

    public void onSuccess() {
        successCount.increment();
    }

    public void onError(Throwable throwable) {
        errorCount.increment();
        if (throwable != null) {
            lastError.set(errorDetail(throwable, defaults));
        }
    }

    private static String errorDetail(Throwable throwable, RuntimeDefaults currentDefaults) {
        int limit = currentDefaults.maxLastErrorCharacters();
        String type = throwable.getClass().getName();
        if (currentDefaults.errorDetailPolicy() == RuntimeErrorDetailPolicy.TYPE_ONLY) {
            return truncate(type, limit);
        }

        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return truncate(type, limit);
        }
        if (type.length() >= limit) {
            return type.substring(0, limit);
        }

        String separator = ": ";
        int remaining = limit - type.length();
        if (remaining <= separator.length()) {
            return type + separator.substring(0, remaining);
        }
        int messageLimit = remaining - separator.length();
        String boundedMessage = message.length() <= messageLimit
                ? message
                : message.substring(0, messageLimit);
        return type + separator + boundedMessage;
    }

    private static String truncate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    public void onRejected() {
        rejectedCount.increment();
    }

    public void release() {
        inFlight.decrementAndGet();
    }

    /**
     * Start timestamp for latency recording, or {@code 0} when no recorder is installed. Called by
     * generated wrappers after a successful {@link #tryAcquire()}.
     */
    public long startTiming() {
        return latencyRecorder == null ? 0L : System.nanoTime();
    }

    /** Releases the concurrency slot and records latency when timing was started. */
    public void release(long startedNanos) {
        inFlight.decrementAndGet();
        if (startedNanos != 0L) {
            MethodLatencyRecorder recorder = latencyRecorder;
            if (recorder != null) {
                recorder.record(System.nanoTime() - startedNanos);
            }
        }
    }

    /** Installs or removes ({@code null}) the latency recorder for synchronous calls. */
    public void latencyRecorder(MethodLatencyRecorder recorder) {
        this.latencyRecorder = recorder;
    }

    public boolean tryAcceptAsyncInvocation() {
        MethodPolicy current = policy;
        if (!current.enabled()) {
            rejectedCount.increment();
            return false;
        }
        return true;
    }

    public boolean tryBeginAsyncExecution() {
        MethodPolicy current = policy;
        if (!current.enabled()) {
            return false;
        }
        int maxConcurrency = current.maxConcurrency();
        while (true) {
            int currentInFlight = inFlight.get();
            if (currentInFlight >= maxConcurrency) {
                return false;
            }
            if (inFlight.compareAndSet(currentInFlight, currentInFlight + 1)) {
                updatePeakInFlight(currentInFlight + 1);
                return true;
            }
        }
    }

    private void updatePeakInFlight(int candidate) {
        int observed = peakInFlight.get();
        while (candidate > observed && !peakInFlight.compareAndSet(observed, candidate)) {
            observed = peakInFlight.get();
        }
    }

    public MethodUnavailableException unavailableException() {
        MethodPolicy current = policy;
        if (!current.enabled()) {
            return new MethodUnavailableException(
                    serviceId,
                    methodId,
                    current.unavailableMessage(),
                    MethodUnavailableException.Reason.DISABLED,
                    false
            );
        }
        if (rateExhausted(current, System.nanoTime())) {
            return new MethodUnavailableException(
                    serviceId,
                    methodId,
                    rateLimitMessage(current),
                    MethodUnavailableException.Reason.RATE_LIMIT,
                    false
            );
        }
        return new MethodUnavailableException(
                serviceId,
                methodId,
                concurrencyLimitMessage(current),
                MethodUnavailableException.Reason.CONCURRENCY_LIMIT,
                false
        );
    }

    /** Formats the limit message once per policy/defaults pair instead of on every rejection. */
    private String concurrencyLimitMessage(MethodPolicy current) {
        RuntimeDefaults currentDefaults = defaults;
        LimitMessage cached = limitMessage;
        if (cached != null && cached.policy == current && cached.defaults == currentDefaults) {
            return cached.message;
        }
        String message = String.format(
                currentDefaults.maxConcurrencyExceededMessageTemplate(),
                current.maxConcurrency()
        );
        limitMessage = new LimitMessage(current, currentDefaults, message);
        return message;
    }

    private record LimitMessage(MethodPolicy policy, RuntimeDefaults defaults, String message) {
    }

    private volatile LimitMessage rateMessage;

    private String rateLimitMessage(MethodPolicy current) {
        LimitMessage cached = rateMessage;
        if (cached != null && cached.policy == current) {
            return cached.message;
        }
        String message = "Method reached its rate limit of " + current.rateLimitPerSecond() + " calls per second";
        rateMessage = new LimitMessage(current, defaults, message);
        return message;
    }

    public MethodStatsSnapshot stats() {
        return new MethodStatsSnapshot(
                observedCount(successCount, successBaseline),
                observedCount(errorCount, errorBaseline),
                observedCount(rejectedCount, rejectedBaseline),
                inFlight.get(),
                peakInFlight.get()
        );
    }

    public String lastError() {
        return lastError.get();
    }

    public long successCount() {
        return observedCount(successCount, successBaseline);
    }

    public long errorCount() {
        return observedCount(errorCount, errorBaseline);
    }

    public long rejectedCount() {
        return observedCount(rejectedCount, rejectedBaseline);
    }

    /** Monotonic lifetime total intended for metrics backends. */
    public long totalSuccessCount() {
        return successCount.sum();
    }

    /** Monotonic lifetime total intended for metrics backends. */
    public long totalErrorCount() {
        return errorCount.sum();
    }

    /** Monotonic lifetime total intended for metrics backends. */
    public long totalRejectedCount() {
        return rejectedCount.sum();
    }

    public int inFlight() {
        return inFlight.get();
    }

    public int peakInFlight() {
        return peakInFlight.get();
    }

    public MethodRuntimeSnapshot snapshot() {
        MethodPolicy current = policy;
        MethodStatsSnapshot stats = stats();
        return new MethodRuntimeSnapshot(
                serviceId,
                methodId,
                methodName,
                current.enabled(),
                current.maxConcurrency(),
                asyncCapable,
                executionMode,
                consumerThreads,
                current.unavailableMessage(),
                stats.successCount(),
                stats.errorCount(),
                stats.rejectedCount(),
                stats.inFlight(),
                stats.peakInFlight(),
                lastError()
        );
    }

    public void resetObservations() {
        // Active work is a live gauge. Resetting it races with release() and can make it negative.
        peakInFlight.set(inFlight.get());
        successBaseline.set(successCount.sum());
        errorBaseline.set(errorCount.sum());
        rejectedBaseline.set(rejectedCount.sum());
        lastError.set(null);
    }

    private static long observedCount(LongAdder total, AtomicLong baseline) {
        return Math.max(0L, total.sum() - baseline.get());
    }

    synchronized void updateDefaults(RuntimeDefaults updatedDefaults) {
        RuntimeDefaults previous = defaults;
        defaults = Objects.requireNonNull(updatedDefaults, "updatedDefaults");
        if (usesDefaultUnavailableMessage
                && policy.unavailableMessage().equals(previous.unavailableMessage())) {
            setUnavailableMessage(updatedDefaults.unavailableMessage());
        }
    }
}
