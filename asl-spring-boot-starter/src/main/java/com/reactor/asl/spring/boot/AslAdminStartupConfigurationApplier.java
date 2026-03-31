package com.reactor.asl.spring.boot;

import org.springframework.beans.factory.SmartInitializingSingleton;

final class AslAdminStartupConfigurationApplier implements SmartInitializingSingleton {
    private final AslAdminFacade facade;

    AslAdminStartupConfigurationApplier(AslAdminFacade facade) {
        this.facade = facade;
    }

    @Override
    public void afterSingletonsInstantiated() {
        facade.applyConfiguredMethodOverrides();
    }
}
