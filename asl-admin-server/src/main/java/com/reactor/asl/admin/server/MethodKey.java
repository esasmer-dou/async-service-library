package com.reactor.asl.admin.server;

import java.util.Objects;

/** Identity of a governed method inside one application. */
public record MethodKey(String serviceId, String methodId) {
    public MethodKey {
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(methodId, "methodId");
    }
}
