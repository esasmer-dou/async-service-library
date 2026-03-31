package com.reactor.asl.core;

import java.util.List;

public record MethodBufferSnapshot(
        String serviceId,
        String methodId,
        boolean available,
        long pendingCount,
        long failedCount,
        long inProgressCount,
        List<MethodBufferEntryView> entries
) {
    public static MethodBufferSnapshot unavailable(String serviceId, String methodId) {
        return new MethodBufferSnapshot(serviceId, methodId, false, 0, 0, 0, List.of());
    }
}
