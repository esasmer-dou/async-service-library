package com.reactor.asl.consumer.sample;

public record UpsertInventoryRequest(
        String sku,
        String title,
        int available
) {
}
