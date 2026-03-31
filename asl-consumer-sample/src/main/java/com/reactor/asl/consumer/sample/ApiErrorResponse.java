package com.reactor.asl.consumer.sample;

public record ApiErrorResponse(
        String code,
        String message
) {
}
