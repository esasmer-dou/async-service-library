package com.reactor.asl.core;

public interface AsyncPayloadCodec {
    String id();

    byte[] encode(String serviceId, String methodId, Object[] arguments);

    Object[] decode(String serviceId, String methodId, byte[] payload);

    String summarize(String serviceId, String methodId, Object[] arguments);

    default AsyncPayloadMetadata describe(String serviceId, String methodId, Object[] arguments, byte[] payload) {
        return inspectEncoded(serviceId, methodId, payload);
    }

    default AsyncPayloadMetadata inspectEncoded(String serviceId, String methodId, byte[] payload) {
        return AsyncPayloadMetadata.unknown();
    }
}
