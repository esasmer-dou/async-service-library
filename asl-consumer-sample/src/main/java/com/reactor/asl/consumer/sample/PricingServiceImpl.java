package com.reactor.asl.consumer.sample;

import org.springframework.stereotype.Service;

@Service
public class PricingServiceImpl extends AbstractSyntheticServiceSupport implements PricingService {
    public PricingServiceImpl() {
        super("pricing");
    }

    @Override
    public String computePrice(String productId) {
        return register(productId, "COMPUTED");
    }

    @Override
    public String lockPrice(String productId) {
        return transition(productId, "LOCKED");
    }

    @Override
    public String health() {
        return healthStatus();
    }
}
