package com.reactor.asl.consumer.sample;

import com.reactor.asl.annotations.Excluded;
import com.reactor.asl.annotations.GovernedMethod;
import com.reactor.asl.annotations.GovernedService;

@GovernedService(id = "payment.service")
public interface PaymentGatewayService {
    @GovernedMethod(initialMaxConcurrency = 5, unavailableMessage = "payment authorize lane closed")
    String authorize(String paymentId);

    @GovernedMethod(initialMaxConcurrency = 4, unavailableMessage = "payment capture lane closed")
    String capture(String paymentId);

    @GovernedMethod(asyncCapable = true, initialMaxConcurrency = 2, initialConsumerThreads = 1)
    void publishSettlement(String paymentId);

    @Excluded
    String health();
}
