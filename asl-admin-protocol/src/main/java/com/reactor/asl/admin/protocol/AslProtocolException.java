package com.reactor.asl.admin.protocol;

/** Raised when a message is malformed, truncated, too large or uses an unknown version. */
public final class AslProtocolException extends IllegalArgumentException {
    public AslProtocolException(String message) {
        super(message);
    }
}
