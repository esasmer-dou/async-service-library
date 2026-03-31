package com.reactor.asl.consumer.sample;

import org.springframework.stereotype.Service;

@Service
public class LedgerServiceImpl extends AbstractSyntheticServiceSupport implements LedgerService {
    public LedgerServiceImpl() {
        super("ledger");
    }

    @Override
    public String postEntry(String entryId) {
        return register(entryId, "POSTED");
    }

    @Override
    public String reconcileEntry(String entryId) {
        return transition(entryId, "RECONCILED");
    }

    @Override
    public void publishLedgerEvent(String entryId) {
        publishPrimary(entryId);
    }

    @Override
    public String health() {
        return healthStatus();
    }
}
