package com.reactor.asl.spring.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public interface JacksonAsyncPayloadArgumentSchema {
    String typeId();

    int currentVersion();

    Class<?> javaType();

    JsonNode encode(ObjectMapper objectMapper, Object value);

    JsonNode migrate(ObjectMapper objectMapper, int fromVersion, JsonNode payload);

    Object decode(ObjectMapper objectMapper, JsonNode payload);
}
