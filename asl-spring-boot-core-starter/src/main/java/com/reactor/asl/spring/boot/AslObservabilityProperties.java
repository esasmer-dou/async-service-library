package com.reactor.asl.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("asl.observability")
public class AslObservabilityProperties {
    private boolean enabled = true;
    private String metricPrefix = "asl";
    private int maxMethodMeters = 2_000;
    /**
     * Records a per-method latency Timer ({@code <prefix>.method.duration}) for synchronous governed
     * calls. Off by default because it adds two clock reads to every governed call.
     */
    private boolean methodLatencyEnabled;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMetricPrefix() {
        return metricPrefix;
    }

    public void setMetricPrefix(String metricPrefix) {
        this.metricPrefix = metricPrefix;
    }

    public int getMaxMethodMeters() {
        return maxMethodMeters;
    }

    public void setMaxMethodMeters(int maxMethodMeters) {
        this.maxMethodMeters = maxMethodMeters;
    }

    public boolean isMethodLatencyEnabled() {
        return methodLatencyEnabled;
    }

    public void setMethodLatencyEnabled(boolean methodLatencyEnabled) {
        this.methodLatencyEnabled = methodLatencyEnabled;
    }

    void validate() {
        if (metricPrefix == null || metricPrefix.isBlank()) {
            throw new IllegalArgumentException("asl.observability.metric-prefix must not be blank");
        }
        if (maxMethodMeters <= 0) {
            throw new IllegalArgumentException("asl.observability.max-method-meters must be positive");
        }
    }
}
