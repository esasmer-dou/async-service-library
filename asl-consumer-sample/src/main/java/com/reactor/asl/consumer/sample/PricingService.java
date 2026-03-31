package com.reactor.asl.consumer.sample;

import com.reactor.asl.annotations.Excluded;
import com.reactor.asl.annotations.GovernedMethod;
import com.reactor.asl.annotations.GovernedService;

@GovernedService(id = "pricing.service")
public interface PricingService {
    @GovernedMethod(initialMaxConcurrency = 8, unavailableMessage = "pricing compute lane closed")
    String computePrice(String productId);

    @GovernedMethod(initialMaxConcurrency = 3, unavailableMessage = "pricing lock lane closed")
    String lockPrice(String productId);

    @Excluded
    String health();
}
