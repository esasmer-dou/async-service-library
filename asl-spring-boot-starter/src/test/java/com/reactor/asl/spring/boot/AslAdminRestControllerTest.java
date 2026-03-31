package com.reactor.asl.spring.boot;

import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.MethodDescriptor;
import com.reactor.asl.core.ServiceDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AslAdminRestControllerTest {
    @Test
    void exposesAndMutatesRuntimeConfiguration() {
        GovernanceRegistry registry = new GovernanceRegistry();
        registry.register(new ServiceDescriptor(
                "svc",
                new MethodDescriptor[]{
                        new MethodDescriptor("m()", "m", true, 1, "", true, 0)
                }
        ));
        AslAdminProperties properties = new AslAdminProperties();
        AslAdminFacade facade = new AslAdminFacade(registry, properties, List.of());
        AslAdminRestController controller = new AslAdminRestController(facade);
        AslAdminRuntimeConfiguration.UiPatch patch = new AslAdminRuntimeConfiguration.UiPatch();
        patch.setHeroTitle("Reactor Runtime Console");

        assertEquals(1, controller.summary().serviceCount());
        assertEquals("ASL Control Plane", controller.config().ui().pageTitle());
        assertEquals("Reactor Runtime Console", controller.updateUi(patch).ui().heroTitle());
        assertEquals(
                17,
                controller.updateRuntime(new AslAdminRestController.RuntimeConfigRequest(
                        17, null, null, null, null, null, null, null, null, null, null
                )).bufferPreviewLimit()
        );
    }

    @Test
    void returnsNotFoundWhenDeleteOrReplayFails() {
        GovernanceRegistry registry = new GovernanceRegistry();
        registry.register(new ServiceDescriptor(
                "svc",
                new MethodDescriptor[]{
                        new MethodDescriptor("m()", "m", true, 1, "", true, 0)
                }
        ));
        AslAdminFacade facade = new AslAdminFacade(registry, new AslAdminProperties(), List.of());

        AslAdminRestController controller = new AslAdminRestController(facade);

        assertEquals(HttpStatus.NOT_FOUND, controller.deleteBufferEntry("svc", "m()", "1").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.replayBufferEntry("svc", "m()", "1").getStatusCode());
    }
}
