package com.reactor.asl.admin.server;

import com.reactor.asl.admin.client.AslAdminClient;
import com.reactor.asl.admin.client.AslAdminClientSettings;
import com.reactor.asl.admin.client.JdkHttpAdminTransport;
import com.reactor.asl.admin.protocol.AslAdminCodec;
import com.reactor.asl.admin.protocol.AslAdminMessages.Beat;
import com.reactor.asl.admin.protocol.AslAdminProtocol;
import com.reactor.asl.admin.protocol.AslAdminSigner;
import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.MethodDescriptor;
import com.reactor.asl.core.MethodRuntime;
import com.reactor.asl.core.ServiceDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real server on the JDK HTTP server, real client, real sockets. */
class AslAdminServerTest {
    private static final String SECRET = "e2e-shared-secret-0123456789";
    private static final String ADMIN = "admin:admin-pass";
    private static final String VIEWER = "viewer:viewer-pass";

    @TempDir
    Path directory;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private AslAdminServer server;
    private AslAdminClient client;
    private MethodRuntime create;
    private String base;

    @BeforeEach
    void start() throws Exception {
        String viewerHash = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest("viewer-pass".getBytes(StandardCharsets.UTF_8)));
        ServerConfig config = ServerConfig.of(Map.of(
                "secret", SECRET, "port", "0", "bind-address", "127.0.0.1",
                "interval", "300ms", "watch.interval", "200ms", "instance.expire-after", "5s",
                "storage.path", directory.resolve("asl-admin.db").toString(),
                "history.resolution", "1s", "history.retention", "30s",
                "security.users", "admin:admin-pass:OPERATOR,viewer:{sha256}" + viewerHash + ":VIEWER"));
        server = new AslAdminServer(config, Clock.systemUTC());
        server.start();
        base = "http://127.0.0.1:" + server.port();

