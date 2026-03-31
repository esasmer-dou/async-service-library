package com.reactor.asl.consumer.sample;

import java.time.Instant;

public record CustomerView(
        String id,
        String email,
        String fullName,
        boolean active,
        Instant createdAt,
        Instant activatedAt
) {
}
