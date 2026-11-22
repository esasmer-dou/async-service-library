package com.reactor.asl.benchmarks;

import com.reactor.asl.annotations.Excluded;
import com.reactor.asl.annotations.GovernedMethod;
import com.reactor.asl.annotations.GovernedService;

@GovernedService(id = "benchmark.service")
public interface BenchmarkService {
    @Excluded
    String excluded(String value);

    String unlimited(String value);

    @GovernedMethod(initialMaxConcurrency = 1_024)
    String bounded(String value);

    String failing(String value);
}
