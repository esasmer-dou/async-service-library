package com.reactor.asl.core;

/**
 * Receives the duration of synchronous governed calls. Installed per method by an observability
 * integration; when none is installed the generated wrapper does not read the clock.
 */
@FunctionalInterface
public interface MethodLatencyRecorder {
    void record(long durationNanos);
}
