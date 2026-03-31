package com.reactor.asl.consumer.sample;

import com.reactor.asl.annotations.Excluded;
import com.reactor.asl.annotations.GovernedMethod;
import com.reactor.asl.annotations.GovernedService;

@GovernedService(id = "billing.service")
public interface BillingService {
    @GovernedMethod(initialMaxConcurrency = 6, unavailableMessage = "billing intake lane closed")
    String openInvoice(String invoiceId);

    @GovernedMethod(initialMaxConcurrency = 3, unavailableMessage = "billing close lane closed")
    String closeInvoice(String invoiceId);

    @GovernedMethod(asyncCapable = true, initialMaxConcurrency = 2, initialConsumerThreads = 0)
    void publishInvoiceEvent(String invoiceId);

    @Excluded
    String health();
}
