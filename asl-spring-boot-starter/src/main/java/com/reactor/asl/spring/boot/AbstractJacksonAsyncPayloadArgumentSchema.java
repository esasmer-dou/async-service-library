package com.reactor.asl.spring.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactor.asl.core.AsyncPayloadCodecException;

public abstract class AbstractJacksonAsyncPayloadArgumentSchema<T> implements JacksonAsyncPayloadArgumentSchema {
    private final String typeId;
    private final Class<T> javaType;
    private final int currentVersion;

    protected AbstractJacksonAsyncPayloadArgumentSchema(String typeId, Class<T> javaType, int currentVersion) {
        if (typeId == null || typeId.isBlank()) {
            throw new IllegalArgumentException("typeId must not be blank");
        }
        if (currentVersion <= 0) {
            throw new IllegalArgumentException("currentVersion must be positive");
        }
        this.typeId = typeId;
        this.javaType = javaType;
        this.currentVersion = currentVersion;
    }

    @Override
    public final String typeId() {
        return typeId;
    }

    @Override
    public final int currentVersion() {
        return currentVersion;
    }

    @Override
    public final Class<?> javaType() {
        return javaType;
    }

    @Override
    public JsonNode encode(ObjectMapper objectMapper, Object value) {
        return objectMapper.valueToTree(javaType.cast(value));
    }

    @Override
    public JsonNode migrate(ObjectMapper objectMapper, int fromVersion, JsonNode payload) {
        if (fromVersion != currentVersion) {
            throw new AsyncPayloadCodecException(
                    "No migration path from version " + fromVersion + " to " + currentVersion + " for schema " + typeId
            );
        }
        return payload;
    }

    @Override
    public Object decode(ObjectMapper objectMapper, JsonNode payload) {
        try {
            return objectMapper.treeToValue(payload, javaType);
        } catch (Exception exception) {
            throw new AsyncPayloadCodecException(
                    "Failed to decode payload for schema " + typeId,
                    exception
            );
        }
    }
}
