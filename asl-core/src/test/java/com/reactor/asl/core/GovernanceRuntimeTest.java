package com.reactor.asl.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceRuntimeTest {
    @Test
    void resettingObservationsDoesNotCorruptActiveGauge() {
        GovernanceRegistry registry = new GovernanceRegistry();
        MethodRuntime runtime = registry.register(new ServiceDescriptor(
                "reset.service",
                new MethodDescriptor[]{new MethodDescriptor("run()", "run", true, 1, "", false, 0)}
        )).methodById("run()");

        assertTrue(runtime.tryAcquire());
        runtime.onSuccess();
        runtime.onError(new IllegalStateException("hidden after reset"));
        runtime.onRejected();
        runtime.resetObservations();
        assertEquals(1, runtime.stats().inFlight());
        assertEquals(1, runtime.stats().peakInFlight());
        assertEquals(0, runtime.successCount());
        assertEquals(0, runtime.errorCount());
        assertEquals(0, runtime.rejectedCount());
        assertEquals(1, runtime.totalSuccessCount());
        assertEquals(1, runtime.totalErrorCount());
        assertEquals(1, runtime.totalRejectedCount());

        runtime.release();
        assertEquals(0, runtime.stats().inFlight());
    }

    @Test
    void tracksMethodLifecycleAndSnapshots() {
        GovernanceRegistry registry = new GovernanceRegistry();
        ServiceRuntime service = registry.register(new ServiceDescriptor(
                "billing.service",
                new MethodDescriptor[]{
                        new MethodDescriptor("charge(java.lang.String)", "charge", true, 2, "charge is disabled", false, 0)
                }
        ));

        MethodRuntime method = service.methodById("charge(java.lang.String)");
        assertTrue(method.tryAcquire());
        method.onSuccess();
        method.release();

        method.disable("manual-stop");
        assertFalse(method.tryAcquire());
        assertEquals("manual-stop", method.unavailableException().getMessage());

        method.enable();
        method.setMaxConcurrency(1);
        assertTrue(method.tryAcquire());
        assertFalse(method.tryAcquire());
        method.onError(new IllegalStateException("boom"));
        method.release();

        MethodRuntimeSnapshot snapshot = method.snapshot();
        assertTrue(snapshot.enabled());
        assertEquals(1, snapshot.maxConcurrency());
        assertEquals(1, snapshot.successCount());
        assertEquals(1, snapshot.errorCount());
        assertEquals(2, snapshot.rejectedCount());
        assertEquals(IllegalStateException.class.getName(), snapshot.lastError());
        assertEquals(1, registry.services().size());
        assertEquals("billing.service", service.snapshot().serviceId());
    }

    @Test
    void boundsOptInErrorMessagesWithoutBuildingAnUnboundedDetail() {
        int limit = 48;
        GovernanceRegistry registry = new GovernanceRegistry(new RuntimeDefaults(
                "disabled",
                "cap: %d",
                limit,
                RuntimeErrorDetailPolicy.TYPE_AND_MESSAGE
        ));
        MethodRuntime method = registry.register(new ServiceDescriptor(
                "bounded-errors.service",
                new MethodDescriptor[]{
                        new MethodDescriptor("run()", "run", true, 1, "", false, 0)
                }
        )).methodById("run()");

        method.onError(new IllegalStateException("a-sensitive-message-that-must-be-truncated"));

        assertEquals(limit, method.lastError().length());
        assertTrue(method.lastError().startsWith(IllegalStateException.class.getName() + ": "));
        assertFalse(method.lastError().contains("must-be-truncated"));
    }

    @Test
    void rejectsAmbiguousMethodNameLookup() {
        ServiceRuntime service = new GovernanceRegistry().register(new ServiceDescriptor(
                "report.service",
                new MethodDescriptor[]{
                        new MethodDescriptor("export(java.lang.String)", "export", true, 1, "", false, 0),
                        new MethodDescriptor("export(int)", "export", true, 1, "", false, 0)
                }
        ));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.methodByName("export")
        );

        assertTrue(exception.getMessage().contains("ambiguous"));
        assertEquals("export(java.lang.String)", service.method(0).methodId());
        assertEquals("export(int)", service.method(1).methodId());
    }
}
