package com.reactor.asl.core;

public final class Throwables {
    private Throwables() {
    }

    @SuppressWarnings("unchecked")
    public static <T, E extends Throwable> T sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }
}
