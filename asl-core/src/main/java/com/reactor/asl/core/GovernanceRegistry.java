package com.reactor.asl.core;

import java.util.Collection;
import java.util.Map.Entry;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class GovernanceRegistry {
    private final Map<String, ServiceRuntime> services = new ConcurrentHashMap<>();
    private final Map<String, AsyncBindingRegistration> asyncBindings = new ConcurrentHashMap<>();

    private volatile AsyncExecutionEngine asyncExecutionEngine;

    public ServiceRuntime register(ServiceDescriptor descriptor) {
        return services.computeIfAbsent(descriptor.serviceId(), ignored -> new ServiceRuntime(descriptor));
    }

    public ServiceRuntime service(String serviceId) {
        ServiceRuntime runtime = services.get(serviceId);
        if (runtime == null) {
            throw new IllegalArgumentException("Unknown serviceId: " + serviceId);
        }
        return runtime;
    }

    public Collection<ServiceRuntime> services() {
        return services.values();
    }

    public void attachAsyncExecutionEngine(AsyncExecutionEngine asyncExecutionEngine) {
        this.asyncExecutionEngine = Objects.requireNonNull(asyncExecutionEngine, "asyncExecutionEngine");
        for (Entry<String, AsyncBindingRegistration> entry : asyncBindings.entrySet()) {
            asyncExecutionEngine.register(entry.getValue().runtime(), entry.getValue().binding());
        }
    }

    public boolean hasAsyncExecutionEngine() {
        return asyncExecutionEngine != null;
    }

    public void registerAsyncMethod(MethodRuntime runtime, AsyncMethodBinding binding) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(binding, "binding");
        String key = asyncKey(runtime.serviceId(), runtime.methodId());
        AsyncBindingRegistration registration = new AsyncBindingRegistration(runtime, binding);
        asyncBindings.put(key, registration);
        AsyncExecutionEngine current = asyncExecutionEngine;
        if (current != null) {
            current.register(runtime, binding);
        }
    }

    public void enqueueAsync(MethodRuntime runtime, Object[] arguments) {
        if (!runtime.tryAcceptAsyncInvocation()) {
            throw runtime.unavailableException();
        }
        AsyncExecutionEngine current = asyncExecutionEngine;
        if (current == null) {
            throw new AsyncExecutionUnavailableException("No async execution engine attached");
        }
        current.enqueue(runtime, arguments);
    }

    public AsyncExecutionEngine asyncExecutionEngine() {
        AsyncExecutionEngine current = asyncExecutionEngine;
        if (current == null) {
            throw new AsyncExecutionUnavailableException("No async execution engine attached");
        }
        return current;
    }

    private static String asyncKey(String serviceId, String methodId) {
        return serviceId + "::" + methodId;
    }

    private record AsyncBindingRegistration(MethodRuntime runtime, AsyncMethodBinding binding) {
    }
}
