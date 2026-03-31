package com.reactor.asl.core;

public record AsyncPayloadMetadata(
        String payloadType,
        String payloadVersion
) {
    public static AsyncPayloadMetadata unknown() {
        return new AsyncPayloadMetadata(null, null);
    }
}
