package com.reactor.asl.core;

public interface AsyncExecutionEngine extends AsyncAdminProvider {
    void register(MethodRuntime runtime, AsyncMethodBinding binding);

    void enqueue(MethodRuntime runtime, Object[] arguments);
}
