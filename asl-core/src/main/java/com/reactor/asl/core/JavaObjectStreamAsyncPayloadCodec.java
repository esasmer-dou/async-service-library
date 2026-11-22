package com.reactor.asl.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class JavaObjectStreamAsyncPayloadCodec implements AsyncPayloadCodec {
    public static final String CODEC_ID = "java-object-stream-v1";
    private static final Set<String> SAFE_TYPE_NAMES = Set.of(
            Object.class.getName(),
            String.class.getName(),
            Boolean.class.getName(),
            Byte.class.getName(),
            Short.class.getName(),
            Integer.class.getName(),
            Long.class.getName(),
            Float.class.getName(),
            Double.class.getName(),
            Character.class.getName(),
            Number.class.getName(),
            java.math.BigDecimal.class.getName(),
            java.math.BigInteger.class.getName(),
            java.util.UUID.class.getName()
    );
    private static final Set<String> SAFE_TYPE_PREFIXES = Set.of("java.time.");

    private final Set<String> allowedTypeNames;
    private final Set<String> allowedTypePrefixes;
    private final long maxDepth;
    private final long maxReferences;
    private final long maxArrayLength;
    private final long maxStreamBytes;

    public JavaObjectStreamAsyncPayloadCodec() {
        this(Set.of(), Set.of(), 32L, 10_000L, 100_000L, 1024L * 1024L);
    }

    public JavaObjectStreamAsyncPayloadCodec(
            Collection<String> allowedTypeNames,
            Collection<String> allowedTypePrefixes,
            long maxDepth,
            long maxReferences,
            long maxArrayLength,
            long maxStreamBytes
    ) {
        this.allowedTypeNames = mergedValues(SAFE_TYPE_NAMES, allowedTypeNames, "allowedTypeNames");
        this.allowedTypePrefixes = mergedValues(
                SAFE_TYPE_PREFIXES,
                allowedTypePrefixes,
                "allowedTypePrefixes"
        );
        this.maxDepth = positive(maxDepth, "maxDepth");
        this.maxReferences = positive(maxReferences, "maxReferences");
        this.maxArrayLength = positive(maxArrayLength, "maxArrayLength");
        this.maxStreamBytes = positive(maxStreamBytes, "maxStreamBytes");
    }

    @Override
    public String id() {
        return CODEC_ID;
    }

    @Override
    public byte[] encode(String serviceId, String methodId, Object[] arguments) {
        Object[] safeArguments = arguments == null ? new Object[0] : arguments;
        for (Object argument : safeArguments) {
            if (argument != null) {
                verifyAllowed(argument.getClass());
            }
        }
        try {
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream(256);
            try (ObjectOutputStream objectStream = new ObjectOutputStream(byteStream)) {
                objectStream.writeObject(safeArguments);
            }
            if (byteStream.size() > maxStreamBytes) {
                throw new AsyncPayloadCodecException(
                        "Encoded Java async payload exceeds " + maxStreamBytes + " bytes for "
                                + serviceId + "::" + methodId
                );
            }
            return byteStream.toByteArray();
        } catch (IOException exception) {
            throw new AsyncPayloadCodecException(
                    "Failed to encode async payload for " + serviceId + "::" + methodId,
                    exception
            );
        }
    }

    @Override
    public Object[] decode(String serviceId, String methodId, byte[] payload) {
        byte[] safePayload = payload == null ? new byte[0] : payload;
        if (safePayload.length > maxStreamBytes) {
            throw new AsyncPayloadCodecException(
                    "Stored Java async payload exceeds " + maxStreamBytes + " bytes for "
                            + serviceId + "::" + methodId
            );
        }
        try (ObjectInputStream objectStream = new ObjectInputStream(new ByteArrayInputStream(safePayload))) {
            objectStream.setObjectInputFilter(this::filter);
            Object decoded = objectStream.readObject();
            if (decoded instanceof Object[] arguments) {
                return arguments;
            }
            throw new AsyncPayloadCodecException(
                    "Decoded async payload is not an Object[] for " + serviceId + "::" + methodId
            );
        } catch (IOException | ClassNotFoundException exception) {
            throw new AsyncPayloadCodecException(
                    "Failed to decode async payload for " + serviceId + "::" + methodId,
                    exception
            );
        }
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
            builder.append(String.valueOf(arguments[i]));
        }
        return builder.toString();
    }

    @Override
    public AsyncPayloadMetadata describe(String serviceId, String methodId, Object[] arguments, byte[] payload) {
        if (arguments == null || arguments.length == 0) {
            return new AsyncPayloadMetadata("(no arguments)", null);
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < arguments.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            Object argument = arguments[i];
            builder.append(argument == null ? "null" : argument.getClass().getName());
        }
        return new AsyncPayloadMetadata(builder.toString(), null);
    }

    private ObjectInputFilter.Status filter(ObjectInputFilter.FilterInfo info) {
        if (info.depth() > maxDepth
                || info.references() > maxReferences
                || info.arrayLength() > maxArrayLength
                || info.streamBytes() > maxStreamBytes) {
            return ObjectInputFilter.Status.REJECTED;
        }
        Class<?> type = info.serialClass();
        if (type == null) {
            return ObjectInputFilter.Status.UNDECIDED;
        }
        while (type.isArray()) {
            type = type.getComponentType();
        }
        if (type.isPrimitive()) {
            return ObjectInputFilter.Status.ALLOWED;
        }
        if (Proxy.isProxyClass(type)) {
            return ObjectInputFilter.Status.REJECTED;
        }
        return isAllowed(type.getName())
                ? ObjectInputFilter.Status.ALLOWED
                : ObjectInputFilter.Status.REJECTED;
    }

    private void verifyAllowed(Class<?> type) {
        Class<?> candidate = type;
        while (candidate.isArray()) {
            candidate = candidate.getComponentType();
        }
        if (!candidate.isPrimitive() && !isAllowed(candidate.getName())) {
            throw new AsyncPayloadCodecException(
                    "Java async payload type is not allowlisted: " + candidate.getName()
            );
        }
    }

    private boolean isAllowed(String typeName) {
        if (allowedTypeNames.contains(typeName)) {
            return true;
        }
        for (String prefix : allowedTypePrefixes) {
            if (typeName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> mergedValues(
            Set<String> defaults,
            Collection<String> configured,
            String name
    ) {
        Objects.requireNonNull(configured, name);
        Set<String> merged = new HashSet<>(defaults);
        for (String value : configured) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not contain blank values");
            }
            merged.add(value);
        }
        return Set.copyOf(merged);
    }

    private static long positive(long value, String name) {
        if (value <= 0L) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
