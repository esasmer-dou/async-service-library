package com.reactor.asl.core;

public interface BufferAdminProvider {
    boolean supports(String serviceId, String methodId);

    MethodBufferSnapshot snapshot(String serviceId, String methodId, int limit);

    int clear(String serviceId, String methodId);

    boolean delete(String serviceId, String methodId, String entryId);
}
