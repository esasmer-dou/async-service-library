package com.reactor.asl.consumer.sample;

import org.springframework.stereotype.Service;

@Service
public class BillingServiceImpl extends AbstractSyntheticServiceSupport implements BillingService {
    public BillingServiceImpl() {
        super("billing");
    }

    @Override
    public String openInvoice(String invoiceId) {
        return register(invoiceId, "OPEN");
    }

    @Override
    public String closeInvoice(String invoiceId) {
        return transition(invoiceId, "CLOSED");
    }

    @Override
    public void publishInvoiceEvent(String invoiceId) {
        publishPrimary(invoiceId);
    }

    @Override
    public String health() {
        return healthStatus();
    }
}
