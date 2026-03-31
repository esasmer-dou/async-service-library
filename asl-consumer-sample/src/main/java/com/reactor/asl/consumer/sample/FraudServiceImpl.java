package com.reactor.asl.consumer.sample;

import org.springframework.stereotype.Service;

@Service
public class FraudServiceImpl extends AbstractSyntheticServiceSupport implements FraudService {
    public FraudServiceImpl() {
        super("fraud");
    }

    @Override
    public String screenCase(String caseId) {
        return register(caseId, "SCREENED");
    }

    @Override
    public String freezeCase(String caseId) {
        return transition(caseId, "FROZEN");
    }

    @Override
    public void publishFraudAlert(String caseId) {
        publishPrimary(caseId);
    }

    @Override
    public String health() {
        return healthStatus();
    }
}
