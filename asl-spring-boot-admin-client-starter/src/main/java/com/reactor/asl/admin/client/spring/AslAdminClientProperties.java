package com.reactor.asl.admin.client.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Settings of the connection from this application to the central ASL admin server. */
@ConfigurationProperties(prefix = "asl.admin.client")
public class AslAdminClientProperties {
    /** Connect this application to the central ASL admin server. */
    private boolean enabled;
    /** Base URL of the admin server, for example http://asl-admin-server:8080. */
    private String serverUrl;
    /** Shared HMAC secret, at least 16 bytes. Provide it from a secret store, not from a committed file. */
    private String secret;
    /** Application name shown in the admin server. Defaults to spring.application.name. */
    private String application;
    /** Instance name shown in the admin server. Defaults to the HOSTNAME environment variable (the pod name in Kubernetes). */
    private String instance;
    /** Heartbeat interval while nobody is watching this application in the admin server. */
    private Duration interval = Duration.ofSeconds(15);
    /** Upper bound of the retry delay while the admin server is unreachable. */
    private Duration maxBackoff = Duration.ofSeconds(60);
    /** TCP connect timeout. */
    private Duration connectTimeout = Duration.ofSeconds(2);
    /** Timeout of one request. */
    private Duration requestTimeout = Duration.ofSeconds(3);
    /** Allow the admin server to list queue entries. Entry summaries and error texts then leave the pod. */
    private boolean queueBrowse;
    /** Maximum queue entries returned for one browse request. */
    private int browseLimit = 50;
    /** Error texts sent to the admin server are cut to this many characters. */
    private int maxErrorChars = 200;
    /**
     * Payload summaries sent to the admin server are cut to this many characters; 0 sends none. What the
     * summary holds is set by asl.async.mapdb.payload.summary-policy (type only by default, never the payload).
     */
    private int maxSummaryChars = 200;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getApplication() {
        return application;
    }

    public void setApplication(String application) {
        this.application = application;
    }

    public String getInstance() {
        return instance;
    }

    public void setInstance(String instance) {
        this.instance = instance;
    }

    public Duration getInterval() {
        return interval;
    }

    public void setInterval(Duration interval) {
        this.interval = interval;
    }

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public void setMaxBackoff(Duration maxBackoff) {
        this.maxBackoff = maxBackoff;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public boolean isQueueBrowse() {
        return queueBrowse;
    }

    public void setQueueBrowse(boolean queueBrowse) {
        this.queueBrowse = queueBrowse;
    }

    public int getBrowseLimit() {
        return browseLimit;
    }

    public void setBrowseLimit(int browseLimit) {
        this.browseLimit = browseLimit;
    }

    public int getMaxErrorChars() {
        return maxErrorChars;
    }

    public void setMaxErrorChars(int maxErrorChars) {
        this.maxErrorChars = maxErrorChars;
    }

    public int getMaxSummaryChars() {
        return maxSummaryChars;
    }

    public void setMaxSummaryChars(int maxSummaryChars) {
        this.maxSummaryChars = maxSummaryChars;
    }
}
