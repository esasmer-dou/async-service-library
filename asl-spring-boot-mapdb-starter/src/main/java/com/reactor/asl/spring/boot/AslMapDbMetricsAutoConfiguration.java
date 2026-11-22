package com.reactor.asl.spring.boot;

import com.reactor.asl.mapdb.MapDbAsyncExecutionEngine;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = AslMapDbAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean({MapDbAsyncExecutionEngine.class, MeterRegistry.class})
@ConditionalOnProperty(prefix = "asl.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AslObservabilityProperties.class)
public class AslMapDbMetricsAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    AslMapDbMetricsBinder aslMapDbMetricsBinder(
            MapDbAsyncExecutionEngine engine,
            MeterRegistry meterRegistry,
            AslObservabilityProperties properties
    ) {
        return new AslMapDbMetricsBinder(engine, meterRegistry, properties);
    }
}
