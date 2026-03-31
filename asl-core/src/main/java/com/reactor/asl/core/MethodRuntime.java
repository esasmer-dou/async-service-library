package com.reactor.asl.core;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

public final class MethodRuntime {
    private static volatile String defaultUnavailableMessage = "Method is disabled";
    private static volatile String maxConcurrencyExceededMessageTemplate = "Method reached max concurrency: %d";

    private final String serviceId;
    private final String methodId;
    private final String methodName;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger peakInFlight = new AtomicInteger();
    private final LongAdder successCount = new LongAdder();
    private final LongAdder errorCount = new LongAdder();
    private final LongAdder rejectedCount = new LongAdder();
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final boolean asyncCapable;

    private volatile MethodPolicy policy;
    private volatile ExecutionMode executionMode;
    private volatile int consumerThreads;

    public MethodRuntime(String serviceId, MethodDescriptor descriptor) {
        this.serviceId = Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(descriptor, "descriptor");
        this.methodId = descriptor.methodId();
        this.methodName = descriptor.methodName();
        this.asyncCapable = descriptor.asyncCapable();
        this.policy = descriptor.initiallyEnabled()
                ? MethodPolicy.enabled(descriptor.initialMaxConcurrency(), defaultUnavailableMessage(descriptor))
                : MethodPolicy.disabled(defaultUnavailableMessage(descriptor), descriptor.initialMaxConcurrency());
        this.executionMode = ExecutionMode.SYNC;
        this.consumerThreads = descriptor.initialConsumerThreads();
    }

    public static synchronized void configureDefaults(
            String defaultUnavailableMessage,
            String maxConcurrencyExceededMessageTemplate
    ) {
        if (defaultUnavailableMessage == null || defaultUnavailableMessage.isBlank()) {
            throw new IllegalArgumentException("defaultUnavailableMessage must not be blank");
        }
        if (maxConcurrencyExceededMessageTemplate == null || maxConcurrencyExceededMessageTemplate.isBlank()) {
            throw new IllegalArgumentException("maxConcurrencyExceededMessageTemplate must not be blank");
        }
        String.format(maxConcurrencyExceededMessageTemplate, 1);
        MethodRuntime.defaultUnavailableMessage = defaultUnavailableMessage;
        MethodRuntime.maxConcurrencyExceededMessageTemplate = maxConcurrencyExceededMessageTemplate;
    }

    public static String defaultUnavailableMessage() {
        return defaultUnavailableMessage;
    }

    private static String defaultUnavailableMessage(MethodDescriptor descriptor) {
        if (!descriptor.unavailableMessage().isBlank()) {
            return descriptor.unavailableMessage();
        }
        return defaultUnavailableMessage;
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

    public void updatePolicy(MethodPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public void enable() {
        MethodPolicy current = policy;
        updatePolicy(MethodPolicy.enabled(current.maxConcurrency(), current.unavailableMessage()));
    }

    public void disable(String unavailableMessage) {
        MethodPolicy current = policy;
        String message = unavailableMessage == null || unavailableMessage.isBlank()
                ? defaultUnavailableMessage
                : unavailableMessage;
        updatePolicy(MethodPolicy.disabled(message, current.maxConcurrency()));
    }

    public void setUnavailableMessage(String unavailableMessage) {
        String message = unavailableMessage == null || unavailableMessage.isBlank()
                ? defaultUnavailableMessage
                : unavailableMessage;
        updatePolicy(policy.withUnavailableMessage(message));
    }

    public void setMaxConcurrency(int maxConcurrency) {
        updatePolicy(policy.withMaxConcurrency(maxConcurrency));
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
                peakInFlight.accumulateAndGet(currentInFlight + 1, Math::max);
                return true;
            }
        }
    }

    public void onSuccess() {
        successCount.increment();
    }

    public void onError(Throwable throwable) {
        errorCount.increment();
        if (throwable != null) {
            lastError.set(throwable.toString());
        }
    }

    public void release() {
        inFlight.decrementAndGet();
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
                peakInFlight.accumulateAndGet(currentInFlight + 1, Math::max);
                return true;
            }
        }
    }

    public MethodUnavailableException unavailableException() {
        MethodPolicy current = policy;
        if (!current.enabled()) {
            return new MethodUnavailableException(serviceId, methodId, current.unavailableMessage());
        }
        return new MethodUnavailableException(
                serviceId,
                methodId,
                String.format(maxConcurrencyExceededMessageTemplate, current.maxConcurrency())
        );
    }

    public MethodStatsSnapshot stats() {
        return new MethodStatsSnapshot(
                successCount.sum(),
                errorCount.sum(),
                rejectedCount.sum(),
                inFlight.get(),
                peakInFlight.get()
        );
    }

    public String lastError() {
        return lastError.get();
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
        inFlight.set(0);
        peakInFlight.set(0);
        successCount.reset();
        errorCount.reset();
        rejectedCount.reset();
        lastError.set(null);
    }
}
