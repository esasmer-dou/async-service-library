package com.reactor.asl.core;

@FunctionalInterface
public interface AsyncMethodBinding {
    void invoke(Object[] arguments) throws Throwable;
}
