package com.reactor.asl.core;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ServiceRuntime {
    private final String serviceId;
    private final MethodRuntime[] methods;
    private final Map<String, MethodRuntime> methodsById;
    private final Map<String, MethodRuntime> methodsByName;

    ServiceRuntime(ServiceDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        this.serviceId = descriptor.serviceId();
        MethodDescriptor[] methodDescriptors = descriptor.methods();
        this.methods = new MethodRuntime[methodDescriptors.length];
        this.methodsById = new LinkedHashMap<>(methodDescriptors.length);
        this.methodsByName = new LinkedHashMap<>(methodDescriptors.length);
        for (int i = 0; i < methodDescriptors.length; i++) {
            MethodRuntime runtime = new MethodRuntime(serviceId, methodDescriptors[i]);
            methods[i] = runtime;
            methodsById.put(runtime.methodId(), runtime);
            MethodRuntime previous = methodsByName.put(runtime.methodName(), runtime);
            if (previous != null) {
                methodsByName.remove(runtime.methodName());
            }
        }
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
        return methodsById.values();
    }

    public ServiceRuntimeSnapshot snapshot() {
        List<MethodRuntimeSnapshot> methodSnapshots = methodsById.values()
                .stream()
                .map(MethodRuntime::snapshot)
                .toList();
        return new ServiceRuntimeSnapshot(serviceId, methodSnapshots);
    }
}
