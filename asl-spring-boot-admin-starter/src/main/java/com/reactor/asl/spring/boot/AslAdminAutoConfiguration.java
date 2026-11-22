package com.reactor.asl.spring.boot;

import com.reactor.asl.core.BufferAdminProvider;
import com.reactor.asl.core.GovernanceRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@AutoConfiguration(after = AslCoreAutoConfiguration.class)
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnBean(GovernanceRegistry.class)
@ConditionalOnProperty(prefix = "asl.admin", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AslAdminProperties.class)
public class AslAdminAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    AslAdminAuditSink aslAdminAuditSink() {
        return new LoggingAslAdminAuditSink();
    }

    @Bean
    @ConditionalOnMissingBean
    AslAdminAccessGuard aslAdminAccessGuard(
            AslAdminProperties properties,
            ObjectProvider<AslAdminAccessPolicy> accessPolicyProvider,
            AslAdminAuditSink auditSink
    ) {
        AslAdminAccessPolicy accessPolicy = accessPolicyProvider.getIfAvailable();
        if (!properties.getSecurity().isAllowUnauthenticated() && accessPolicy == null) {
            throw new IllegalStateException(
                    "ASL admin is enabled but no AslAdminAccessPolicy bean is configured. "
                            + "Provide an access policy or explicitly set "
                            + "asl.admin.security.allow-unauthenticated=true for an isolated demo only."
            );
        }
        return AslAdminAccessGuard.from(properties, accessPolicy, auditSink);
    }

    @Bean
    @ConditionalOnMissingBean
    AslAdminSecurityFilter aslAdminSecurityFilter(AslAdminProperties properties, AslAdminAccessGuard guard) {
        return new AslAdminSecurityFilter(properties, guard);
    }

    @Bean
    WebMvcConfigurer aslAdminAuthorizationWebMvcConfigurer(AslAdminAccessGuard guard) {
        AslAdminAuthorizationInterceptor interceptor = new AslAdminAuthorizationInterceptor(guard);
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor).order(Ordered.HIGHEST_PRECEDENCE);
            }
        };
    }

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
