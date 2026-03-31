package com.reactor.asl.spring.boot;

import com.reactor.asl.core.AsyncExecutionEngine;
import com.reactor.asl.core.AsyncMethodBinding;
import com.reactor.asl.core.ExecutionMode;
import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.MethodBufferSnapshot;
import com.reactor.asl.core.MethodDescriptor;
import com.reactor.asl.core.MethodRuntime;
import com.reactor.asl.core.ServiceDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AslAdminPageControllerTest {
    private static final String SERVICE_ID = "svc";
    private static final String METHOD_ID = "m()";

    @Test
    void rendersDashboardAndDelegatesAllActions() {
        GovernanceRegistry registry = new GovernanceRegistry();
        registry.register(new ServiceDescriptor(
                SERVICE_ID,
                new MethodDescriptor[]{
                        new MethodDescriptor(METHOD_ID, "m", true, 1, "", true, 0)
                }
        ));
        registry.attachAsyncExecutionEngine(new NoOpAsyncExecutionEngine());
        AslAdminProperties properties = new AslAdminProperties();
        properties.setPath("/asl");
        properties.setApiPath("/asl/api");
        AslAdminFacade facade = new AslAdminFacade(registry, properties, List.of());

        AslAdminPageController controller = new AslAdminPageController(facade);
        ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("asl/dashboard", controller.dashboard(model));
        assertEquals(1, ((List<?>) model.get("services")).size());
        assertEquals("/asl", model.get("pagePath"));
        assertEquals("/asl/api", model.get("apiPath"));
        assertEquals("ASL Control Plane", ((AslAdminRuntimeConfiguration.UiSnapshot) model.get("ui")).pageTitle());
        assertEquals(50, ((AslAdminRuntimeConfiguration.ConfigSnapshot) model.get("config")).bufferPreviewLimit());
        assertEquals(1, ((AslAdminFacade.AdminDashboardSummary) model.get("summary")).serviceCount());

        assertEquals("redirect:/asl", controller.enable("svc", "m()"));
        assertTrue(registry.service(SERVICE_ID).methodById(METHOD_ID).snapshot().enabled());

        assertEquals("redirect:/asl", controller.disable("svc", "m()", "stop"));
        assertFalse(registry.service(SERVICE_ID).methodById(METHOD_ID).snapshot().enabled());
        assertEquals("stop", registry.service(SERVICE_ID).methodById(METHOD_ID).snapshot().unavailableMessage());

        assertEquals("redirect:/asl", controller.concurrency("svc", "m()", 4));
        assertEquals(4, registry.service(SERVICE_ID).methodById(METHOD_ID).snapshot().maxConcurrency());

        assertEquals("redirect:/asl", controller.mode("svc", "m()", ExecutionMode.ASYNC));
        assertEquals(ExecutionMode.ASYNC, registry.service(SERVICE_ID).methodById(METHOD_ID).snapshot().executionMode());

        assertEquals("redirect:/asl", controller.consumerThreads("svc", "m()", 2));
        assertEquals(2, registry.service(SERVICE_ID).methodById(METHOD_ID).snapshot().consumerThreads());

        assertEquals("redirect:/asl", controller.clearBuffer("svc", "m()"));
        assertEquals("redirect:/asl", controller.replayBuffer("svc", "m()", "1"));
        assertEquals("redirect:/asl", controller.deleteBuffer("svc", "m()", "1"));
    }

    private static final class NoOpAsyncExecutionEngine implements AsyncExecutionEngine {
        @Override
        public void register(MethodRuntime runtime, AsyncMethodBinding binding) {
        }

        @Override
        public void enqueue(MethodRuntime runtime, Object[] arguments) {
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
    }
}
