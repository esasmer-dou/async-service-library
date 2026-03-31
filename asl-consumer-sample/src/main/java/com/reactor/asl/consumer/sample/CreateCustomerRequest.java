package com.reactor.asl.consumer.sample;

public record CreateCustomerRequest(
        String email,
        String fullName
) {
}
