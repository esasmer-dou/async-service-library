package com.reactor.asl.admin.client;

import java.util.Objects;

/**
 * @param intervalMillis   heartbeat interval used until the server sends another one
 * @param maxBackoffMillis upper bound for the retry delay while the server is unreachable
 * @param queueBrowse      whether the server may list queue entries (payload summaries leave the pod)
 * @param browseLimit      maximum entries returned for one browse command
 * @param maxErrorChars    error texts sent to the server are cut to this length
 * @param maxSummaryChars  payload summaries sent to the server are cut to this length
 */
public record AslAdminClientSettings(
        String application,
        String instance,
        String libraryVersion,
        long intervalMillis,
        long maxBackoffMillis,
        boolean queueBrowse,
        int browseLimit,
        int maxErrorChars,
        int maxSummaryChars
) {
    public static final int DEFAULT_MAX_SUMMARY_CHARS = 200;

    public AslAdminClientSettings(String application, String instance, String libraryVersion, long intervalMillis,
                                  long maxBackoffMillis, boolean queueBrowse, int browseLimit, int maxErrorChars) {
        this(application, instance, libraryVersion, intervalMillis, maxBackoffMillis, queueBrowse, browseLimit, maxErrorChars,
                DEFAULT_MAX_SUMMARY_CHARS);
    }

    public AslAdminClientSettings {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(instance, "instance");
        if (application.isBlank() || instance.isBlank()) {
            throw new IllegalArgumentException("application and instance must not be blank");
        }
        if (intervalMillis < 100) {
            throw new IllegalArgumentException("intervalMillis must be at least 100");
        }
        if (maxBackoffMillis < intervalMillis) {
            throw new IllegalArgumentException("maxBackoffMillis must be at least intervalMillis");
        }
        if (browseLimit < 1 || browseLimit > 500) {
            throw new IllegalArgumentException("browseLimit must be between 1 and 500");
        }
        if (maxErrorChars < 16) {
            throw new IllegalArgumentException("maxErrorChars must be at least 16");
        }
        if (maxSummaryChars < 0 || maxSummaryChars > 2_000) {
            throw new IllegalArgumentException("maxSummaryChars must be between 0 and 2000");
        }
    }
}
