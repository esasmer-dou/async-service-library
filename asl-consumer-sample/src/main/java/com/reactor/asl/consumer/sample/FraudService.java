package com.reactor.asl.consumer.sample;

import com.reactor.asl.annotations.Excluded;
import com.reactor.asl.annotations.GovernedMethod;
import com.reactor.asl.annotations.GovernedService;

@GovernedService(id = "fraud.service")
public interface FraudService {
    @GovernedMethod(initialMaxConcurrency = 6, unavailableMessage = "fraud screening lane closed")
    String screenCase(String caseId);

    @GovernedMethod(initialMaxConcurrency = 2, unavailableMessage = "fraud hold lane closed")
    String freezeCase(String caseId);

    @GovernedMethod(asyncCapable = true, initialMaxConcurrency = 1, initialConsumerThreads = 0)
    void publishFraudAlert(String caseId);

    @Excluded
    String health();
}
