package com.reactor.asl.spring.boot.autowrap;

import com.reactor.asl.annotations.GovernedMethod;
import com.reactor.asl.annotations.GovernedService;

@GovernedService(id = "auto-wrap.service")
public interface AutoWrappedSampleService {
    @GovernedMethod(unavailableMessage = "auto-wrap-disabled", initialMaxConcurrency = 1)
    String process(String value);
}
