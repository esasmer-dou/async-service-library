package com.reactor.asl.spring.boot;

@FunctionalInterface
public interface JacksonAsyncPayloadTypePolicy {
    void verifyAllowed(String javaTypeName);
}
