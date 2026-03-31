package com.reactor.asl.sample;

public final class SampleServiceImpl implements SampleService {
    @Override
    public String echo(String value) {
        return "echo:" + value;
    }

    @Override
    public String slow(String value) {
        return "slow:" + value;
    }

    @Override
    public void publish(String value) {
    }

    @Override
    public String health() {
        return "UP";
    }
}
