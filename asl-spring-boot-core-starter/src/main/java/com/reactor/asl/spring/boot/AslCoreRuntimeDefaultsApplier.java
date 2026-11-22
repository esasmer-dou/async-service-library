package com.reactor.asl.spring.boot;

import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.RuntimeDefaults;
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
        registry.updateRuntimeDefaults(new RuntimeDefaults(
                properties.getDefaultUnavailableMessage(),
                properties.getMaxConcurrencyExceededMessageTemplate(),
                properties.getMaxLastErrorCharacters(),
                properties.getErrorDetailPolicy()
        ));
    }
}
