package com.reactor.asl.spring.boot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class MapBackedJacksonAsyncPayloadSchemaRegistry implements JacksonAsyncPayloadSchemaRegistry {
    private final Map<Key, JacksonAsyncPayloadArgumentSchema> bindings;
    private final Map<String, JacksonAsyncPayloadArgumentSchema> schemasByTypeId;

    private MapBackedJacksonAsyncPayloadSchemaRegistry(
            Map<Key, JacksonAsyncPayloadArgumentSchema> bindings,
            Map<String, JacksonAsyncPayloadArgumentSchema> schemasByTypeId
    ) {
        this.bindings = Map.copyOf(bindings);
        this.schemasByTypeId = Map.copyOf(schemasByTypeId);
    }

    @Override
    public Optional<JacksonAsyncPayloadArgumentSchema> find(String serviceId, String methodId, int argumentIndex) {
        return Optional.ofNullable(bindings.get(new Key(serviceId, methodId, argumentIndex)));
    }

    @Override
    public Optional<JacksonAsyncPayloadArgumentSchema> findByTypeId(String typeId) {
        return Optional.ofNullable(schemasByTypeId.get(typeId));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<Key, JacksonAsyncPayloadArgumentSchema> bindings = new LinkedHashMap<>();
        private final Map<String, JacksonAsyncPayloadArgumentSchema> schemasByTypeId = new LinkedHashMap<>();

        public Builder register(
                String serviceId,
                String methodId,
                int argumentIndex,
                JacksonAsyncPayloadArgumentSchema schema
        ) {
            if (serviceId == null || serviceId.isBlank()) {
                throw new IllegalArgumentException("serviceId must not be blank");
            }
            if (methodId == null || methodId.isBlank()) {
                throw new IllegalArgumentException("methodId must not be blank");
            }
            if (argumentIndex < 0) {
                throw new IllegalArgumentException("argumentIndex must be zero or positive");
            }
            JacksonAsyncPayloadArgumentSchema safeSchema = Objects.requireNonNull(schema, "schema");
            registerType(safeSchema);
            bindings.put(new Key(serviceId, methodId, argumentIndex), safeSchema);
            return this;
        }

        public Builder registerType(JacksonAsyncPayloadArgumentSchema schema) {
            JacksonAsyncPayloadArgumentSchema safeSchema = Objects.requireNonNull(schema, "schema");
            JacksonAsyncPayloadArgumentSchema existing = schemasByTypeId.putIfAbsent(safeSchema.typeId(), safeSchema);
            if (existing != null && existing != safeSchema) {
                throw new IllegalArgumentException(
                        "Duplicate schema typeId registration: " + safeSchema.typeId()
                );
            }
            return this;
        }

        public Builder bind(
                String serviceId,
                String methodId,
                int argumentIndex,
                String schemaTypeId
        ) {
            if (serviceId == null || serviceId.isBlank()) {
                throw new IllegalArgumentException("serviceId must not be blank");
            }
            if (methodId == null || methodId.isBlank()) {
                throw new IllegalArgumentException("methodId must not be blank");
            }
            if (argumentIndex < 0) {
                throw new IllegalArgumentException("argumentIndex must be zero or positive");
            }
            if (schemaTypeId == null || schemaTypeId.isBlank()) {
                throw new IllegalArgumentException("schemaTypeId must not be blank");
            }
            JacksonAsyncPayloadArgumentSchema schema = schemasByTypeId.get(schemaTypeId);
            if (schema == null) {
                throw new IllegalArgumentException("No schema registered for typeId: " + schemaTypeId);
            }
            bindings.put(new Key(serviceId, methodId, argumentIndex), schema);
            return this;
        }

        public MapBackedJacksonAsyncPayloadSchemaRegistry build() {
            return new MapBackedJacksonAsyncPayloadSchemaRegistry(bindings, schemasByTypeId);
        }
    }

    private record Key(String serviceId, String methodId, int argumentIndex) {
    }
}
