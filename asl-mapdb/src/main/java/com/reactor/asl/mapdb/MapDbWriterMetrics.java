package com.reactor.asl.mapdb;

public record MapDbWriterMetrics(
        long acceptedCommands,
        long committedCommands,
        long failedCommands,
        long batches,
        long commits,
        int queueDepth,
        long lastCommitNanos,
        long maxCommitNanos,
        double averageCommitNanos
) {
}
