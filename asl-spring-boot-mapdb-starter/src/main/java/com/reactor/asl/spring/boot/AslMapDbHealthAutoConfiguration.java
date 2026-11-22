package com.reactor.asl.spring.boot;

import com.reactor.asl.mapdb.MapDbAsyncExecutionEngine;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * Registers {@link AslMapDbHealthIndicator} when Spring Boot Actuator is on the classpath.
 * Disable with {@code management.health.aslmapdb.enabled=false}.
 */
@AutoConfiguration(after = AslMapDbAutoConfiguration.class)
@ConditionalOnClass({HealthIndicator.class, MapDbAsyncExecutionEngine.class})
@ConditionalOnBean(MapDbAsyncExecutionEngine.class)
@ConditionalOnProperty(prefix = "management.health.aslmapdb", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AslMapDbHealthAutoConfiguration {
    @Bean(name = "aslMapDbHealthIndicator")
    @ConditionalOnMissingBean(name = "aslMapDbHealthIndicator")
    AslMapDbHealthIndicator aslMapDbHealthIndicator(MapDbAsyncExecutionEngine engine) {
        return new AslMapDbHealthIndicator(engine, Duration.ofSeconds(60));
    }
}
