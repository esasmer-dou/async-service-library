package com.reactor.asl.consumer.sample;

public final class MailNotFoundException extends RuntimeException {
    public MailNotFoundException(String mailId) {
        super("Mail not found: " + mailId);
    }
}
