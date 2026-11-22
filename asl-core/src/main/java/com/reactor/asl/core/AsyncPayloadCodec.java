package com.reactor.asl.core;

public interface AsyncPayloadCodec {
    String id();

    byte[] encode(String serviceId, String methodId, Object[] arguments);

    Object[] decode(String serviceId, String methodId, byte[] payload);

    /**
     * Decodes with the declared parameter types of the target method. Codecs that lose generic type
     * information (for example JSON) should override this; the default ignores the hints.
     *
     * @param parameterTypes declared parameter types, or {@code null} when unknown
     */
    default Object[] decode(
            String serviceId,
            String methodId,
            byte[] payload,
            java.lang.reflect.Type[] parameterTypes
    ) {
        return decode(serviceId, methodId, payload);
    }

    String summarize(String serviceId, String methodId, Object[] arguments);

    default AsyncPayloadMetadata describe(String serviceId, String methodId, Object[] arguments, byte[] payload) {
        return inspectEncoded(serviceId, methodId, payload);
    }

    default AsyncPayloadMetadata inspectEncoded(String serviceId, String methodId, byte[] payload) {
        return AsyncPayloadMetadata.unknown();
    }
}
