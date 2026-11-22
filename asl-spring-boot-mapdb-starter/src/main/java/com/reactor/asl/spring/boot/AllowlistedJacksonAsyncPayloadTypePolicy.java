package com.reactor.asl.spring.boot;

import com.reactor.asl.core.AsyncPayloadCodecException;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class AllowlistedJacksonAsyncPayloadTypePolicy implements JacksonAsyncPayloadTypePolicy {
    private static final Set<String> SAFE_TYPE_NAMES = Set.of(
            String.class.getName(),
            Boolean.class.getName(),
            Byte.class.getName(),
            Short.class.getName(),
            Integer.class.getName(),
            Long.class.getName(),
            Float.class.getName(),
            Double.class.getName(),
            Character.class.getName(),
            java.math.BigDecimal.class.getName(),
            java.math.BigInteger.class.getName(),
            java.util.UUID.class.getName()
    );
    private static final Set<String> SAFE_TYPE_PREFIXES = Set.of("java.time.");

    private final Set<String> allowedTypeNames;
    private final Set<String> allowedTypePrefixes;

    public AllowlistedJacksonAsyncPayloadTypePolicy() {
        this(Set.of(), Set.of());
    }

    public AllowlistedJacksonAsyncPayloadTypePolicy(
            Collection<String> allowedTypeNames,
            Collection<String> allowedTypePrefixes
    ) {
        this.allowedTypeNames = merged(SAFE_TYPE_NAMES, allowedTypeNames, "allowedTypeNames");
        this.allowedTypePrefixes = merged(SAFE_TYPE_PREFIXES, allowedTypePrefixes, "allowedTypePrefixes");
    }

    @Override
    public void verifyAllowed(String javaTypeName) {
        if (javaTypeName == null || javaTypeName.isBlank()) {
            throw new AsyncPayloadCodecException("Jackson async payload type must not be blank");
        }
        if (allowedTypeNames.contains(javaTypeName)) {
            return;
        }
        for (String prefix : allowedTypePrefixes) {
            if (javaTypeName.startsWith(prefix)) {
                return;
            }
        }
        throw new AsyncPayloadCodecException(
                "Jackson async payload type is not allowlisted and has no registered schema: " + javaTypeName
        );
    }

    private static Set<String> merged(Set<String> defaults, Collection<String> values, String name) {
        Objects.requireNonNull(values, name);
        Set<String> merged = new HashSet<>(defaults);
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not contain blank values");
            }
            merged.add(value);
        }
        return Set.copyOf(merged);
    }
}
