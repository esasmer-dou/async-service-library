package com.reactor.asl.core;

public interface AsyncAdminProvider extends BufferAdminProvider {
    boolean replay(String serviceId, String methodId, String entryId);

    void applyRuntime(MethodRuntime runtime);
}
