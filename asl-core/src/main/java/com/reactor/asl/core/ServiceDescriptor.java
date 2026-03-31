package com.reactor.asl.core;

import java.util.Arrays;
import java.util.Objects;

public record ServiceDescriptor(String serviceId, MethodDescriptor[] methods) {
    public ServiceDescriptor {
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(methods, "methods");
        methods = Arrays.copyOf(methods, methods.length);
    }

    @Override
    public MethodDescriptor[] methods() {
        return Arrays.copyOf(methods, methods.length);
    }
}
