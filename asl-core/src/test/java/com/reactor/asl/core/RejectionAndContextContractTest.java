package com.reactor.asl.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RejectionAndContextContractTest {
    @Test
    void hotPathRejectionsCarryReasonAndNoStackTrace() {
        MethodRuntime runtime = new MethodRuntime("svc", new MethodDescriptor("m()", "m", true, 1, "", false, 0));
        assertTrue(runtime.tryAcquire());

        MethodUnavailableException limited = runtime.unavailableException();
        assertEquals(MethodUnavailableException.Reason.CONCURRENCY_LIMIT, limited.reason());
        assertEquals(0, limited.getStackTrace().length);
        assertEquals("Method reached max concurrency: 1", limited.getMessage());
        assertSame(limited.getMessage(), runtime.unavailableException().getMessage(), "message is cached");

        runtime.setMaxConcurrency(3);
        assertEquals("Method reached max concurrency: 3", runtime.unavailableException().getMessage());

        runtime.disable("maintenance");
        MethodUnavailableException disabled = runtime.unavailableException();
        assertEquals(MethodUnavailableException.Reason.DISABLED, disabled.reason());
        assertEquals("maintenance", disabled.getMessage());
        runtime.release();
    }

    @Test
    void publicConstructorKeepsStackTraceForDirectUse() {
        MethodUnavailableException direct = new MethodUnavailableException("svc", "m()", "stop");
        assertTrue(direct.getStackTrace().length > 0);
        assertEquals(MethodUnavailableException.Reason.DISABLED, direct.reason());
    }

    @Test
    void ofNullableTreatsMissingKeyAsNoIdempotency() {
        assertSame(AsyncInvocationContext.none(), AsyncInvocationContext.ofNullable(null));
        assertSame(AsyncInvocationContext.none(), AsyncInvocationContext.ofNullable("  "));
        assertEquals("k", AsyncInvocationContext.ofNullable("k").idempotencyKey());
        assertThrows(NullPointerException.class, () -> AsyncInvocationContext.idempotent(null));
    }

    @Test
    void defaultEngineRejectsKeyedAdmissionsInsteadOfDroppingTheKey() {
        List<Object[]> accepted = new ArrayList<>();
        AsyncExecutionEngine legacyEngine = new AsyncExecutionEngine() {
            @Override
            public void register(MethodRuntime runtime, AsyncMethodBinding binding) {
            }

            @Override
            public void enqueue(MethodRuntime runtime, Object[] arguments) {
                accepted.add(arguments);
            }

            @Override
            public boolean supports(String serviceId, String methodId) {
                return true;
            }

            @Override
            public MethodBufferSnapshot snapshot(String serviceId, String methodId, int limit) {
                return MethodBufferSnapshot.unavailable(serviceId, methodId);
            }

            @Override
            public int clear(String serviceId, String methodId) {
                return 0;
            }

            @Override
            public boolean delete(String serviceId, String methodId, String entryId) {
                return false;
            }

            @Override
            public boolean replay(String serviceId, String methodId, String entryId) {
                return false;
            }

            @Override
            public void applyRuntime(MethodRuntime runtime) {
            }
        };
        MethodRuntime runtime = new MethodRuntime("svc", new MethodDescriptor("p()", "p", true, 1, "", true, 1));

        legacyEngine.enqueue(runtime, new Object[]{"a"}, AsyncInvocationContext.none());
        assertEquals(1, accepted.size());
        assertThrows(UnsupportedOperationException.class,
                () -> legacyEngine.enqueue(runtime, new Object[]{"b"}, AsyncInvocationContext.idempotent("k")));
        assertEquals(1, accepted.size());
        assertNull(AsyncInvocationContext.none().idempotencyKey());
    }
}
