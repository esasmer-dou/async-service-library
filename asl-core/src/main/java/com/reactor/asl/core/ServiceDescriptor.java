package com.reactor.asl.core;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;

public record ServiceDescriptor(String serviceId, MethodDescriptor[] methods) {
    public ServiceDescriptor {
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(methods, "methods");
        if (serviceId.isBlank()) {
            throw new IllegalArgumentException("serviceId must not be blank");
        }
        methods = Arrays.copyOf(methods, methods.length);
        HashSet<String> methodIds = new HashSet<>(methods.length);
        for (MethodDescriptor method : methods) {
            Objects.requireNonNull(method, "methods must not contain null");
            if (!methodIds.add(method.methodId())) {
                throw new IllegalArgumentException("Duplicate methodId in service descriptor: " + method.methodId());
            }
        }
    }

    @Override
    public MethodDescriptor[] methods() {
        return Arrays.copyOf(methods, methods.length);
    }

    @Override
    public boolean equals(Object other) {
        return other == this || other instanceof ServiceDescriptor descriptor
                && serviceId.equals(descriptor.serviceId)
                && Arrays.equals(methods, descriptor.methods);
    }

    @Override
    public int hashCode() {
        return 31 * serviceId.hashCode() + Arrays.hashCode(methods);
    }
}
