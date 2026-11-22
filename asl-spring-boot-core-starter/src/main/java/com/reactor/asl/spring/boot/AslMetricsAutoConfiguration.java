package com.reactor.asl.spring.boot;

import com.reactor.asl.core.GovernanceRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = AslCoreAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean({GovernanceRegistry.class, MeterRegistry.class})
@ConditionalOnProperty(prefix = "asl.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AslObservabilityProperties.class)
public class AslMetricsAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    AslCoreMetricsBinder aslCoreMetricsBinder(
            GovernanceRegistry governanceRegistry,
            MeterRegistry meterRegistry,
            AslObservabilityProperties properties
    ) {
        return new AslCoreMetricsBinder(governanceRegistry, meterRegistry, properties);
    }
}
