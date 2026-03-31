package com.reactor.asl.consumer.sample;

import com.reactor.asl.annotations.Excluded;
import com.reactor.asl.annotations.GovernedMethod;
import com.reactor.asl.annotations.GovernedService;

@GovernedService(id = "reporting.service")
public interface ReportingService {
    @GovernedMethod(initialMaxConcurrency = 4, unavailableMessage = "report build lane closed")
    String buildReport(String reportId);

    @GovernedMethod(initialMaxConcurrency = 2, unavailableMessage = "report archive lane closed")
    String archiveReport(String reportId);

    @GovernedMethod(asyncCapable = true, initialMaxConcurrency = 1, initialConsumerThreads = 0)
    void publishReportReady(String reportId);

    @Excluded
    String health();
}
