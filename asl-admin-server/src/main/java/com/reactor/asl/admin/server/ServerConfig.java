package com.reactor.asl.admin.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Settings of the admin server. Sources, later ones win: built-in defaults, the properties file
 * given with {@code --config=<path>} (or {@code asl-admin.properties} in the working directory),
 * environment variables ({@code asl.admin.server.secret} as {@code ASL_ADMIN_SERVER_SECRET}),
 * JVM system properties, then {@code --key=value} arguments.
 */
public final class ServerConfig {
    public static final String PREFIX = "asl.admin.server.";

    public enum StorageMode { FILE, MEMORY }

    public enum SecurityMode { BASIC, PROXY_HEADER, NONE }

    public enum Role { VIEWER, OPERATOR }

    public record User(String username, String password, Role role) {
    }

    final String bindAddress;
    final int port;
    final int threads;
    final String secret;
    final Duration interval;
    final Duration watchInterval;
    final Duration watchIdleTimeout;
    final Duration expireAfter;
    final int maxInstances;
    final int maxBodyBytes;
    final Duration signatureTolerance;
    final Duration commandTtl;
    final int maxPendingCommands;
    final int maxCommandResults;
    final StorageMode storageMode;
    final Path storagePath;
    final long storageMaxBytes;
    final boolean auditEnabled;
    final int auditMaxEntries;
    final Duration auditRetention;
    final boolean historyEnabled;
    final Duration historyResolution;
    final Duration historyRetention;
    final int historyMaxSeries;
    final SecurityMode securityMode;
    final List<User> users;
    final String proxyUserHeader;
    final String proxyGroupsHeader;
    final String proxyOperatorGroup;
    final List<String> allowedOrigins;

    private ServerConfig(Map<String, String> values) {
        Map<String, String> v = values;
        bindAddress = text(v, "bind-address", "0.0.0.0");
        port = integer(v, "port", 8080, 0, 65_535);
        threads = integer(v, "threads", 2, 1, 64);
        secret = firstNonBlank(v.get(PREFIX + "secret"), v.get("ASL_ADMIN_SECRET"));
        interval = duration(v, "interval", "15s");
        watchInterval = duration(v, "watch.interval", "2s");
        watchIdleTimeout = duration(v, "watch.idle-timeout", "60s");
        String expire = v.get(PREFIX + "instance.expire-after");
        expireAfter = expire == null || expire.isBlank() ? interval.multipliedBy(3) : parseDuration(expire, "instance.expire-after");
        maxInstances = integer(v, "instance.max-instances", 2_000, 1, 1_000_000);
        maxBodyBytes = (int) bytes(v, "max-body-size", "64KB", 1_024, 16L * 1024 * 1024);
        signatureTolerance = duration(v, "signature-tolerance", "60s");
        commandTtl = duration(v, "commands.ttl", "5m");
        maxPendingCommands = integer(v, "commands.max-pending", 32, 1, 10_000);
        maxCommandResults = integer(v, "commands.max-results", 50, 0, 10_000);
        storageMode = StorageMode.valueOf(text(v, "storage.mode", "FILE").toUpperCase(Locale.ROOT));
        storagePath = Path.of(text(v, "storage.path", "data/asl-admin.db"));
        storageMaxBytes = bytes(v, "storage.max-size", "64MB", 1, 1L << 40);
        auditEnabled = Boolean.parseBoolean(text(v, "audit.enabled", "true"));
        auditMaxEntries = integer(v, "audit.max-entries", 1_000, 1, 1_000_000);
        auditRetention = duration(v, "audit.retention", "7d");
        historyEnabled = Boolean.parseBoolean(text(v, "history.enabled", "true"));
        historyResolution = duration(v, "history.resolution", "1m");
        historyRetention = duration(v, "history.retention", "1h");
        historyMaxSeries = integer(v, "history.max-series", 2_000, 1, 1_000_000);
        securityMode = SecurityMode.valueOf(text(v, "security.mode", "BASIC").toUpperCase(Locale.ROOT).replace('-', '_'));
        users = users(v);
        proxyUserHeader = text(v, "security.proxy.user-header", "X-Forwarded-User");
        proxyGroupsHeader = text(v, "security.proxy.groups-header", "X-Forwarded-Groups");
        proxyOperatorGroup = text(v, "security.proxy.operator-group", "asl-operator");
        allowedOrigins = list(text(v, "security.allowed-origins", ""));
        validate();
    }

