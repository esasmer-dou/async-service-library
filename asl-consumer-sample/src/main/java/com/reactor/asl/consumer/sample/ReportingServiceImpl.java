package com.reactor.asl.consumer.sample;

import org.springframework.stereotype.Service;

@Service
public class ReportingServiceImpl extends AbstractSyntheticServiceSupport implements ReportingService {
    public ReportingServiceImpl() {
        super("reporting");
    }

    @Override
    public String buildReport(String reportId) {
        return register(reportId, "BUILT");
    }

    @Override
    public String archiveReport(String reportId) {
        return transition(reportId, "ARCHIVED");
    }

    @Override
    public void publishReportReady(String reportId) {
        publishPrimary(reportId);
    }

    @Override
    public String health() {
        return healthStatus();
    }
}
