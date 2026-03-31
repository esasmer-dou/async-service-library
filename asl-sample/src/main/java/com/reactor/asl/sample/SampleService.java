package com.reactor.asl.sample;

import com.reactor.asl.annotations.Excluded;
import com.reactor.asl.annotations.GovernedMethod;
import com.reactor.asl.annotations.GovernedService;

@GovernedService
public interface SampleService {
    String echo(String value);

    @GovernedMethod(initialMaxConcurrency = 1)
    String slow(String value);

    @GovernedMethod(asyncCapable = true, initialConsumerThreads = 0, initialMaxConcurrency = 2)
    void publish(String value);

    @Excluded
    String health();
}