    /** Builds the configuration from every source; {@code args} may contain {@code --config=path}. */
    public static ServerConfig load(String[] args, Map<String, String> environment, Properties systemProperties) {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> arguments = arguments(args);
        String configFile = arguments.getOrDefault("config", "asl-admin.properties");
        Path file = Path.of(configFile);
        if (Files.isRegularFile(file)) {
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(file)) {
                properties.load(input);
            } catch (IOException exception) {
                throw new UncheckedIOException("Cannot read " + file, exception);
            }
            properties.forEach((key, value) -> values.put(key.toString(), value.toString()));
        } else if (arguments.containsKey("config")) {
            throw new IllegalArgumentException("Configuration file not found: " + file.toAbsolutePath());
        }
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            String name = entry.getKey();
            if (name.startsWith("ASL_ADMIN_SERVER_")) {
                values.put(fromEnvironment(name), entry.getValue());
            } else if (name.equals("ASL_ADMIN_SECRET") || name.equals("ASL_ADMIN_USER") || name.equals("ASL_ADMIN_PASSWORD")) {
                values.put(name, entry.getValue());
            }
        }
        systemProperties.forEach((key, value) -> {
            if (key.toString().startsWith(PREFIX)) {
                values.put(key.toString(), value.toString());
            }
        });
        arguments.forEach((key, value) -> {
            if (!key.equals("config")) {
                values.put(key.startsWith(PREFIX) ? key : PREFIX + key, value);
            }
        });
        return new ServerConfig(values);
    }

    public static ServerConfig of(Map<String, String> values) {
        Map<String, String> prefixed = new LinkedHashMap<>();
        values.forEach((key, value) -> prefixed.put(key.startsWith(PREFIX) || key.startsWith("ASL_") ? key : PREFIX + key, value));
        return new ServerConfig(prefixed);
    }

    private void validate() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("asl.admin.server.secret is required (env ASL_ADMIN_SECRET); use the same value as asl.admin.client.secret");
        }
        if (interval.toMillis() < 100 || watchInterval.toMillis() < 100) {
            throw new IllegalArgumentException("interval and watch.interval must be at least 100ms");
        }
        if (expireAfter.compareTo(interval) <= 0) {
            throw new IllegalArgumentException("instance.expire-after must be greater than interval");
        }
        if (storageMode == StorageMode.FILE && storageMaxBytes < AdminStore.MIN_FILE_BYTES) {
            throw new IllegalArgumentException("storage.max-size must be at least 2MB (MapDB's smallest file)");
        }
        if (historyEnabled && historyResolution.toMillis() < 1_000) {
            throw new IllegalArgumentException("history.resolution must be at least 1s");
        }
        if (historyEnabled && (historyRetention.compareTo(historyResolution) < 0 || historyRetention.toMillis() / historyResolution.toMillis() > 100_000)) {
            throw new IllegalArgumentException("history.retention / history.resolution must be between 1 and 100000 points");
        }
        if (securityMode == SecurityMode.BASIC && users.isEmpty()) {
            throw new IllegalArgumentException("security.mode=BASIC needs a user: set ASL_ADMIN_USER and ASL_ADMIN_PASSWORD, "
                    + "or asl.admin.server.security.users=name:password:OPERATOR (use PROXY_HEADER behind an SSO proxy, NONE only for a demo)");
        }
    }

    /** Points kept per series: retention / resolution. */
    int historySlots() {
        return (int) Math.max(1, historyRetention.toMillis() / historyResolution.toMillis());
    }

    private static List<User> users(Map<String, String> v) {
        List<User> result = new ArrayList<>();
        for (String entry : list(text(v, "security.users", ""))) {
            String[] parts = entry.split(":", 3);
            if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException("security.users entries are name:password[:ROLE]");
            }
            Role role = parts.length == 3 ? Role.valueOf(parts[2].trim().toUpperCase(Locale.ROOT)) : Role.VIEWER;
            result.add(new User(parts[0].trim(), parts[1], role));
        }
        String user = v.get("ASL_ADMIN_USER");
        String password = v.get("ASL_ADMIN_PASSWORD");
        if (user != null && !user.isBlank() && password != null && !password.isBlank()) {
            result.add(new User(user.trim(), password, Role.OPERATOR));
        }
        return List.copyOf(result);
    }

    static Map<String, String> arguments(String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String arg : args == null ? new String[0] : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) {
                throw new IllegalArgumentException("Arguments are --key=value, got: " + arg);
            }
            int equals = arg.indexOf('=');
            result.put(arg.substring(2, equals), arg.substring(equals + 1));
        }
        return result;
    }

    /** {@code ASL_ADMIN_SERVER_WATCH_IDLE_TIMEOUT} cannot tell dots from dashes; known keys resolve it. */
    static String fromEnvironment(String name) {
        String tail = name.substring("ASL_ADMIN_SERVER_".length()).toLowerCase(Locale.ROOT);
        for (String key : KNOWN_KEYS) {
            if (key.replace('.', '_').replace('-', '_').equals(tail)) {
                return PREFIX + key;
            }
        }
        return PREFIX + tail.replace('_', '.');
    }

    private static final List<String> KNOWN_KEYS = List.of(
            "bind-address", "port", "threads", "secret", "interval", "watch.interval", "watch.idle-timeout",
            "instance.expire-after", "instance.max-instances", "max-body-size", "signature-tolerance",
            "commands.ttl", "commands.max-pending", "commands.max-results", "storage.mode", "storage.path", "storage.max-size",
            "audit.enabled", "audit.max-entries", "audit.retention", "history.enabled", "history.resolution",
            "history.retention", "history.max-series", "security.mode", "security.users",
            "security.proxy.user-header", "security.proxy.groups-header", "security.proxy.operator-group",
            "security.allowed-origins");

    private static String text(Map<String, String> v, String key, String fallback) {
        String value = v.get(PREFIX + key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int integer(Map<String, String> v, String key, int fallback, int min, int max) {
        String value = v.get(PREFIX + key);
        int parsed = value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        }
        return parsed;
    }

    private static Duration duration(Map<String, String> v, String key, String fallback) {
        return parseDuration(text(v, key, fallback), key);
    }

    static Duration parseDuration(String text, String key) {
        String value = text.trim().toLowerCase(Locale.ROOT);
        try {
            if (value.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2)));
            }
            long amount = Long.parseLong(value.substring(0, value.length() - 1));
            return switch (value.charAt(value.length() - 1)) {
                case 's' -> Duration.ofSeconds(amount);
                case 'm' -> Duration.ofMinutes(amount);
                case 'h' -> Duration.ofHours(amount);
                case 'd' -> Duration.ofDays(amount);
                default -> Duration.ofMillis(Long.parseLong(value));
            };
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(key + ": invalid duration '" + text + "' (use 500ms, 15s, 5m, 1h, 7d)");
        }
    }

    private static long bytes(Map<String, String> v, String key, String fallback, long min, long max) {
        String value = text(v, key, fallback).toUpperCase(Locale.ROOT);
        long parsed;
        try {
            if (value.endsWith("KB")) {
                parsed = Long.parseLong(value.substring(0, value.length() - 2).trim()) * 1024;
            } else if (value.endsWith("MB")) {
                parsed = Long.parseLong(value.substring(0, value.length() - 2).trim()) * 1024 * 1024;
            } else if (value.endsWith("B")) {
                parsed = Long.parseLong(value.substring(0, value.length() - 1).trim());
            } else {
                parsed = Long.parseLong(value);
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + ": invalid size '" + value + "' (use 512KB, 1MB)");
        }
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max + " bytes");
        }
        return parsed;
    }

    private static List<String> list(String value) {
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                result.add(part.trim());
            }
        }
        return result;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
