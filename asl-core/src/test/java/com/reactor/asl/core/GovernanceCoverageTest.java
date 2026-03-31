package com.reactor.asl.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceCoverageTest {
    @Test
    void validatesDescriptorsPoliciesAndValueObjects() {
        assertThrows(IllegalArgumentException.class, () -> new MethodDescriptor("m", "m", true, 0, "", false, 0));
        assertThrows(IllegalArgumentException.class, () -> new MethodDescriptor("m", "m", true, 1, "", false, -1));
        assertThrows(IllegalArgumentException.class, () -> MethodPolicy.enabled(0));

        MethodPolicy unbounded = MethodPolicy.enabledUnbounded();
        assertTrue(unbounded.enabled());
        assertEquals(Integer.MAX_VALUE, unbounded.maxConcurrency());

        MethodPolicy disabled = MethodPolicy.disabled("down", 2);
        assertFalse(disabled.enabled());
        assertEquals("down", disabled.unavailableMessage());
        assertTrue(disabled.withEnabled(true).enabled());
        assertEquals(4, disabled.withMaxConcurrency(4).maxConcurrency());
        assertEquals("later", disabled.withUnavailableMessage("later").unavailableMessage());

        MethodUnavailableException unavailable = new MethodUnavailableException("svc", "m()", "stop");
        assertEquals("svc", unavailable.serviceId());
        assertEquals("m()", unavailable.methodId());
        assertEquals("stop", unavailable.getMessage());

        MethodBufferEntryView entry = new MethodBufferEntryView(
                "1",
                "FAILED",
                "payload",
                Instant.now(),
                2,
                "boom",
                "java.lang.IllegalStateException",
                "BUSINESS",
                "jackson-json-v1",
                "com.acme.Payload",
                "v2"
        );
        MethodBufferSnapshot snapshot = MethodBufferSnapshot.unavailable("svc", "m()");
        assertEquals("1", entry.entryId());
        assertEquals("BUSINESS", entry.errorCategory());
        assertFalse(snapshot.available());
        assertTrue(snapshot.entries().isEmpty());
    }

    @Test
    void coversMethodRuntimeEdgeCases() {
        MethodRuntime nonAsync = new MethodRuntime(
                "svc",
                new MethodDescriptor("sync()", "sync", true, 1, "", false, 0)
        );
        assertEquals("Method is disabled", nonAsync.policy().unavailableMessage());
        assertThrows(IllegalStateException.class, () -> nonAsync.switchMode(ExecutionMode.ASYNC));
        assertThrows(NullPointerException.class, () -> nonAsync.switchMode(null));
        assertThrows(IllegalArgumentException.class, () -> nonAsync.setConsumerThreads(-1));
        assertThrows(NullPointerException.class, () -> nonAsync.updatePolicy(null));

        nonAsync.disable("   ");
        assertFalse(nonAsync.policy().enabled());
        assertEquals("Method is disabled", nonAsync.unavailableException().getMessage());
        assertFalse(nonAsync.tryAcceptAsyncInvocation());
        assertFalse(nonAsync.tryBeginAsyncExecution());

        nonAsync.enable();
        assertTrue(nonAsync.tryAcquire());
        MethodUnavailableException concurrency = nonAsync.unavailableException();
        assertTrue(concurrency.getMessage().contains("max concurrency"));
        assertFalse(nonAsync.tryBeginAsyncExecution());
        nonAsync.release();

        MethodRuntime asyncRuntime = new MethodRuntime(
                "svc",
                new MethodDescriptor("async()", "async", false, 2, "queued-down", true, 3)
        );
        assertTrue(asyncRuntime.asyncCapable());
        assertEquals(3, asyncRuntime.consumerThreads());
        assertEquals(ExecutionMode.SYNC, asyncRuntime.executionMode());
        asyncRuntime.enable();
        asyncRuntime.switchMode(ExecutionMode.ASYNC);
        assertEquals(ExecutionMode.ASYNC, asyncRuntime.executionMode());
        asyncRuntime.setConsumerThreads(1);
        assertEquals(1, asyncRuntime.consumerThreads());
        assertTrue(asyncRuntime.tryAcceptAsyncInvocation());
        assertTrue(asyncRuntime.tryBeginAsyncExecution());
        asyncRuntime.onError(new IllegalArgumentException("bad"));
        asyncRuntime.release();
        assertTrue(asyncRuntime.lastError().contains("bad"));
        MethodRuntimeSnapshot runtimeSnapshot = asyncRuntime.snapshot();
        assertTrue(runtimeSnapshot.asyncCapable());
        assertEquals(ExecutionMode.ASYNC, runtimeSnapshot.executionMode());
        assertEquals(1, runtimeSnapshot.consumerThreads());
        assertEquals(1, runtimeSnapshot.errorCount());
    }

    @Test
    void coversServiceRuntimeAndRegistryBranches() {
        GovernanceRegistry registry = new GovernanceRegistry();
        ServiceRuntime service = registry.register(new ServiceDescriptor(
                "svc",
                new MethodDescriptor[]{
                        new MethodDescriptor("alpha()", "alpha", true, 1, "", false, 0),
                        new MethodDescriptor("beta(java.lang.String)", "beta", true, 2, "", true, 0)
                }
        ));

        assertSame(service, registry.service("svc"));
        assertEquals(2, service.methods().size());
        assertEquals("svc", service.snapshot().serviceId());
        assertThrows(IllegalArgumentException.class, () -> service.methodById("missing"));
        assertThrows(IllegalArgumentException.class, () -> service.methodByName("missing"));
        assertThrows(IllegalArgumentException.class, () -> registry.service("missing"));
        assertFalse(registry.hasAsyncExecutionEngine());
        assertThrows(AsyncExecutionUnavailableException.class, registry::asyncExecutionEngine);

        MethodRuntime asyncRuntime = service.methodById("beta(java.lang.String)");
        MethodRuntime syncRuntime = service.methodById("alpha()");
        AtomicInteger registrations = new AtomicInteger();
        AtomicInteger enqueues = new AtomicInteger();

        AsyncExecutionEngine engine = new AsyncExecutionEngine() {
            @Override
            public void register(MethodRuntime runtime, AsyncMethodBinding binding) {
                registrations.incrementAndGet();
            }

            @Override
            public void enqueue(MethodRuntime runtime, Object[] arguments) {
                enqueues.incrementAndGet();
            }

            @Override
            public void applyRuntime(MethodRuntime runtime) {
            }

            @Override
            public boolean supports(String serviceId, String methodId) {
                return false;
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
        };

        registry.registerAsyncMethod(asyncRuntime, arguments -> {
        });
        registry.attachAsyncExecutionEngine(engine);
        assertTrue(registry.hasAsyncExecutionEngine());
        assertSame(engine, registry.asyncExecutionEngine());
        assertEquals(1, registrations.get());

        registry.registerAsyncMethod(asyncRuntime, arguments -> {
        });
        assertEquals(2, registrations.get());

        asyncRuntime.disable("paused");
        MethodUnavailableException disabled = assertThrows(
                MethodUnavailableException.class,
                () -> registry.enqueueAsync(asyncRuntime, new Object[]{"x"})
        );
        assertTrue(disabled.getMessage().contains("paused"));

        asyncRuntime.enable();
        registry.enqueueAsync(asyncRuntime, new Object[]{"x"});
        assertEquals(1, enqueues.get());

        GovernanceRegistry noEngineRegistry = new GovernanceRegistry();
        ServiceRuntime noEngineService = noEngineRegistry.register(new ServiceDescriptor(
                "svc-2",
                new MethodDescriptor[]{new MethodDescriptor("async()", "async", true, 1, "", true, 0)}
        ));
        MethodRuntime noEngineRuntime = noEngineService.methodById("async()");
        AsyncExecutionUnavailableException unavailable = assertThrows(
                AsyncExecutionUnavailableException.class,
                () -> noEngineRegistry.enqueueAsync(noEngineRuntime, new Object[0])
        );
        assertTrue(unavailable.getMessage().contains("No async execution engine"));

        Throwable thrown = assertThrows(IllegalStateException.class, () -> Throwables.sneakyThrow(new IllegalStateException("boom")));
        assertEquals("boom", thrown.getMessage());
        assertNotNull(List.copyOf(service.methods()));
        assertFalse(syncRuntime.asyncCapable());
    }
}
