package com.reactor.asl.consumer.sample;

import java.time.Instant;

public record InventoryItemView(
        String sku,
        String title,
        int available,
        int reserved,
        Instant createdAt,
        Instant updatedAt
) {
}
