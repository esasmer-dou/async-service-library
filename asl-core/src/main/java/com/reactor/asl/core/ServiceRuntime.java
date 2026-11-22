package com.reactor.asl.core;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ServiceRuntime {
    private final String serviceId;
    private final ServiceDescriptor descriptor;
    private final MethodRuntime[] methods;
    private final Map<String, MethodRuntime> methodsById;
    private final Map<String, MethodRuntime> methodsByName;

    ServiceRuntime(ServiceDescriptor descriptor, RuntimeDefaults defaults) {
        Objects.requireNonNull(descriptor, "descriptor");
        this.descriptor = descriptor;
        this.serviceId = descriptor.serviceId();
        MethodDescriptor[] methodDescriptors = descriptor.methods();
        this.methods = new MethodRuntime[methodDescriptors.length];
        Map<String, MethodRuntime> byId = new LinkedHashMap<>(methodDescriptors.length);
        Map<String, MethodRuntime> byName = new LinkedHashMap<>(methodDescriptors.length);
        HashSet<String> ambiguousNames = new HashSet<>();
        for (int i = 0; i < methodDescriptors.length; i++) {
            MethodRuntime runtime = new MethodRuntime(serviceId, methodDescriptors[i], defaults);
            methods[i] = runtime;
            byId.put(runtime.methodId(), runtime);
            if (ambiguousNames.contains(runtime.methodName())) {
                continue;
            }
            MethodRuntime previous = byName.putIfAbsent(runtime.methodName(), runtime);
            if (previous != null) {
                byName.remove(runtime.methodName());
                ambiguousNames.add(runtime.methodName());
            }
        }
        this.methodsById = Collections.unmodifiableMap(byId);
        this.methodsByName = Collections.unmodifiableMap(byName);
    }

    public String serviceId() {
        return serviceId;
    }

    public MethodRuntime method(int index) {
        return methods[index];
    }

    public MethodRuntime methodById(String methodId) {
        MethodRuntime runtime = methodsById.get(methodId);
        if (runtime == null) {
            throw new IllegalArgumentException("Unknown methodId: " + methodId);
        }
        return runtime;
    }

    public MethodRuntime methodByName(String methodName) {
        MethodRuntime runtime = methodsByName.get(methodName);
        if (runtime == null) {
            throw new IllegalArgumentException("Unknown or ambiguous methodName: " + methodName);
        }
        return runtime;
    }

    public Collection<MethodRuntime> methods() {
        return List.copyOf(methodsById.values());
    }

    public ServiceRuntimeSnapshot snapshot() {
        List<MethodRuntimeSnapshot> methodSnapshots = methodsById.values()
                .stream()
                .map(MethodRuntime::snapshot)
                .toList();
        return new ServiceRuntimeSnapshot(serviceId, methodSnapshots);
    }

    ServiceDescriptor descriptor() {
        return descriptor;
    }

    void updateDefaults(RuntimeDefaults defaults) {
        methodsById.values().forEach(method -> method.updateDefaults(defaults));
    }
}
