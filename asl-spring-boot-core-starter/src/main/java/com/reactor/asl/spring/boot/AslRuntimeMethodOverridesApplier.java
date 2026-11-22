package com.reactor.asl.spring.boot;

import com.reactor.asl.core.ExecutionMode;
import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.MethodRuntime;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Applies {@code asl.runtime.services.*} startup overrides after every singleton (wrappers and the
 * async engine) exists. Works without the admin starter, so a headless worker can start a lane in
 * ASYNC mode purely from configuration. If the admin starter also configures the same method, the
 * admin values are applied afterwards and win.
 */
final class AslRuntimeMethodOverridesApplier implements SmartInitializingSingleton {
    static final String PREFIX = "asl.runtime.services";
    static final String BRACKET_HINT = AslServiceKeys.hint(PREFIX, "max-concurrency");

    private final GovernanceRegistry registry;
    private final AslRuntimeProperties properties;
    private final Environment environment;

    AslRuntimeMethodOverridesApplier(GovernanceRegistry registry, AslRuntimeProperties properties) {
        this(registry, properties, null);
    }

    /** With an environment it also fails on keys written without brackets, which Spring Boot drops while binding. */
    AslRuntimeMethodOverridesApplier(GovernanceRegistry registry, AslRuntimeProperties properties, Environment environment) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Map<String, Set<String>> bound = new LinkedHashMap<>();
        properties.getServices().forEach((service, overrides) -> bound.put(service, overrides.getMethods().keySet()));
        AslServiceKeys.requireBound(environment, PREFIX, bound, "max-concurrency");
        for (Map.Entry<String, AslRuntimeProperties.ServiceOverrides> service : properties.getServices().entrySet()) {
            String serviceId = service.getKey();
            try {
                registry.service(serviceId);
            } catch (IllegalArgumentException unknown) {
                throw new IllegalStateException(
                        "asl.runtime.services configures unknown governed service '" + serviceId + "'." + BRACKET_HINT, unknown);
            }
            if (service.getValue().getMethods().isEmpty()) {
                throw new IllegalStateException(
                        "asl.runtime.services['" + serviceId + "'] configures no method." + BRACKET_HINT);
            }
            for (Map.Entry<String, AslRuntimeProperties.MethodOverrides> method
                    : service.getValue().getMethods().entrySet()) {
                apply(serviceId, method.getKey(), method.getValue());
            }
        }
    }

    void apply(String serviceId, String methodId, AslRuntimeProperties.MethodOverrides overrides) {
        MethodRuntime runtime;
        try {
            runtime = registry.service(serviceId).methodById(methodId);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalStateException(
                    "asl.runtime.services configures unknown governed method " + serviceId + "::" + methodId
                            + ". Use the @GovernedService id and the method id/signature." + BRACKET_HINT,
                    unknown
            );
        }
        if (overrides.getMaxConcurrency() != null) {
            runtime.setMaxConcurrency(overrides.getMaxConcurrency());
        }
        if (overrides.getConsumerThreads() != null) {
            runtime.setConsumerThreads(overrides.getConsumerThreads());
        }
        if (overrides.getUnavailableMessage() != null) {
            runtime.setUnavailableMessage(overrides.getUnavailableMessage());
        }
        if (overrides.getEnabled() != null) {
            if (overrides.getEnabled()) {
                runtime.enable();
            } else {
                runtime.disable(overrides.getUnavailableMessage());
            }
        }
        if (overrides.getExecutionMode() != null) {
            if (overrides.getExecutionMode() == ExecutionMode.ASYNC && !registry.hasAsyncExecutionEngine()) {
                throw new IllegalStateException(
                        "asl.runtime.services sets " + serviceId + "::" + methodId
                                + " to ASYNC but no async execution engine is attached; enable asl.async.mapdb.enabled"
                );
            }
            runtime.switchMode(overrides.getExecutionMode());
        }
        if (registry.hasAsyncExecutionEngine() && runtime.asyncCapable()) {
            registry.asyncExecutionEngine().applyRuntime(runtime);
        }
    }
}
