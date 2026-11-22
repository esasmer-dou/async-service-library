package com.reactor.asl.spring.boot;

import java.util.Optional;

public interface JacksonAsyncPayloadSchemaRegistry {
    Optional<JacksonAsyncPayloadArgumentSchema> find(String serviceId, String methodId, int argumentIndex);

    default Optional<JacksonAsyncPayloadArgumentSchema> findByTypeId(String typeId) {
        return Optional.empty();
    }

    static JacksonAsyncPayloadSchemaRegistry empty() {
        return new JacksonAsyncPayloadSchemaRegistry() {
            @Override
            public Optional<JacksonAsyncPayloadArgumentSchema> find(String serviceId, String methodId, int argumentIndex) {
                return Optional.empty();
            }
        };
    }
}
