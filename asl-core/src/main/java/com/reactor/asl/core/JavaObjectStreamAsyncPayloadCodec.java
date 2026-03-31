package com.reactor.asl.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

public final class JavaObjectStreamAsyncPayloadCodec implements AsyncPayloadCodec {
    public static final String CODEC_ID = "java-object-stream-v1";

    @Override
    public String id() {
        return CODEC_ID;
    }

    @Override
    public byte[] encode(String serviceId, String methodId, Object[] arguments) {
        Object[] safeArguments = arguments == null ? new Object[0] : arguments;
        try {
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream(256);
            try (ObjectOutputStream objectStream = new ObjectOutputStream(byteStream)) {
                objectStream.writeObject(safeArguments);
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
        try (ObjectInputStream objectStream = new ObjectInputStream(new ByteArrayInputStream(safePayload))) {
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
}
