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
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AslRuntimeMethodOverridesApplierTest {
    private static final String SERVICE = "mail.service";
    private static final String SYNC = "send(java.lang.String)";
    private static final String ASYNC = "publish(java.lang.String)";

    @Test
    void bindsAndAppliesStartupOverridesWithoutAdmin() {
        AslRuntimeProperties properties = bind(Map.of(
                "asl.runtime.services[mail.service].methods[send(java.lang.String)].max-concurrency", "6",
                "asl.runtime.services[mail.service].methods[send(java.lang.String)].enabled", "false",
                "asl.runtime.services[mail.service].methods[send(java.lang.String)].unavailable-message", "Paused",
                "asl.runtime.services[mail.service].methods[publish(java.lang.String)].execution-mode", "ASYNC",
                "asl.runtime.services[mail.service].methods[publish(java.lang.String)].consumer-threads", "3"
        ));
        GovernanceRegistry registry = registry();
        List<MethodRuntime> applied = new ArrayList<>();
        registry.attachAsyncExecutionEngine(engine(applied));

        new AslRuntimeMethodOverridesApplier(registry, properties).afterSingletonsInstantiated();

        MethodRuntime send = registry.service(SERVICE).methodById(SYNC);
        assertEquals(6, send.policy().maxConcurrency());
        assertFalse(send.policy().enabled());
        assertEquals("Paused", send.unavailableException().getMessage());
        MethodRuntime publish = registry.service(SERVICE).methodById(ASYNC);
        assertEquals(ExecutionMode.ASYNC, publish.executionMode());
        assertEquals(3, publish.consumerThreads());
        assertTrue(applied.contains(publish), "engine must be told about the new lane settings");
    }

    @Test
    void failsFastForUnknownMethodsAndAsyncWithoutEngine() {
        GovernanceRegistry registry = registry();
        AslRuntimeProperties unknown = bind(Map.of(
                "asl.runtime.services[mail.service].methods[typo()].enabled", "false"));
        IllegalStateException unknownFailure = assertThrows(IllegalStateException.class,
                () -> new AslRuntimeMethodOverridesApplier(registry, unknown).afterSingletonsInstantiated());
        assertTrue(unknownFailure.getMessage().contains("typo()"));

        AslRuntimeProperties async = bind(Map.of(
                "asl.runtime.services[mail.service].methods[publish(java.lang.String)].execution-mode", "ASYNC"));
        IllegalStateException noEngine = assertThrows(IllegalStateException.class,
                () -> new AslRuntimeMethodOverridesApplier(registry, async).afterSingletonsInstantiated());
        assertTrue(noEngine.getMessage().contains("no async execution engine"));
    }

    @Test
    void yamlWithBracketedKeysIsApplied() {
        StandardEnvironment environment = yamlEnvironment("""
                asl:
                  runtime:
                    services:
                      "[mail.service]":
                        methods:
                          "[send(java.lang.String)]":
                            max-concurrency: 6
                """);
        GovernanceRegistry registry = registry();
        new AslRuntimeMethodOverridesApplier(registry, bind(environment), environment).afterSingletonsInstantiated();
        assertEquals(6, registry.service(SERVICE).methodById(SYNC).policy().maxConcurrency());
    }

    @Test
    void yamlWithoutBracketsFailsInsteadOfBeingIgnored() {
        for (String[] keys : new String[][]{
                {"mail.service", "[send(java.lang.String)]"},
                {"[mail.service]", "send(java.lang.String)"},
                {"mail.service", "send(java.lang.String)"}}) {
            StandardEnvironment environment = yamlEnvironment("""
                    asl:
                      runtime:
                        services:
                          "%s":
                            methods:
                              "%s":
                                max-concurrency: 6
                    """.formatted(keys[0], keys[1]));
            GovernanceRegistry registry = registry();
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> new AslRuntimeMethodOverridesApplier(registry, bind(environment), environment).afterSingletonsInstantiated(),
                    String.join(" / ", keys));
            assertTrue(failure.getMessage().contains("[mail.service]"), failure.getMessage());
            assertEquals(2, registry.service(SERVICE).methodById(SYNC).policy().maxConcurrency(), "nothing was applied");
        }
    }

    private static StandardEnvironment yamlEnvironment(String yaml) {
        StandardEnvironment environment = new StandardEnvironment();
        try {
            for (PropertySource<?> source : new YamlPropertySourceLoader()
                    .load("application.yml", new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)))) {
                environment.getPropertySources().addFirst(source);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return environment;
    }

    private static AslRuntimeProperties bind(StandardEnvironment environment) {
        return Binder.get(environment).bind("asl.runtime", AslRuntimeProperties.class).orElseGet(AslRuntimeProperties::new);
    }

    private static AslRuntimeProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bind("asl.runtime", AslRuntimeProperties.class)
                .orElseGet(AslRuntimeProperties::new);
    }

    private static GovernanceRegistry registry() {
        GovernanceRegistry registry = new GovernanceRegistry();
        registry.register(new ServiceDescriptor(SERVICE, new MethodDescriptor[]{
                new MethodDescriptor(SYNC, "send", true, 2, "", false, 0),
                new MethodDescriptor(ASYNC, "publish", true, 2, "", true, 1)
        }));
        return registry;
    }

    private static AsyncExecutionEngine engine(List<MethodRuntime> applied) {
        return new AsyncExecutionEngine() {
            @Override
            public void register(MethodRuntime runtime, AsyncMethodBinding binding) {
            }

            @Override
            public void enqueue(MethodRuntime runtime, Object[] arguments) {
            }

            @Override
            public boolean replay(String serviceId, String methodId, String entryId) {
                return false;
            }

            @Override
            public void applyRuntime(MethodRuntime runtime) {
                applied.add(runtime);
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
        };
    }
}
