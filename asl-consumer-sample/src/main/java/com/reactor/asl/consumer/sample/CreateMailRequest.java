package com.reactor.asl.consumer.sample;

public record CreateMailRequest(
        String recipient,
        String subject,
        String body
) {
}
