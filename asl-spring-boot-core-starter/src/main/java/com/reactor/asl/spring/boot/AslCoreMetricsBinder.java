package com.reactor.asl.spring.boot;

import com.reactor.asl.core.ExecutionMode;
import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.MethodRuntime;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class AslCoreMetricsBinder implements SmartInitializingSingleton {
    private final GovernanceRegistry governanceRegistry;
    private final MeterRegistry meterRegistry;
    private final AslObservabilityProperties properties;

    AslCoreMetricsBinder(
            GovernanceRegistry governanceRegistry,
            MeterRegistry meterRegistry,
            AslObservabilityProperties properties
    ) {
        this.governanceRegistry = governanceRegistry;
        this.meterRegistry = meterRegistry;
        this.properties = properties;
    }

    @Override
    public void afterSingletonsInstantiated() {
        properties.validate();
        List<MethodRuntime> methods = governanceRegistry.services().stream()
                .flatMap(service -> service.methods().stream())
                .sorted(Comparator
                        .comparing(MethodRuntime::serviceId)
                        .thenComparing(MethodRuntime::methodId))
                .toList();
        if (methods.size() > properties.getMaxMethodMeters()) {
            throw new IllegalStateException(
                    "ASL metric cardinality limit exceeded: " + methods.size()
                            + " methods > " + properties.getMaxMethodMeters()
            );
        }
        methods.forEach(this::register);
    }

    private void register(MethodRuntime runtime) {
        String prefix = properties.getMetricPrefix();
        String service = runtime.serviceId();
        String method = runtime.methodId();

        FunctionCounter.builder(prefix + ".method.success", runtime, MethodRuntime::totalSuccessCount)
                .description("Successfully completed governed method calls")
                .tag("service", service)
                .tag("method", method)
                .register(meterRegistry);
        FunctionCounter.builder(prefix + ".method.errors", runtime, MethodRuntime::totalErrorCount)
                .description("Failed governed method calls")
                .tag("service", service)
                .tag("method", method)
                .register(meterRegistry);
        FunctionCounter.builder(prefix + ".method.rejected", runtime, MethodRuntime::totalRejectedCount)
                .description("Rejected governed method calls")
                .tag("service", service)
                .tag("method", method)
                .register(meterRegistry);
        Gauge.builder(prefix + ".method.in.flight", runtime, MethodRuntime::inFlight)
                .description("Currently executing governed calls")
                .tag("service", service)
                .tag("method", method)
                .register(meterRegistry);
        Gauge.builder(prefix + ".method.peak.in.flight", runtime, MethodRuntime::peakInFlight)
                .description("Peak concurrent governed calls since the last observation reset")
                .tag("service", service)
                .tag("method", method)
                .register(meterRegistry);
        Gauge.builder(prefix + ".method.max.concurrency", runtime, value -> value.policy().maxConcurrency())
                .description("Configured concurrency limit")
                .tag("service", service)
                .tag("method", method)
                .register(meterRegistry);
        Gauge.builder(prefix + ".method.enabled", runtime, value -> value.policy().enabled() ? 1.0d : 0.0d)
                .description("Method availability state")
                .tag("service", service)
                .tag("method", method)
                .register(meterRegistry);
        Gauge.builder(prefix + ".method.async.mode", runtime,
                        value -> value.executionMode() == ExecutionMode.ASYNC ? 1.0d : 0.0d)
                .description("Whether the method currently runs in async mode")
                .tag("service", service)
                .tag("method", method)
                .register(meterRegistry);
        Gauge.builder(prefix + ".method.consumer.threads", runtime, MethodRuntime::consumerThreads)
                .description("Configured async consumer concurrency")
                .tag("service", service)
                .tag("method", method)
                .register(meterRegistry);
        if (properties.isMethodLatencyEnabled()) {
            Timer timer = Timer.builder(prefix + ".method.duration")
                    .description("Duration of synchronous governed calls that acquired a concurrency slot")
                    .tag("service", service)
                    .tag("method", method)
                    .register(meterRegistry);
            runtime.latencyRecorder(nanos -> timer.record(nanos, TimeUnit.NANOSECONDS));
        }
    }
}
