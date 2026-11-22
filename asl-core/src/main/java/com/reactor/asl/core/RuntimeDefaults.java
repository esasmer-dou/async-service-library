package com.reactor.asl.core;

public record RuntimeDefaults(
        String unavailableMessage,
        String maxConcurrencyExceededMessageTemplate,
        int maxLastErrorCharacters,
        RuntimeErrorDetailPolicy errorDetailPolicy
) {
    public static final int DEFAULT_MAX_LAST_ERROR_CHARACTERS = 2_048;

    public static final RuntimeDefaults STANDARD = new RuntimeDefaults(
            "Method is disabled",
            "Method reached max concurrency: %d",
            DEFAULT_MAX_LAST_ERROR_CHARACTERS,
            RuntimeErrorDetailPolicy.TYPE_ONLY
    );

    public RuntimeDefaults(String unavailableMessage, String maxConcurrencyExceededMessageTemplate) {
        this(
                unavailableMessage,
                maxConcurrencyExceededMessageTemplate,
                DEFAULT_MAX_LAST_ERROR_CHARACTERS,
                RuntimeErrorDetailPolicy.TYPE_ONLY
        );
    }

    public RuntimeDefaults {
        if (unavailableMessage == null || unavailableMessage.isBlank()) {
            throw new IllegalArgumentException("unavailableMessage must not be blank");
        }
        if (maxConcurrencyExceededMessageTemplate == null
                || maxConcurrencyExceededMessageTemplate.isBlank()) {
            throw new IllegalArgumentException("maxConcurrencyExceededMessageTemplate must not be blank");
        }
        if (maxLastErrorCharacters <= 0) {
            throw new IllegalArgumentException("maxLastErrorCharacters must be positive");
        }
        if (errorDetailPolicy == null) {
            throw new IllegalArgumentException("errorDetailPolicy must not be null");
        }
        String.format(maxConcurrencyExceededMessageTemplate, 1);
    }
}
