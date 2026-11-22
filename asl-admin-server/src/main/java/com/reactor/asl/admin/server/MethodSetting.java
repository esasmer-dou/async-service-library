package com.reactor.asl.admin.server;

/** Desired override for one method as typed in the UI. {@code null} keeps the application's own value. */
public record MethodSetting(Boolean enabled, Boolean async, Integer maxConcurrency, Integer consumerThreads) {
    public static final MethodSetting NONE = new MethodSetting(null, null, null, null);

    public MethodSetting {
        if (maxConcurrency != null && maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be at least 1");
        }
        if (consumerThreads != null && consumerThreads < 0) {
            throw new IllegalArgumentException("consumerThreads must be zero or positive");
        }
    }

    public boolean isEmpty() {
        return enabled == null && async == null && maxConcurrency == null && consumerThreads == null;
    }

    /** Compact text form used in the store file and audit: {@code enabled=true;async=false;max=20;consumers=2}. */
    public String toText() {
        StringBuilder text = new StringBuilder();
        append(text, "enabled", enabled);
        append(text, "async", async);
        append(text, "max", maxConcurrency);
        append(text, "consumers", consumerThreads);
        return text.toString();
    }

    public static MethodSetting parse(String text) {
        Boolean enabled = null;
        Boolean async = null;
        Integer max = null;
        Integer consumers = null;
        for (String part : text.split(";")) {
            if (part.isBlank()) {
                continue;
            }
            String[] pair = part.split("=", 2);
            if (pair.length != 2) {
                throw new IllegalArgumentException("Invalid setting: " + text);
            }
            String value = pair[1].trim();
            switch (pair[0].trim()) {
                case "enabled" -> enabled = Boolean.parseBoolean(value);
                case "async" -> async = Boolean.parseBoolean(value);
                case "max" -> max = Integer.parseInt(value);
                case "consumers" -> consumers = Integer.parseInt(value);
                default -> throw new IllegalArgumentException("Unknown setting key: " + pair[0]);
            }
        }
        return new MethodSetting(enabled, async, max, consumers);
    }

    private static void append(StringBuilder text, String key, Object value) {
        if (value != null) {
            if (!text.isEmpty()) {
                text.append(';');
            }
            text.append(key).append('=').append(value);
        }
    }
}
