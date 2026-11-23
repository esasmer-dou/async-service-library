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

    /**
     * Deletes up to {@code filter.max()} entries that match, never running ones, in one durable step.
     *
     * @return how many entries were deleted
     * @throws UnsupportedOperationException when the provider has no bulk support
     */
    default int deleteMatching(String serviceId, String methodId, QueueEntryFilter filter) {
        throw new UnsupportedOperationException("bulk delete is not supported by " + getClass().getSimpleName());
    }
}
