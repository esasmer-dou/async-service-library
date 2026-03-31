package com.reactor.asl.spring.boot.autowrap;

import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AutoWrappedSampleServiceImpl implements AutoWrappedSampleService {
    private final AtomicInteger invocations = new AtomicInteger();

    @Override
    public String process(String value) {
        return value + "|" + invocations.incrementAndGet();
    }

    public int invocations() {
        return invocations.get();
    }

    public void reset() {
        invocations.set(0);
    }
}
