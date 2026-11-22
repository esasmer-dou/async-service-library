package com.reactor.asl.admin.server;

import com.reactor.asl.admin.server.ServerConfig.Role;
import com.reactor.asl.admin.server.ServerConfig.User;
import com.sun.net.httpserver.Headers;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Login for the UI and its API. Reads need VIEWER; changes need OPERATOR and a same-origin request
 * (or a configured origin). Passwords are compared as SHA-256 digests in constant time.
 */
final class UiAuth {
    record Principal(String name, Role role) {
    }

    private final ServerConfig config;
    private final List<String> allowedOrigins;

    UiAuth(ServerConfig config) {
        this.config = config;
        this.allowedOrigins = config.allowedOrigins.stream().map(UiAuth::normalizeOrigin).toList();
    }

    Principal authenticate(Headers headers) {
        return switch (config.securityMode) {
            case NONE -> new Principal("anonymous", Role.OPERATOR);
            case PROXY_HEADER -> {
                String user = headers.getFirst(config.proxyUserHeader);
                if (user == null || user.isBlank()) {
                    yield null;
                }
                String groups = headers.getFirst(config.proxyGroupsHeader);
                boolean operator = groups != null && List.of(groups.split("[,\\s]+")).contains(config.proxyOperatorGroup);
                yield new Principal(user.trim(), operator ? Role.OPERATOR : Role.VIEWER);
            }
            case BASIC -> basic(headers.getFirst("Authorization"));
        };
    }

    boolean basicMode() {
        return config.securityMode == ServerConfig.SecurityMode.BASIC;
    }

    boolean isCrossOrigin(Headers headers) {
        String source = headers.getFirst("Origin");
        if (source == null || source.isBlank() || "null".equals(source)) {
            source = headers.getFirst("Referer");
        }
        if (source == null || source.isBlank()) {
            return false;
        }
        if (allowedOrigins.contains(normalizeOrigin(source))) {
            return false;
        }
        String host = headers.getFirst("X-Forwarded-Host");
        if (host == null || host.isBlank()) {
            host = headers.getFirst("Host");
        }
        if (host == null || host.isBlank()) {
            return true;
        }
        try {
            String authority = URI.create(source.trim()).getRawAuthority();
            return authority == null || !authority.equalsIgnoreCase(host.split(",")[0].trim());
        } catch (IllegalArgumentException exception) {
            return true;
        }
    }

    private Principal basic(String header) {
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        int colon = decoded.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        byte[] username = decoded.substring(0, colon).getBytes(StandardCharsets.UTF_8);
        String password = decoded.substring(colon + 1);
        Principal match = null;
        for (User user : config.users) {
            boolean nameMatches = MessageDigest.isEqual(user.username().getBytes(StandardCharsets.UTF_8), username);
            if (passwordMatches(user.password(), password) && nameMatches) {
                match = new Principal(user.username(), user.role());
            }
        }
        return match;
    }

    static boolean passwordMatches(String configured, String provided) {
        byte[] expected;
        if (configured.startsWith("{sha256}")) {
            try {
                expected = HexFormat.of().parseHex(configured.substring(8).trim().toLowerCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                return false;
            }
        } else {
            expected = sha256(configured);
        }
        return MessageDigest.isEqual(expected, sha256(provided));
    }

    private static String normalizeOrigin(String value) {
        try {
            URI uri = URI.create(value.trim());
            return (uri.getScheme() + "://" + uri.getRawAuthority()).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            return value.trim().toLowerCase(Locale.ROOT);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