        GovernanceRegistry registry = new GovernanceRegistry();
        registry.register(new ServiceDescriptor("OrderService", new MethodDescriptor[]{
                new MethodDescriptor("create(java.lang.String)", "create", true, 10, "busy", true, 0),
                new MethodDescriptor("find(long)", "find", true, 5, "busy", false, 0)
        }));
        create = registry.service("OrderService").methodById("create(java.lang.String)");
        client = new AslAdminClient(new AslAdminClientSettings("orders", "orders-1", "e2e", 300, 2_000, true, 20, 200),
                registry, List.of(), new JdkHttpAdminTransport(base, Duration.ofSeconds(1), Duration.ofSeconds(2)), new AslAdminSigner(SECRET));
        client.start();
    }

    @AfterEach
    void stop() {
        client.close();
        server.close();
    }

    @Test
    void applicationIsTunedCentrallyThroughTheRelay() throws Exception {
        await(() -> get("/asl-admin/api/apps", ADMIN).contains("\"instances\":1"));
        String app = get("/asl-admin/api/apps/orders", ADMIN);
        assertTrue(app.contains("\"name\":\"orders-1\""), app);

        assertEquals(204, send("POST", "/asl-admin/api/apps/orders/overrides", ADMIN, null,
                form(Map.of("serviceId", "OrderService", "methodId", "create(java.lang.String)", "maxConcurrency", "33"))).statusCode());
        await(() -> create.policy().maxConcurrency() == 33);
        await(() -> get("/asl-admin/api/apps/orders", ADMIN).contains("\"inSync\":true"));
        assertTrue(Files.size(directory.resolve("asl-admin.db")) >= AdminStore.MIN_FILE_BYTES);

        await(() -> get("/asl-admin/api/apps/orders/history?serviceId=OrderService&methodId="
                + URLEncoder.encode("create(java.lang.String)", StandardCharsets.UTF_8), ADMIN).contains("\"instance\":\"orders-1\",\"points\":[["));
        String history = get("/asl-admin/api/apps/orders/history?serviceId=OrderService&methodId="
                + URLEncoder.encode("create(java.lang.String)", StandardCharsets.UTF_8), ADMIN);
        assertTrue(history.contains("\"resolutionMillis\":1000"), history);

        assertEquals(204, send("POST", "/asl-admin/api/apps/orders/overrides", ADMIN, null,
                form(Map.of("serviceId", "OrderService", "methodId", "create(java.lang.String)", "instance", "orders-1", "maxConcurrency", "7"))).statusCode());
        await(() -> create.policy().maxConcurrency() == 7);

        HttpResponse<String> command = send("POST", "/asl-admin/api/apps/orders/commands", ADMIN, null,
                form(Map.of("serviceId", "OrderService", "methodId", "create(java.lang.String)", "instance", "orders-1", "type", "RESET_OBSERVATIONS")));
        assertEquals(200, command.statusCode());
        assertTrue(command.body().contains("commandIds"));
        await(() -> get("/asl-admin/api/apps/orders", ADMIN).contains("\"status\":\"OK\""));

        assertEquals(204, send("DELETE", "/asl-admin/api/apps/orders/overrides?serviceId=OrderService&methodId="
                + URLEncoder.encode("create(java.lang.String)", StandardCharsets.UTF_8) + "&instance=orders-1", ADMIN, null, null).statusCode());
        await(() -> create.policy().maxConcurrency() == 33);
        assertEquals(204, send("DELETE", "/asl-admin/api/apps/orders/overrides?serviceId=OrderService&methodId="
                + URLEncoder.encode("create(java.lang.String)", StandardCharsets.UTF_8), ADMIN, null, null).statusCode());
        await(() -> create.policy().maxConcurrency() == 10);

        String auditJson = get("/asl-admin/api/audit?limit=10", ADMIN);
        assertTrue(auditJson.contains("override.set") && auditJson.contains("override.clear") && auditJson.contains("command.RESET_OBSERVATIONS"), auditJson);
        String serverInfo = get("/asl-admin/api/server", ADMIN);
        assertTrue(serverInfo.contains("\"storageMode\":\"FILE\"") && serverInfo.contains("\"historyPointsPerSeries\":30"), serverInfo);

        assertEquals(400, send("POST", "/asl-admin/api/apps/orders/overrides", ADMIN, null,
                form(Map.of("serviceId", "OrderService", "methodId", "find(long)", "async", "true"))).statusCode());
        assertEquals(400, send("POST", "/asl-admin/api/apps/orders/overrides", ADMIN, null,
                form(Map.of("serviceId", "OrderService", "methodId", "find(long)", "maxConcurrency", "abc"))).statusCode());
        assertEquals(400, send("POST", "/asl-admin/api/apps/orders/commands", ADMIN, null,
                form(Map.of("serviceId", "OrderService", "methodId", "find(long)", "type", "NOPE"))).statusCode());
        assertEquals(404, send("GET", "/asl-admin/api/nothing", ADMIN, null, null).statusCode());

        client.close();
        await(() -> get("/asl-admin/api/apps", ADMIN).equals("[]"));
    }

    @Test
    void portalIsProtectedAndChangesNeedOperatorAndSameOrigin() throws Exception {
        assertEquals(401, send("GET", "/asl-admin/api/apps", null, null, null).statusCode());
        assertEquals(401, send("GET", "/asl-admin/index.html", "admin:wrong", null, null).statusCode());
        assertEquals(200, send("GET", "/asl-admin/api/apps", VIEWER, null, null).statusCode());
        HttpResponse<String> page = send("GET", "/asl-admin/index.html", ADMIN, null, null);
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("ASL Admin"));
        assertEquals("DENY", page.headers().firstValue("X-Frame-Options").orElse(""));
        assertTrue(page.headers().firstValue("Content-Security-Policy").orElse("").contains("frame-ancestors 'none'"));
        assertEquals(302, send("GET", "/", null, null, null).statusCode());
        assertEquals(302, send("GET", "/asl-admin/", ADMIN, null, null).statusCode());
        assertEquals(404, send("GET", "/other", null, null, null).statusCode());
        int encoded = send("GET", "/%61sl-admin/api/apps", null, null, null).statusCode();
        assertTrue(encoded == 401 || encoded == 404, "encoded path must not bypass login: " + encoded);

        await(() -> get("/asl-admin/api/apps", ADMIN).contains("orders"));
        String body = form(Map.of("serviceId", "OrderService", "methodId", "find(long)", "enabled", "false"));
        assertEquals(403, send("POST", "/asl-admin/api/apps/orders/overrides", VIEWER, null, body).statusCode());
        assertEquals(403, send("POST", "/asl-admin/api/apps/orders/overrides", ADMIN, "https://evil.example", body).statusCode());
        assertEquals(204, send("POST", "/asl-admin/api/apps/orders/overrides", ADMIN, base, body).statusCode());
    }

    @Test
    void ingestRejectsUnsignedMalformedOversizedAndUnknownSessions() throws Exception {
        AslAdminSigner signer = new AslAdminSigner(SECRET);
        long now = System.currentTimeMillis() / 1000;
        byte[] beat = AslAdminCodec.encode(new Beat(987654, 1, 0, 0, null, List.of(), List.of()));
        assertEquals(401, ingest(beat, "t=1,s=AAAA").statusCode());
        assertEquals(401, ingest(beat, new AslAdminSigner("some-other-secret-0123").sign(beat, now)).statusCode());
        assertEquals(410, ingest(beat, signer.sign(beat, now)).statusCode());
        byte[] garbage = {1, 3, (byte) 0xFF};
        assertEquals(400, ingest(garbage, signer.sign(garbage, now)).statusCode());
        byte[] huge = new byte[70 * 1024];
        assertEquals(413, ingest(huge, signer.sign(huge, now)).statusCode());
        HttpResponse<String> getOnIngest = send("GET", AslAdminProtocol.BEAT_PATH, null, null, null);
        assertEquals(405, getOnIngest.statusCode());
    }

    private HttpResponse<byte[]> ingest(byte[] body, String auth) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + AslAdminProtocol.BEAT_PATH))
                .header("Content-Type", AslAdminProtocol.CONTENT_TYPE)
                .header(AslAdminProtocol.AUTH_HEADER, auth)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private String get(String path, String credentials) {
        try {
            HttpResponse<String> response = send("GET", path, credentials, null, null);
            if (response.statusCode() != 200) {
                throw new IllegalStateException(path + " -> " + response.statusCode());
            }
            return response.body();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private HttpResponse<String> send(String method, String path, String credentials, String origin, String formBody) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path.replace("(", "%28").replace(")", "%29")))
                .method(method, formBody == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(formBody));
        if (formBody != null) {
            builder.header("Content-Type", "application/x-www-form-urlencoded");
        }
        if (credentials != null) {
            builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        }
        if (origin != null) {
            builder.header("Origin", origin);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String form(Map<String, String> values) {
        StringBuilder out = new StringBuilder();
        values.forEach((key, value) -> out.append(out.isEmpty() ? "" : "&")
                .append(URLEncoder.encode(key, StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return out.toString();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // not ready yet
            }
            Thread.sleep(50);
        }
        throw new AssertionError("condition not met within 10s");
    }
}
