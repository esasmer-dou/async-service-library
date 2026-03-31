package com.reactor.asl.spring.boot;

import com.reactor.asl.core.BufferAdminProvider;
import com.reactor.asl.core.GovernanceRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;

import java.util.List;

@AutoConfiguration(after = AslCoreAutoConfiguration.class)
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnBean(GovernanceRegistry.class)
@ConditionalOnProperty(prefix = "asl.admin", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AslAdminProperties.class)
public class AslAdminAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    AslStartupRecoveryState aslStartupRecoveryState() {
        return new AslStartupRecoveryState();
    }

    @Bean
    @ConditionalOnMissingBean
    AslAdminRuntimeConfiguration aslAdminRuntimeConfiguration(AslAdminProperties properties) {
        return new AslAdminRuntimeConfiguration(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    AslAdminFacade aslAdminFacade(
            GovernanceRegistry governanceRegistry,
            AslAdminProperties properties,
            AslAdminRuntimeConfiguration runtimeConfiguration,
            AslStartupRecoveryState startupRecoveryState,
            List<BufferAdminProvider> bufferAdminProviders
    ) {
        return new AslAdminFacade(governanceRegistry, properties, runtimeConfiguration, startupRecoveryState, bufferAdminProviders);
    }

    @Bean
    @ConditionalOnMissingBean
    AslAdminStartupConfigurationApplier aslAdminStartupConfigurationApplier(AslAdminFacade facade) {
        return new AslAdminStartupConfigurationApplier(facade);
    }

    @Bean
    @ConditionalOnMissingBean
    AslAdminRestController aslAdminRestController(AslAdminFacade facade) {
        return new AslAdminRestController(facade);
    }

    @Bean
    @ConditionalOnMissingBean
    AslAdminPageController aslAdminPageController(AslAdminFacade facade) {
        return new AslAdminPageController(facade);
    }
}
