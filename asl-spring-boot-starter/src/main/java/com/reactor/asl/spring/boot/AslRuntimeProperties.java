package com.reactor.asl.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("asl.runtime")
public class AslRuntimeProperties {
    private String defaultUnavailableMessage = "Method is disabled";
    private String maxConcurrencyExceededMessageTemplate = "Method reached max concurrency: %d";

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
}
