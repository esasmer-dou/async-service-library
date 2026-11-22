package com.reactor.asl.spring.boot.verify;

public class ProbeImpl implements Probe {
    @Override
    public String run() {
        return "raw";
    }
}
