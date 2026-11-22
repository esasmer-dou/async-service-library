package com.reactor.asl.spring.boot.verify;

/** Stands in for the generated wrapper of {@link Probe}. */
public final class ProbeAslGoverned implements Probe {
    private final Probe delegate;

    public ProbeAslGoverned(Probe delegate) {
        this.delegate = delegate;
    }

    @Override
    public String run() {
        return delegate.run();
    }
}
