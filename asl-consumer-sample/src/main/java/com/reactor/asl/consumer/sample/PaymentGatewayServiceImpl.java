package com.reactor.asl.consumer.sample;

import org.springframework.stereotype.Service;

@Service
public class PaymentGatewayServiceImpl extends AbstractSyntheticServiceSupport implements PaymentGatewayService {
    public PaymentGatewayServiceImpl() {
        super("payment");
    }

    @Override
    public String authorize(String paymentId) {
        return register(paymentId, "AUTHORIZED");
    }

    @Override
    public String capture(String paymentId) {
        return transition(paymentId, "CAPTURED");
    }

    @Override
    public void publishSettlement(String paymentId) {
        publishPrimary(paymentId);
    }

    @Override
    public String health() {
        return healthStatus();
    }
}
