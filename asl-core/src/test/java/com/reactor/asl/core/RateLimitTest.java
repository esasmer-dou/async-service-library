package com.reactor.asl.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitTest {
    private static MethodRuntime runtime(int maxConcurrency) {
        return new GovernanceRegistry().register(new ServiceDescriptor(
                "rate.service",
                new MethodDescriptor[]{new MethodDescriptor("run()", "run", true, maxConcurrency, "", true, 0)}
        )).methodById("run()");
    }

    @Test
    void syncCallsAboveTheRateAreRejectedWithTheirOwnReason() {
        MethodRuntime runtime = runtime(100);
        runtime.setRateLimitPerSecond(5);

        int admitted = 0;
        for (int i = 0; i < 20; i++) {
            if (runtime.tryAcquire()) {
                admitted++;
                runtime.release();
            }
        }
        // One second of burst is allowed, then permits come at the configured rate.
        assertEquals(5, admitted);
        assertEquals(15, runtime.rejectedCount());
        MethodUnavailableException rejection = runtime.unavailableException();
        assertEquals(MethodUnavailableException.Reason.RATE_LIMIT, rejection.reason());
        assertTrue(rejection.getMessage().contains("5 calls per second"), rejection.getMessage());
    }

    @Test
    void aConcurrencyRejectionDoesNotBurnARatePermit() {
        MethodRuntime runtime = runtime(1);
        runtime.setRateLimitPerSecond(2);

        assertTrue(runtime.tryAcquire());
        assertFalse(runtime.tryAcquire());
        assertEquals(MethodUnavailableException.Reason.CONCURRENCY_LIMIT, runtime.unavailableException().reason());
        runtime.release();
        assertTrue(runtime.tryAcquire(), "the second permit is still there");
        runtime.release();
        assertFalse(runtime.tryAcquire());
        assertEquals(0, runtime.stats().inFlight());
    }

    @Test
    void zeroRemovesTheLimitAndOtherChangesKeepIt() {
        MethodRuntime runtime = runtime(100);
        runtime.setRateLimitPerSecond(1);
        runtime.setMaxConcurrency(50);
        runtime.disable("stopped");
        runtime.enable();
        assertEquals(1, runtime.policy().rateLimitPerSecond());
        assertTrue(runtime.tryAcquire());
        runtime.release();
        assertFalse(runtime.tryAcquire());

        runtime.setRateLimitPerSecond(0);
        for (int i = 0; i < 1000; i++) {
            assertTrue(runtime.tryAcquire());
            runtime.release();
        }
        assertEquals(0L, runtime.reserveAsyncRatePermitNanos());
        assertThrows(IllegalArgumentException.class, () -> runtime.setRateLimitPerSecond(-1));
    }

    @Test
    void asyncPermitsReturnTheWaitInsteadOfRejecting() {
        MethodRuntime runtime = runtime(100);
        runtime.setRateLimitPerSecond(4);
        for (int i = 0; i < 4; i++) {
            assertEquals(0L, runtime.reserveAsyncRatePermitNanos());
        }
        long wait = runtime.reserveAsyncRatePermitNanos();
        assertTrue(wait > 0L && wait <= 250_000_000L, "wait " + wait);
        assertEquals(0, runtime.rejectedCount(), "an async wait is not a rejection");
    }
}
