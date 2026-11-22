package com.reactor.asl.core;

import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GovernanceRegistryIntegrityTest {
    @Test
    void rejectsConflictingDescriptorsForTheSameServiceId() {
        GovernanceRegistry registry = new GovernanceRegistry();
        registry.register(descriptor("service", "first()"));

        assertThrows(
                IllegalStateException.class,
                () -> registry.register(descriptor("service", "second()"))
        );
    }

    @Test
    void exposesImmutableViewsAndKeepsOverloadedNamesAmbiguous() {
        GovernanceRegistry registry = new GovernanceRegistry();
        ServiceRuntime runtime = registry.register(new ServiceDescriptor(
                "service",
                new MethodDescriptor[]{
                        method("find()", "find"),
                        method("find(java.lang.String)", "find"),
                        method("find(long)", "find")
                }
        ));

        Collection<ServiceRuntime> services = registry.services();
        Collection<MethodRuntime> methods = runtime.methods();

        assertThrows(UnsupportedOperationException.class, services::clear);
        assertThrows(UnsupportedOperationException.class, methods::clear);
        assertThrows(IllegalArgumentException.class, () -> runtime.methodByName("find"));
    }

    @Test
    void runtimeDefaultsAreIsolatedPerRegistry() {
        GovernanceRegistry first = new GovernanceRegistry(new RuntimeDefaults("First disabled", "First cap: %d"));
        GovernanceRegistry second = new GovernanceRegistry(new RuntimeDefaults("Second disabled", "Second cap: %d"));

        MethodRuntime firstMethod = first.register(descriptor("first", "run()" )).methodById("run()");
        MethodRuntime secondMethod = second.register(descriptor("second", "run()" )).methodById("run()");

        firstMethod.disable(null);
        secondMethod.disable(null);
        assertEquals("First disabled", firstMethod.unavailableException().getMessage());
        assertEquals("Second disabled", secondMethod.unavailableException().getMessage());

        first.updateRuntimeDefaults(new RuntimeDefaults("First updated", "Updated cap: %d"));
        assertEquals("First updated", firstMethod.unavailableException().getMessage());
        assertEquals("Second disabled", secondMethod.unavailableException().getMessage());
    }

    private static ServiceDescriptor descriptor(String serviceId, String methodId) {
        return new ServiceDescriptor(serviceId, new MethodDescriptor[]{method(methodId, "run")});
    }

    private static MethodDescriptor method(String methodId, String methodName) {
        return new MethodDescriptor(methodId, methodName, true, 1, "", false, 0);
    }
}
