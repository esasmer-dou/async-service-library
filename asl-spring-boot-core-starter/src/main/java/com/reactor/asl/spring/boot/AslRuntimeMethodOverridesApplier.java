package com.reactor.asl.spring.boot;

import com.reactor.asl.core.ExecutionMode;
import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.MethodRuntime;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.Map;
import java.util.Objects;

/**
 * Applies {@code asl.runtime.services.*} startup overrides after every singleton (wrappers and the
 * async engine) exists. Works without the admin starter, so a headless worker can start a lane in
 * ASYNC mode purely from configuration. If the admin starter also configures the same method, the
 * admin values are applied afterwards and win.
 */
final class AslRuntimeMethodOverridesApplier implements SmartInitializingSingleton {
    private final GovernanceRegistry registry;
    private final AslRuntimeProperties properties;

    AslRuntimeMethodOverridesApplier(GovernanceRegistry registry, AslRuntimeProperties properties) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public void afterSingletonsInstantiated() {
        for (Map.Entry<String, AslRuntimeProperties.ServiceOverrides> service : properties.getServices().entrySet()) {
            for (Map.Entry<String, AslRuntimeProperties.MethodOverrides> method
                    : service.getValue().getMethods().entrySet()) {
                apply(service.getKey(), method.getKey(), method.getValue());
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
                            + ". Use the @GovernedService id and the method id/signature.",
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
