package com.reactor.asl.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceRuntimeTest {
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
        assertTrue(snapshot.lastError().contains("boom"));
        assertEquals(1, registry.services().size());
        assertEquals("billing.service", service.snapshot().serviceId());
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
