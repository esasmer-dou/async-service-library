package com.reactor.asl.core;

public class AsyncPayloadCodecException extends RuntimeException {
    public AsyncPayloadCodecException(String message) {
        super(message);
    }

    public AsyncPayloadCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
