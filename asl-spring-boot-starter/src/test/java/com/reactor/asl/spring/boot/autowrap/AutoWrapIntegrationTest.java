package com.reactor.asl.spring.boot.autowrap;

import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.MethodRuntime;
import com.reactor.asl.core.MethodUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = AutoWrapIntegrationTest.AutoWrapApplication.class)
class AutoWrapIntegrationTest {
    private static final String SERVICE_ID = "auto-wrap.service";
    private static final String METHOD_ID = "process(java.lang.String)";

    @Autowired
    private AutoWrappedSampleService service;

    @Autowired
    private AutoWrappedSampleServiceImpl delegate;

    @Autowired
    private GovernanceRegistry registry;

    @Autowired
    private ApplicationContext applicationContext;

    @BeforeEach
    void resetDelegate() {
        delegate.reset();
        registry.service(SERVICE_ID).methodById(METHOD_ID).enable();
    }

    @Test
    void injectsGeneratedWrapperAsPrimaryGovernedBean() {
        Map<String, AutoWrappedSampleService> beans = applicationContext.getBeansOfType(AutoWrappedSampleService.class);

        assertEquals(2, beans.size());
        assertTrue(service.getClass().getName().endsWith("AslGoverned"));
        assertEquals("payload|1", service.process("payload"));
        assertEquals(1, delegate.invocations());
    }

    @Test
    void appliesRegistryControlsToAutowiredService() {
        MethodRuntime runtime = registry.service(SERVICE_ID).methodById(METHOD_ID);
        runtime.disable("stop-from-registry");

        MethodUnavailableException exception = assertThrows(MethodUnavailableException.class, () -> service.process("blocked"));

        assertTrue(exception.getMessage().contains("stop-from-registry"));
        assertEquals(0, delegate.invocations());
    }

    @SpringBootApplication
    static class AutoWrapApplication {
        public static void main(String[] args) {
            SpringApplication.run(AutoWrapApplication.class, args);
        }
    }
}
