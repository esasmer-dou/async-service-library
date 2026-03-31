package com.reactor.asl.spring.boot;

import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.MethodRuntime;
import org.springframework.beans.factory.SmartInitializingSingleton;

final class AslCoreRuntimeDefaultsApplier implements SmartInitializingSingleton {
    private final GovernanceRegistry registry;
    private final AslRuntimeProperties properties;

    AslCoreRuntimeDefaultsApplier(GovernanceRegistry registry, AslRuntimeProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    @Override
    public void afterSingletonsInstantiated() {
        String legacyDefaultUnavailableMessage = MethodRuntime.defaultUnavailableMessage();
        MethodRuntime.configureDefaults(
                properties.getDefaultUnavailableMessage(),
                properties.getMaxConcurrencyExceededMessageTemplate()
        );
        if (!legacyDefaultUnavailableMessage.equals(properties.getDefaultUnavailableMessage())) {
            registry.services().forEach(service -> service.methods().forEach(method -> {
                if (method.policy().unavailableMessage().equals(legacyDefaultUnavailableMessage)) {
                    method.setUnavailableMessage(properties.getDefaultUnavailableMessage());
                }
            }));
        }
    }
}
