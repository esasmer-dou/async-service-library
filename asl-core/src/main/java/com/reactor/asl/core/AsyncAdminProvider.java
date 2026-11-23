package com.reactor.asl.core;

public interface AsyncAdminProvider extends BufferAdminProvider {
    boolean replay(String serviceId, String methodId, String entryId);

    void applyRuntime(MethodRuntime runtime);

    /**
     * Queues up to {@code filter.max()} failed entries that match again, in one durable step. Only
     * {@code FAILED} entries can be replayed; other states in the filter are ignored.
     *
     * @return how many entries were queued again
     * @throws UnsupportedOperationException when the provider has no bulk support
     */
    default int replayMatching(String serviceId, String methodId, QueueEntryFilter filter) {
        throw new UnsupportedOperationException("bulk replay is not supported by " + getClass().getSimpleName());
    }
}
