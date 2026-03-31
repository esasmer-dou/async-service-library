package com.reactor.asl.consumer.sample;

import com.reactor.asl.annotations.Excluded;
import com.reactor.asl.annotations.GovernedMethod;
import com.reactor.asl.annotations.GovernedService;

import java.util.List;

@GovernedService(id = "chaos.service")
public interface ChaosLabService {
    @GovernedMethod(asyncCapable = true, initialMaxConcurrency = 1, initialConsumerThreads = 0)
    void emit(String payload);

    @Excluded
    List<String> events();

    @Excluded
    void reset();
}
