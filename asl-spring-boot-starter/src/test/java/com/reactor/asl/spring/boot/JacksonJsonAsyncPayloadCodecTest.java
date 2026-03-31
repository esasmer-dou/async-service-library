package com.reactor.asl.spring.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reactor.asl.core.AsyncPayloadCodecException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JacksonJsonAsyncPayloadCodecTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JacksonJsonAsyncPayloadCodec codec = new JacksonJsonAsyncPayloadCodec(objectMapper);

    @Test
    void encodesAndDecodesNonSerializablePayloads() {
        SamplePayload payload = new SamplePayload("evt-1", "hello");

        byte[] encoded = codec.encode("demo.service", "publish(com.acme.Payload)", new Object[]{payload, null});
        Object[] decoded = codec.decode("demo.service", "publish(com.acme.Payload)", encoded);

        assertEquals(2, decoded.length);
        assertEquals(payload, decoded[0]);
        assertNull(decoded[1]);
    }

    @Test
    void summarizesComplexPayloadsAsJson() {
        SamplePayload payload = new SamplePayload("evt-2", "world");

        String summary = codec.summarize("demo.service", "publish(com.acme.Payload)", new Object[]{payload});

        assertTrue(summary.contains("\"id\":\"evt-2\""));
        assertTrue(summary.contains("\"message\":\"world\""));
    }

    @Test
    void usesMethodSpecificSchemaAndMigrationHooks() {
        JacksonAsyncPayloadSchemaRegistry registry = MapBackedJacksonAsyncPayloadSchemaRegistry.builder()
                .register("demo.service", "publish(sample)", 0, new MigratingSamplePayloadSchema())
                .build();
        JacksonJsonAsyncPayloadCodec schemaCodec = new JacksonJsonAsyncPayloadCodec(
                objectMapper,
                Thread.currentThread().getContextClassLoader(),
                registry
        );

        byte[] encoded = schemaCodec.encode("demo.service", "publish(sample)", new Object[]{new MigratingSamplePayload("evt-3", "hello")});
        Object[] decoded = schemaCodec.decode("demo.service", "publish(sample)", encoded);

        assertEquals(1, decoded.length);
        assertEquals(new MigratingSamplePayload("evt-3", "hello"), decoded[0]);

        String oldEnvelope = """
                {
                  "codec":"jackson-json-v1",
                  "codecVersion":2,
                  "arguments":[
                    {
                      "schemaType":"sample-payload",
                      "schemaVersion":1,
                      "javaType":null,
                      "json":{"id":"evt-legacy","text":"legacy-message"}
                    }
                  ]
                }
                """;
        Object[] migrated = schemaCodec.decode("demo.service", "publish(sample)", oldEnvelope.getBytes(StandardCharsets.UTF_8));
        assertEquals(new MigratingSamplePayload("evt-legacy", "legacy-message"), migrated[0]);
    }

    @Test
    void decodesByStableSchemaTypeEvenWhenMethodIdChanges() {
        MigratingSamplePayloadSchema schema = new MigratingSamplePayloadSchema();
        JacksonAsyncPayloadSchemaRegistry oldRegistry = MapBackedJacksonAsyncPayloadSchemaRegistry.builder()
                .register("demo.service", "publish(sample)", 0, schema)
                .build();
        JacksonJsonAsyncPayloadCodec oldCodec = new JacksonJsonAsyncPayloadCodec(
                objectMapper,
                Thread.currentThread().getContextClassLoader(),
                oldRegistry
        );

        byte[] encoded = oldCodec.encode("demo.service", "publish(sample)", new Object[]{new MigratingSamplePayload("evt-4", "renamed")});

        JacksonAsyncPayloadSchemaRegistry renamedRegistry = MapBackedJacksonAsyncPayloadSchemaRegistry.builder()
                .registerType(schema)
                .bind("demo.service", "publishRenamed(sample)", 0, "sample-payload")
                .build();
        JacksonJsonAsyncPayloadCodec renamedCodec = new JacksonJsonAsyncPayloadCodec(
                objectMapper,
                Thread.currentThread().getContextClassLoader(),
                renamedRegistry
        );

        Object[] decoded = renamedCodec.decode("demo.service", "publishRenamed(sample)", encoded);
        assertEquals(1, decoded.length);
        assertEquals(new MigratingSamplePayload("evt-4", "renamed"), decoded[0]);
    }

    @Test
    void failsWhenSchemaEnvelopeHasNoRegisteredSchema() {
        JacksonAsyncPayloadSchemaRegistry registry = JacksonAsyncPayloadSchemaRegistry.empty();
        JacksonJsonAsyncPayloadCodec schemaCodec = new JacksonJsonAsyncPayloadCodec(
                objectMapper,
                Thread.currentThread().getContextClassLoader(),
                registry
        );

        String encoded = """
                {
                  "codec":"jackson-json-v1",
                  "codecVersion":2,
                  "arguments":[
                    {
                      "schemaType":"sample-payload",
                      "schemaVersion":2,
                      "javaType":null,
                      "json":{"id":"evt","message":"hello"}
                    }
                  ]
                }
                """;

        AsyncPayloadCodecException exception = assertThrows(
                AsyncPayloadCodecException.class,
                () -> schemaCodec.decode("demo.service", "publish(sample)", encoded.getBytes(StandardCharsets.UTF_8))
        );
        assertTrue(exception.getMessage().contains("No schema registered"));
        assertTrue(exception.getMessage().contains("schemaType"));
    }

    @Test
    void rejectsDuplicateStableSchemaTypeRegistrations() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> MapBackedJacksonAsyncPayloadSchemaRegistry.builder()
                        .registerType(new MigratingSamplePayloadSchema())
                        .registerType(new MigratingSamplePayloadSchema())
        );

        assertTrue(exception.getMessage().contains("Duplicate schema typeId"));
    }

    private record SamplePayload(String id, String message) {
    }

    private record MigratingSamplePayload(String id, String message) {
    }

    private static final class MigratingSamplePayloadSchema
            extends AbstractJacksonAsyncPayloadArgumentSchema<MigratingSamplePayload> {

        private MigratingSamplePayloadSchema() {
            super("sample-payload", MigratingSamplePayload.class, 2);
        }

        @Override
        public JsonNode migrate(ObjectMapper objectMapper, int fromVersion, JsonNode payload) {
            if (fromVersion == currentVersion()) {
                return payload;
            }
            if (fromVersion == 1 && payload instanceof ObjectNode objectNode) {
                JsonNode text = objectNode.remove("text");
                objectNode.set("message", text);
                return objectNode;
            }
            return super.migrate(objectMapper, fromVersion, payload);
        }
    }
}
