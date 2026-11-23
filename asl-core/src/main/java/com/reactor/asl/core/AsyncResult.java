package com.reactor.asl.core;

/**
 * Outcome of one ASYNC invocation of a method that returns {@code CompletableFuture<T>}.
 *
 * @param invocationId  id of the queued invocation (the queue entry id shown in the admin portal)
 * @param value         the value the method's future completed with; {@code null} on failure
 * @param failure       why the invocation failed for good (no retry left); {@code null} on success
 * @param callerWaiting whether a caller's future received this outcome. {@code false} when nobody was waiting any
 *                      more: the process restarted, the caller's wait timed out, or the call was a duplicate.
 */
public record AsyncResult(String serviceId, String methodId, String invocationId, Object value, Throwable failure,
                          boolean callerWaiting) {
    public boolean succeeded() {
        return failure == null;
    }
}
