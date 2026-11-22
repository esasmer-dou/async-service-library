package com.reactor.asl.core;

public interface BufferAdminProvider {
    /**
     * Monotonic version of the provider's admin projection.
     *
     * <p>Providers that do not expose a version may keep the default. Versioned providers allow
     * control-plane clients to use conditional requests without scanning payload records.</p>
     */
    default long projectionVersion() {
        return -1L;
    }

    boolean supports(String serviceId, String methodId);

    MethodBufferSnapshot snapshot(String serviceId, String methodId, int limit);

    int clear(String serviceId, String methodId);

    boolean delete(String serviceId, String methodId, String entryId);
}
