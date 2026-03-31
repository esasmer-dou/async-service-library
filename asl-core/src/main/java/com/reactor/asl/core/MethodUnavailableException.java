package com.reactor.asl.core;

public final class MethodUnavailableException extends RuntimeException {
    private final String serviceId;
    private final String methodId;

    public MethodUnavailableException(String serviceId, String methodId, String message) {
        super(message);
        this.serviceId = serviceId;
        this.methodId = methodId;
    }

    public String serviceId() {
        return serviceId;
    }

    public String methodId() {
        return methodId;
    }
}
