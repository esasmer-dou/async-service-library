package com.reactor.asl.spring.boot;

import com.reactor.asl.core.RuntimeDefaults;
import com.reactor.asl.core.RuntimeErrorDetailPolicy;
import com.reactor.asl.core.ExecutionMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties("asl.runtime")
public class AslRuntimeProperties {
    private String defaultUnavailableMessage = "Method is disabled";
    private String maxConcurrencyExceededMessageTemplate = "Method reached max concurrency: %d";
    private int maxLastErrorCharacters = RuntimeDefaults.DEFAULT_MAX_LAST_ERROR_CHARACTERS;
    private RuntimeErrorDetailPolicy errorDetailPolicy = RuntimeErrorDetailPolicy.TYPE_ONLY;
    private UnwrappedGovernedBeanPolicy unwrappedGovernedBeanPolicy = UnwrappedGovernedBeanPolicy.WARN;
    private Map<String, ServiceOverrides> services = new LinkedHashMap<>();

    public String getDefaultUnavailableMessage() {
        return defaultUnavailableMessage;
    }

    public void setDefaultUnavailableMessage(String defaultUnavailableMessage) {
        this.defaultUnavailableMessage = defaultUnavailableMessage;
    }

    public String getMaxConcurrencyExceededMessageTemplate() {
        return maxConcurrencyExceededMessageTemplate;
    }

    public void setMaxConcurrencyExceededMessageTemplate(String maxConcurrencyExceededMessageTemplate) {
        this.maxConcurrencyExceededMessageTemplate = maxConcurrencyExceededMessageTemplate;
    }

    /** Maximum retained character count for the last synchronous invocation error. */
    public int getMaxLastErrorCharacters() {
        return maxLastErrorCharacters;
    }

    public void setMaxLastErrorCharacters(int maxLastErrorCharacters) {
        this.maxLastErrorCharacters = maxLastErrorCharacters;
    }

    /** Error type only by default; message retention must be explicitly enabled. */
    public RuntimeErrorDetailPolicy getErrorDetailPolicy() {
        return errorDetailPolicy;
    }

    public void setErrorDetailPolicy(RuntimeErrorDetailPolicy errorDetailPolicy) {
        this.errorDetailPolicy = errorDetailPolicy;
    }

    /**
     * Startup check for governed interfaces exposed as beans without their generated wrapper
     * (governance silently bypassed): WARN, FAIL or IGNORE.
     */
    public UnwrappedGovernedBeanPolicy getUnwrappedGovernedBeanPolicy() {
        return unwrappedGovernedBeanPolicy;
    }

    public void setUnwrappedGovernedBeanPolicy(UnwrappedGovernedBeanPolicy unwrappedGovernedBeanPolicy) {
        this.unwrappedGovernedBeanPolicy = unwrappedGovernedBeanPolicy == null
                ? UnwrappedGovernedBeanPolicy.WARN
                : unwrappedGovernedBeanPolicy;
    }

    /**
     * Startup overrides per governed service and method, applied without the admin starter. Keys are
     * the {@code @GovernedService} id and the method id/signature.
     */
    public Map<String, ServiceOverrides> getServices() {
        return services;
    }

    public void setServices(Map<String, ServiceOverrides> services) {
        this.services = services == null ? new LinkedHashMap<>() : new LinkedHashMap<>(services);
    }

    public static final class ServiceOverrides {
        private Map<String, MethodOverrides> methods = new LinkedHashMap<>();

        public Map<String, MethodOverrides> getMethods() {
            return methods;
        }

        public void setMethods(Map<String, MethodOverrides> methods) {
            this.methods = methods == null ? new LinkedHashMap<>() : new LinkedHashMap<>(methods);
        }
    }

    public static final class MethodOverrides {
        private Boolean enabled;
        private Integer maxConcurrency;
        private String unavailableMessage;
        private ExecutionMode executionMode;
        private Integer consumerThreads;

        /** Enables or disables the method at startup; omitted keeps the annotation value. */
        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        /** Concurrency limit for SYNC and ASYNC execution; omitted keeps the annotation value. */
        public Integer getMaxConcurrency() {
            return maxConcurrency;
        }

        public void setMaxConcurrency(Integer maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
        }

        /** Caller-facing message while the method is disabled. */
        public String getUnavailableMessage() {
            return unavailableMessage;
        }

        public void setUnavailableMessage(String unavailableMessage) {
            this.unavailableMessage = unavailableMessage;
        }

        /** SYNC or ASYNC; ASYNC requires an async-capable void method and an attached engine. */
        public ExecutionMode getExecutionMode() {
            return executionMode;
        }

        public void setExecutionMode(ExecutionMode executionMode) {
            this.executionMode = executionMode;
        }

        /** Async lane consumers; 0 accepts backlog without consuming it. */
        public Integer getConsumerThreads() {
            return consumerThreads;
        }

        public void setConsumerThreads(Integer consumerThreads) {
            this.consumerThreads = consumerThreads;
        }

        private Integer rateLimit;

        /**
         * Calls per second on each replica; 0 or omitted means no limit. SYNC calls over the limit
         * are rejected with reason RATE_LIMIT; in ASYNC mode calls are queued and consumers start at
         * most this many executions per second.
         */
        public Integer getRateLimit() {
            return rateLimit;
        }

        public void setRateLimit(Integer rateLimit) {
            this.rateLimit = rateLimit;
        }
    }
}
