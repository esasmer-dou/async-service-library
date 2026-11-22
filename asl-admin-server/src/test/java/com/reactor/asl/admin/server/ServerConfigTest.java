package com.reactor.asl.admin.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConfigTest {
    @TempDir
    Path directory;

    @Test
    void defaultsNeedOnlySecretAndUser() {
        ServerConfig config = ServerConfig.load(new String[0],
                Map.of("ASL_ADMIN_SECRET", "0123456789abcdef-x", "ASL_ADMIN_USER", "admin", "ASL_ADMIN_PASSWORD", "pw"), new Properties());
        assertEquals(8080, config.port);
        assertEquals(Duration.ofSeconds(15), config.interval);
        assertEquals(Duration.ofSeconds(45), config.expireAfter);
        assertEquals(64 * 1024, config.maxBodyBytes);
        assertEquals(ServerConfig.StorageMode.FILE, config.storageMode);
        assertEquals(60, config.historySlots());
        assertEquals(1_000, config.auditMaxEntries);
        assertEquals(1, config.users.size());
        assertEquals(ServerConfig.Role.OPERATOR, config.users.get(0).role());
    }

    @Test
    void laterSourcesWin() throws Exception {
        Path file = directory.resolve("admin.properties");
        Files.writeString(file, "asl.admin.server.secret=file-secret-0123456789\nasl.admin.server.port=9000\n"
                + "asl.admin.server.security.mode=NONE\nasl.admin.server.watch.idle-timeout=30s\n");
        Properties system = new Properties();
        system.setProperty("asl.admin.server.port", "9100");
        ServerConfig config = ServerConfig.load(new String[]{"--config=" + file, "--threads=2", "--asl.admin.server.storage.max-size=4MB"},
                Map.of("ASL_ADMIN_SERVER_PORT", "9001", "ASL_ADMIN_SERVER_WATCH_IDLE_TIMEOUT", "90s", "ASL_ADMIN_SERVER_STORAGE_MODE", "memory"),
                system);
        assertEquals(9100, config.port);
        assertEquals(2, config.threads);
        assertEquals(Duration.ofSeconds(90), config.watchIdleTimeout);
        assertEquals(ServerConfig.StorageMode.MEMORY, config.storageMode);
        assertEquals(4L * 1024 * 1024, config.storageMaxBytes);
        assertEquals("file-secret-0123456789", config.secret);
    }

    @Test
    void parsesUsersDurationsAndSizes() {
        ServerConfig config = ServerConfig.of(Map.of("secret", "0123456789abcdef-x",
                "security.users", "alice:pw1:OPERATOR, bob:{sha256}abc, carol:pw3:viewer",
                "interval", "500ms", "instance.expire-after", "2m", "commands.ttl", "1h", "max-body-size", "2048",
                "security.allowed-origins", "https://a.example, https://b.example"));
        assertEquals(3, config.users.size());
        assertEquals(ServerConfig.Role.VIEWER, config.users.get(1).role());
        assertEquals(ServerConfig.Role.VIEWER, config.users.get(2).role());
        assertEquals(Duration.ofMillis(500), config.interval);
        assertEquals(Duration.ofMinutes(2), config.expireAfter);
        assertEquals(Duration.ofHours(1), config.commandTtl);
        assertEquals(2048, config.maxBodyBytes);
        assertEquals(2, config.allowedOrigins.size());
        assertEquals(Duration.ofDays(7), ServerConfig.parseDuration("7d", "x"));
        assertEquals(Duration.ofMillis(250), ServerConfig.parseDuration("250", "x"));
        assertEquals("asl.admin.server.security.proxy.operator-group", ServerConfig.fromEnvironment("ASL_ADMIN_SERVER_SECURITY_PROXY_OPERATOR_GROUP"));
        assertEquals("asl.admin.server.custom.key", ServerConfig.fromEnvironment("ASL_ADMIN_SERVER_CUSTOM_KEY"));
    }

    @Test
    void failsFastOnUnsafeOrInvalidSettings() {
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.of(Map.of("security.mode", "NONE")));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.of(Map.of("secret", "0123456789abcdef-x")));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.of(Map.of("secret", "0123456789abcdef-x", "security.mode", "NONE", "interval", "50ms")));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.of(Map.of("secret", "0123456789abcdef-x", "security.mode", "NONE", "instance.expire-after", "1s")));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.of(Map.of("secret", "0123456789abcdef-x", "security.mode", "NONE", "interval", "soon")));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.of(Map.of("secret", "0123456789abcdef-x", "security.mode", "NONE", "max-body-size", "lots")));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.of(Map.of("secret", "0123456789abcdef-x", "security.mode", "NONE", "port", "70000")));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.of(Map.of("secret", "0123456789abcdef-x", "security.users", "nopassword")));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.of(Map.of("secret", "0123456789abcdef-x", "security.mode", "NONE", "storage.max-size", "1MB")));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.of(Map.of("secret", "0123456789abcdef-x", "security.mode", "NONE", "history.resolution", "500ms")));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.of(Map.of("secret", "0123456789abcdef-x", "security.mode", "NONE", "history.retention", "1s")));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.load(new String[]{"oops"}, Map.of(), new Properties()));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.load(new String[]{"--config=" + directory.resolve("missing")}, Map.of(), new Properties()));
        assertTrue(UiAuth.passwordMatches("{sha256}zz", "x") == false);
    }
}
