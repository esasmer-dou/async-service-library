package com.reactor.asl.spring.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactor.asl.core.AsyncPayloadCodec;
import com.reactor.asl.core.AsyncPayloadCodecException;
import com.reactor.asl.core.AsyncPayloadMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class JacksonJsonAsyncPayloadCodec implements AsyncPayloadCodec {
    public static final String CODEC_ID = "jackson-json-v1";
    private static final int ENVELOPE_VERSION = 2;
    private static final int SUMMARY_LIMIT = 160;

    private final ObjectMapper objectMapper;
    private final ClassLoader classLoader;
    private final JacksonAsyncPayloadSchemaRegistry schemaRegistry;

    public JacksonJsonAsyncPayloadCodec(ObjectMapper objectMapper) {
        this(objectMapper, Thread.currentThread().getContextClassLoader(), JacksonAsyncPayloadSchemaRegistry.empty());
    }

    public JacksonJsonAsyncPayloadCodec(ObjectMapper objectMapper, ClassLoader classLoader) {
        this(objectMapper, classLoader, JacksonAsyncPayloadSchemaRegistry.empty());
    }

    public JacksonJsonAsyncPayloadCodec(
            ObjectMapper objectMapper,
            ClassLoader classLoader,
            JacksonAsyncPayloadSchemaRegistry schemaRegistry
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.classLoader = classLoader == null ? JacksonJsonAsyncPayloadCodec.class.getClassLoader() : classLoader;
        this.schemaRegistry = Objects.requireNonNull(schemaRegistry, "schemaRegistry");
    }

    @Override
    public String id() {
        return CODEC_ID;
    }

    @Override
    public byte[] encode(String serviceId, String methodId, Object[] arguments) {
        Object[] safeArguments = arguments == null ? new Object[0] : arguments;
        List<ArgumentEnvelope> encodedArguments = new ArrayList<>(safeArguments.length);
        try {
            for (int i = 0; i < safeArguments.length; i++) {
                Object argument = safeArguments[i];
                Optional<JacksonAsyncPayloadArgumentSchema> schema = schemaRegistry.find(serviceId, methodId, i);
                if (argument == null) {
                    encodedArguments.add(new ArgumentEnvelope(
                            schema.map(JacksonAsyncPayloadArgumentSchema::typeId).orElse(null),
                            schema.map(JacksonAsyncPayloadArgumentSchema::currentVersion).orElse(null),
                            null,
                            null
                    ));
                } else if (schema.isPresent()) {
                    JacksonAsyncPayloadArgumentSchema argumentSchema = schema.get();
                    Class<?> expectedType = argumentSchema.javaType();
                    if (!expectedType.isInstance(argument)) {
                        throw new AsyncPayloadCodecException(
                                "Schema type mismatch for " + serviceId + "::" + methodId + " argument " + i
                                        + ": expected " + expectedType.getName()
                                        + " but got " + argument.getClass().getName()
                        );
                    }
                    encodedArguments.add(new ArgumentEnvelope(
                            argumentSchema.typeId(),
                            argumentSchema.currentVersion(),
                            null,
                            argumentSchema.encode(objectMapper, argument)
                    ));
                } else {
                    encodedArguments.add(new ArgumentEnvelope(
                            null,
                            null,
                            argument.getClass().getName(),
                            objectMapper.valueToTree(argument)
                    ));
                }
            }
            return objectMapper.writeValueAsBytes(new PayloadEnvelope(CODEC_ID, ENVELOPE_VERSION, encodedArguments));
        } catch (Exception exception) {
            throw new AsyncPayloadCodecException(
                    "Failed to encode Jackson async payload for " + serviceId + "::" + methodId,
                    exception
            );
        }
    }

    @Override
    public Object[] decode(String serviceId, String methodId, byte[] payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            Integer codecVersion = root.hasNonNull("codecVersion") ? root.get("codecVersion").asInt() : null;
            if (codecVersion == null) {
                return decodeLegacyEnvelope(serviceId, methodId, root);
            }
            PayloadEnvelope envelope = objectMapper.treeToValue(root, PayloadEnvelope.class);
            if (envelope.codec() == null || !CODEC_ID.equals(envelope.codec())) {
                throw new AsyncPayloadCodecException(
                        "Unsupported Jackson async payload codec for " + serviceId + "::" + methodId + ": " + envelope.codec()
                );
            }
            if (envelope.codecVersion() != ENVELOPE_VERSION) {
                throw new AsyncPayloadCodecException(
                        "Unsupported Jackson async payload envelope version for " + serviceId + "::" + methodId + ": " + envelope.codecVersion()
                );
            }
            List<ArgumentEnvelope> encodedArguments = envelope.arguments() == null ? List.of() : envelope.arguments();
            Object[] decoded = new Object[encodedArguments.size()];
            for (int i = 0; i < encodedArguments.size(); i++) {
                ArgumentEnvelope argument = encodedArguments.get(i);
                if (argument.schemaType() != null) {
                    decoded[i] = decodeSchemaArgument(serviceId, methodId, i, argument);
                    continue;
                }
                if (argument.javaType() == null) {
                    decoded[i] = null;
                    continue;
                }
                Class<?> argumentType = Class.forName(argument.javaType(), true, classLoader);
                decoded[i] = objectMapper.treeToValue(argument.json(), argumentType);
            }
            return decoded;
        } catch (AsyncPayloadCodecException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AsyncPayloadCodecException(
                    "Failed to decode Jackson async payload for " + serviceId + "::" + methodId,
                    exception
            );
        }
    }

    private Object[] decodeLegacyEnvelope(String serviceId, String methodId, JsonNode root) throws Exception {
        LegacyPayloadEnvelope envelope = objectMapper.treeToValue(root, LegacyPayloadEnvelope.class);
        if (envelope.version() == null || !CODEC_ID.equals(envelope.version())) {
            throw new AsyncPayloadCodecException(
                    "Unsupported legacy Jackson async payload version for " + serviceId + "::" + methodId + ": " + envelope.version()
            );
        }
        List<LegacyArgumentEnvelope> encodedArguments = envelope.arguments() == null ? List.of() : envelope.arguments();
        Object[] decoded = new Object[encodedArguments.size()];
        for (int i = 0; i < encodedArguments.size(); i++) {
            LegacyArgumentEnvelope argument = encodedArguments.get(i);
            if (argument.type() == null) {
                decoded[i] = null;
                continue;
            }
            Class<?> argumentType = Class.forName(argument.type(), true, classLoader);
            decoded[i] = objectMapper.treeToValue(argument.json(), argumentType);
        }
        return decoded;
    }

    private Object decodeSchemaArgument(String serviceId, String methodId, int argumentIndex, ArgumentEnvelope argument) {
        JacksonAsyncPayloadArgumentSchema schema = schemaRegistry.findByTypeId(argument.schemaType())
                .or(() -> schemaRegistry.find(serviceId, methodId, argumentIndex))
                .orElseThrow(() -> new AsyncPayloadCodecException(
                        "No schema registered for schemaType "
                                + argument.schemaType()
                                + " while decoding "
                                + serviceId
                                + "::"
                                + methodId
                                + " argument "
                                + argumentIndex
                ));
        if (!schema.typeId().equals(argument.schemaType())) {
            throw new AsyncPayloadCodecException(
                    "Schema type mismatch for " + serviceId + "::" + methodId + " argument " + argumentIndex
                            + ": expected " + schema.typeId()
                            + " but found " + argument.schemaType()
            );
        }
        if (argument.json() == null || argument.json().isNull()) {
            return null;
        }
        int storedVersion = argument.schemaVersion() == null ? schema.currentVersion() : argument.schemaVersion();
        JsonNode migrated = schema.migrate(objectMapper, storedVersion, cloneNode(argument.json()));
        return schema.decode(objectMapper, migrated);
    }

    private JsonNode cloneNode(JsonNode jsonNode) {
        if (jsonNode == null || jsonNode.isNull()) {
            return jsonNode;
        }
        if (jsonNode.isObject() || jsonNode.isArray()) {
            return jsonNode.deepCopy();
        }
        return objectMapper.valueToTree(jsonNode);
    }

    @Override
    public String summarize(String serviceId, String methodId, Object[] arguments) {
        if (arguments == null || arguments.length == 0) {
            return "(no arguments)";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < arguments.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(truncate(stringify(arguments[i])));
        }
        return builder.toString();
    }

    @Override
    public AsyncPayloadMetadata describe(String serviceId, String methodId, Object[] arguments, byte[] payload) {
        Object[] safeArguments = arguments == null ? new Object[0] : arguments;
        List<String> descriptors = new ArrayList<>(safeArguments.length);
        List<String> versions = new ArrayList<>(safeArguments.length);
        for (int i = 0; i < safeArguments.length; i++) {
            Object argument = safeArguments[i];
            Optional<JacksonAsyncPayloadArgumentSchema> schema = schemaRegistry.find(serviceId, methodId, i);
            if (schema.isPresent()) {
                descriptors.add(schema.get().typeId());
                versions.add("v" + schema.get().currentVersion());
            } else if (argument == null) {
                descriptors.add("null");
            } else {
                descriptors.add(argument.getClass().getName());
            }
        }
        return new AsyncPayloadMetadata(joinOrNull(descriptors), joinOrNull(versions));
    }

    @Override
    public AsyncPayloadMetadata inspectEncoded(String serviceId, String methodId, byte[] payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            Integer codecVersion = root.hasNonNull("codecVersion") ? root.get("codecVersion").asInt() : null;
            if (codecVersion == null) {
                LegacyPayloadEnvelope legacy = objectMapper.treeToValue(root, LegacyPayloadEnvelope.class);
                List<String> types = new ArrayList<>();
                if (legacy.arguments() != null) {
                    for (LegacyArgumentEnvelope argument : legacy.arguments()) {
                        if (argument.type() != null) {
                            types.add(argument.type());
                        }
                    }
                }
                return new AsyncPayloadMetadata(joinOrNull(types), "legacy");
            }

            PayloadEnvelope envelope = objectMapper.treeToValue(root, PayloadEnvelope.class);
            List<String> types = new ArrayList<>();
            List<String> versions = new ArrayList<>();
            if (envelope.arguments() != null) {
                for (ArgumentEnvelope argument : envelope.arguments()) {
                    if (argument.schemaType() != null) {
                        types.add(argument.schemaType());
                        if (argument.schemaVersion() != null) {
                            versions.add("v" + argument.schemaVersion());
                        }
                    } else if (argument.javaType() != null) {
                        types.add(argument.javaType());
                    }
                }
            }
            return new AsyncPayloadMetadata(joinOrNull(types), versions.isEmpty() ? "codec-v" + codecVersion : joinOrNull(versions));
        } catch (Exception exception) {
            return AsyncPayloadMetadata.unknown();
        }
    }

    private String stringify(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return String.valueOf(value);
        }
    }

    private static String truncate(String value) {
        if (value.length() <= SUMMARY_LIMIT) {
            return value;
        }
        return value.substring(0, SUMMARY_LIMIT - 3) + "...";
    }

    private static String joinOrNull(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream()
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .reduce((left, right) -> left + ", " + right)
                .orElse(null);
    }

    private record PayloadEnvelope(String codec, int codecVersion, List<ArgumentEnvelope> arguments) {
    }

    private record ArgumentEnvelope(String schemaType, Integer schemaVersion, String javaType, JsonNode json) {
    }

    private record LegacyPayloadEnvelope(String version, List<LegacyArgumentEnvelope> arguments) {
    }

    private record LegacyArgumentEnvelope(String type, JsonNode json) {
    }
}
