package com.reactor.asl.consumer.sample;

import com.reactor.asl.annotations.Excluded;
import com.reactor.asl.annotations.GovernedMethod;
import com.reactor.asl.annotations.GovernedService;

@GovernedService(id = "ledger.service")
public interface LedgerService {
    @GovernedMethod(initialMaxConcurrency = 4, unavailableMessage = "ledger post lane closed")
    String postEntry(String entryId);

    @GovernedMethod(initialMaxConcurrency = 2, unavailableMessage = "ledger reconcile lane closed")
    String reconcileEntry(String entryId);

    @GovernedMethod(asyncCapable = true, initialMaxConcurrency = 1, initialConsumerThreads = 0)
    void publishLedgerEvent(String entryId);

    @Excluded
    String health();
}
