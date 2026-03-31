package com.reactor.asl.consumer.sample;

import java.time.Instant;

public record MailMessageView(
        String id,
        String recipient,
        String subject,
        String body,
        MailMessageStatus status,
        Instant createdAt,
        Instant sentAt
) {
}
