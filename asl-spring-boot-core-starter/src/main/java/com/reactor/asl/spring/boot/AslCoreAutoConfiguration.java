package com.reactor.asl.spring.boot;

import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.RuntimeDefaults;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@AutoConfiguration
@ConditionalOnClass(GovernanceRegistry.class)
@EnableConfigurationProperties(AslRuntimeProperties.class)
public class AslCoreAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    GovernanceRegistry governanceRegistry(AslRuntimeProperties properties) {
        return new GovernanceRegistry(new RuntimeDefaults(
                properties.getDefaultUnavailableMessage(),
                properties.getMaxConcurrencyExceededMessageTemplate(),
                properties.getMaxLastErrorCharacters(),
                properties.getErrorDetailPolicy()
        ));
    }

    @Bean
    @ConditionalOnMissingBean
    static AslGovernedBeanVerifier aslGovernedBeanVerifier(Environment environment) {
        // Static and Environment-based so the check never forces early initialization of other beans.
        UnwrappedGovernedBeanPolicy policy = environment.getProperty(
                "asl.runtime.unwrapped-governed-bean-policy",
                UnwrappedGovernedBeanPolicy.class,
                UnwrappedGovernedBeanPolicy.WARN
        );
        return new AslGovernedBeanVerifier(policy);
    }

    @Bean
    @ConditionalOnMissingBean
    AslRuntimeMethodOverridesApplier aslRuntimeMethodOverridesApplier(
            GovernanceRegistry governanceRegistry,
            AslRuntimeProperties properties,
            Environment environment
    ) {
        return new AslRuntimeMethodOverridesApplier(governanceRegistry, properties, environment);
    }

    @Bean
    @ConditionalOnMissingBean
    AslCoreRuntimeDefaultsApplier aslCoreRuntimeDefaultsApplier(
            GovernanceRegistry governanceRegistry,
            AslRuntimeProperties properties
    ) {
        return new AslCoreRuntimeDefaultsApplier(governanceRegistry, properties);
    }
}
