package com.reactor.asl.core;

import java.util.Collection;
import java.util.List;
import java.util.Map.Entry;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class GovernanceRegistry {
    private final Map<String, ServiceRuntime> services = new ConcurrentHashMap<>();
    private final Map<String, AsyncBindingRegistration> asyncBindings = new ConcurrentHashMap<>();

    private volatile AsyncExecutionEngine asyncExecutionEngine;
    private volatile RuntimeDefaults runtimeDefaults;

    public GovernanceRegistry() {
        this(RuntimeDefaults.STANDARD);
    }

    public GovernanceRegistry(RuntimeDefaults runtimeDefaults) {
        this.runtimeDefaults = Objects.requireNonNull(runtimeDefaults, "runtimeDefaults");
    }

    public synchronized ServiceRuntime register(ServiceDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        return services.compute(descriptor.serviceId(), (serviceId, existing) -> {
            if (existing == null) {
                return new ServiceRuntime(descriptor, runtimeDefaults);
            }
            if (!existing.descriptor().equals(descriptor)) {
                throw new IllegalStateException(
                        "Conflicting service descriptor registration for serviceId: " + serviceId
                );
            }
            return existing;
        });
    }

    public ServiceRuntime service(String serviceId) {
        ServiceRuntime runtime = services.get(serviceId);
        if (runtime == null) {
            throw new IllegalArgumentException("Unknown serviceId: " + serviceId);
        }
        return runtime;
    }

    public Collection<ServiceRuntime> services() {
        return List.copyOf(services.values());
    }

    public synchronized void attachAsyncExecutionEngine(AsyncExecutionEngine asyncExecutionEngine) {
        AsyncExecutionEngine requested = Objects.requireNonNull(asyncExecutionEngine, "asyncExecutionEngine");
        if (this.asyncExecutionEngine != null && this.asyncExecutionEngine != requested) {
            throw new IllegalStateException("An async execution engine is already attached");
        }
        this.asyncExecutionEngine = requested;
        for (Entry<String, AsyncBindingRegistration> entry : asyncBindings.entrySet()) {
            requested.register(entry.getValue().runtime(), entry.getValue().binding());
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
        AsyncBindingRegistration existing = asyncBindings.putIfAbsent(key, registration);
        if (existing != null && (existing.runtime() != runtime || existing.binding() != binding)) {
            throw new IllegalStateException("Async method binding already registered: " + key);
        }
        if (existing != null) {
            return;
        }
        AsyncExecutionEngine current = asyncExecutionEngine;
        if (current != null) {
            current.register(runtime, binding);
        }
    }

    public void enqueueAsync(MethodRuntime runtime, Object[] arguments) {
        enqueueAsync(runtime, arguments, AsyncInvocationContext.none());
    }

    public void enqueueAsync(MethodRuntime runtime, Object[] arguments, AsyncInvocationContext context) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(context, "context");
        if (!runtime.tryAcceptAsyncInvocation()) {
            throw runtime.unavailableException();
        }
        AsyncExecutionEngine current = asyncExecutionEngine;
        if (current == null) {
            runtime.onRejected();
            throw new AsyncExecutionUnavailableException("No async execution engine attached");
        }
        try {
            current.enqueue(runtime, arguments, context);
        } catch (RuntimeException exception) {
            runtime.onRejected();
            throw exception;
        }
    }

    public AsyncExecutionEngine asyncExecutionEngine() {
        AsyncExecutionEngine current = asyncExecutionEngine;
        if (current == null) {
            throw new AsyncExecutionUnavailableException("No async execution engine attached");
        }
        return current;
    }

    public RuntimeDefaults runtimeDefaults() {
        return runtimeDefaults;
    }

    public synchronized void updateRuntimeDefaults(RuntimeDefaults updatedDefaults) {
        runtimeDefaults = Objects.requireNonNull(updatedDefaults, "updatedDefaults");
        services.values().forEach(service -> service.updateDefaults(updatedDefaults));
    }

    private static String asyncKey(String serviceId, String methodId) {
        if (serviceId.indexOf('\0') >= 0 || methodId.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("serviceId and methodId must not contain NUL");
        }
        return serviceId + '\0' + methodId;
    }

    private record AsyncBindingRegistration(MethodRuntime runtime, AsyncMethodBinding binding) {
    }
}
