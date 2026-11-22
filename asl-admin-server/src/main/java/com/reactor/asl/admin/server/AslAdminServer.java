package com.reactor.asl.admin.server;

import com.reactor.asl.admin.protocol.AslAdminCodec;
import com.reactor.asl.admin.protocol.AslAdminMessages.BeatReply;
import com.reactor.asl.admin.protocol.AslAdminMessages.Command;
import com.reactor.asl.admin.protocol.AslAdminProtocol;
import com.reactor.asl.admin.protocol.AslAdminSigner;
import com.reactor.asl.admin.protocol.AslProtocolException;
import com.reactor.asl.admin.server.ServerConfig.Role;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central ASL admin relay on the JDK's built-in HTTP server. It serves the web portal, receives
 * heartbeats and hands settings and commands back in the replies. No framework, no database.
 */
public final class AslAdminServer implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(AslAdminServer.class.getName());
    static final String UI = "/asl-admin";
    private static final String API = UI + "/api";
    private static final String CSP = "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'";

    private final ServerConfig config;
    private final Clock clock;
    private final AslAdminSigner signer;
    private final AdminStore store;
    private final InstanceRegistry registry;
    private final UiAuth auth;
    private final byte[] indexPage;
    private HttpServer http;
    private ExecutorService workers;
    private ScheduledExecutorService maintenance;

    public AslAdminServer(ServerConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
        this.signer = new AslAdminSigner(config.secret, Math.max(1, config.signatureTolerance.toSeconds()));
        this.store = new AdminStore(config);
        this.registry = new InstanceRegistry(config, store, clock.millis());
        this.auth = new UiAuth(config);
        try (InputStream page = AslAdminServer.class.getResourceAsStream(UI + "/index.html")) {
            this.indexPage = page == null ? "ASL admin UI is missing".getBytes(StandardCharsets.UTF_8) : page.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static void main(String[] args) throws Exception {
        ServerConfig config = ServerConfig.load(args, System.getenv(), System.getProperties());
        AslAdminServer server = new AslAdminServer(config, Clock.systemUTC());
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "asl-admin-shutdown"));
        LOG.log(System.Logger.Level.INFO, "ASL admin server listening on {0}:{1,number,#} (UI {2}/)", config.bindAddress, server.port(), UI);
        if (Runtime.getRuntime().maxMemory() > 64L * 1024 * 1024) {
            LOG.log(System.Logger.Level.INFO, "Started with a large heap; asl-admin-server.sh or java @asl-admin-server.jvmopts keeps the footprint minimal");
        }
    }

    public synchronized void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress(config.bindAddress, config.port), 64);
        AtomicInteger threadNumber = new AtomicInteger();
        workers = Executors.newFixedThreadPool(config.threads, runnable -> {
            Thread thread = new Thread(runnable, "asl-admin-http-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        http.setExecutor(workers);
        http.createContext("/asl/v1/", this::ingest);
        http.createContext(UI, this::ui);
        http.createContext("/", exchange -> {
            if ("/".equals(exchange.getRequestURI().getPath())) {
                redirect(exchange, UI + "/index.html");
            } else {
                send(exchange, 404, "text/plain", "Not found");
            }
        });
        maintenance = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "asl-admin-maintenance");
            thread.setDaemon(true);
            return thread;
        });
        long every = Math.max(1_000, registry.intervalMillis() / 3);
        maintenance.scheduleWithFixedDelay(() -> {
            try {
                registry.maintain(clock.millis());
            } catch (RuntimeException exception) {
                LOG.log(System.Logger.Level.WARNING, "ASL admin maintenance failed", exception);
            }
        }, every, every, TimeUnit.MILLISECONDS);
        if (store.historyEnabled()) {
            long step = config.historyResolution.toMillis();
            long firstDelay = step - Math.floorMod(clock.millis(), step);
            maintenance.scheduleAtFixedRate(this::recordHistory, firstDelay, step, TimeUnit.MILLISECONDS);
        }
        if (config.auditEnabled) {
            maintenance.scheduleWithFixedDelay(() -> {
                try {
                    store.trimAudit(clock.millis() - config.auditRetention.toMillis());
                } catch (RuntimeException exception) {
                    LOG.log(System.Logger.Level.WARNING, "ASL admin audit trim failed", exception);
                }
            }, 1, 10, TimeUnit.MINUTES);
        }
        http.start();
    }

    /** Stores the latest raw values of every replica as one history point. */
    void recordHistory() {
        try {
            long now = clock.millis();
            long step = config.historyResolution.toMillis();
            long bucket = now / step;
            store.recordHistory(bucket, bucket * step, registry.historySamples());
        } catch (RuntimeException exception) {
            LOG.log(System.Logger.Level.WARNING, "ASL admin history write failed", exception);
        }
    }

    public int port() {
        return http.getAddress().getPort();
    }

    @Override
    public synchronized void close() {
        if (http != null) {
            http.stop(1);
            http = null;
        }
        if (workers != null) {
            workers.shutdownNow();
        }
        if (maintenance != null) {
            maintenance.shutdownNow();
        }
        store.close();
    }

    // ---- protocol ---------------------------------------------------------------------------

    private void ingest(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendEmpty(exchange, 405);
                return;
            }
            byte[] body = readBody(exchange);
            if (body == null) {
                sendEmpty(exchange, 413);
                return;
            }
            if (!signer.verify(exchange.getRequestHeaders().getFirst(AslAdminProtocol.AUTH_HEADER), body, clock.millis() / 1000L)) {
                sendEmpty(exchange, 401);
                return;
            }
            try {
                if (AslAdminProtocol.REGISTER_PATH.equals(path)) {
                    sendBinary(exchange, AslAdminCodec.encode(registry.register(AslAdminCodec.decodeRegister(body), clock.millis())));
                } else if (AslAdminProtocol.BEAT_PATH.equals(path)) {
                    BeatReply reply = registry.beat(AslAdminCodec.decodeBeat(body), clock.millis());
                    if (reply == null) {
                        sendEmpty(exchange, 410);
                    } else if (reply.isEmpty()) {
                        sendEmpty(exchange, 204);
                    } else {
                        sendBinary(exchange, AslAdminCodec.encode(reply));
                    }
                } else {
                    sendEmpty(exchange, 404);
                }
            } catch (AslProtocolException exception) {
                send(exchange, 400, "text/plain", exception.getMessage());
            } catch (InstanceRegistry.CapacityExceededException exception) {
                send(exchange, 503, "text/plain", exception.getMessage());
            }
        } catch (RuntimeException exception) {
            LOG.log(System.Logger.Level.WARNING, "ASL admin ingest failed", exception);
        }
    }

    // ---- UI ---------------------------------------------------------------------------------

    private void ui(HttpExchange exchange) throws IOException {
        try (exchange) {
            handleUi(exchange);
        }
    }

    private void handleUi(HttpExchange exchange) throws IOException {
        try {
            UiAuth.Principal principal = auth.authenticate(exchange.getRequestHeaders());
            if (principal == null) {
                if (auth.basicMode()) {
                    exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"ASL admin\", charset=\"UTF-8\"");
                }
                send(exchange, 401, "text/plain", "Authentication required");
                return;
            }
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            boolean mutating = !method.equals("GET") && !method.equals("HEAD");
            if (mutating && principal.role() != Role.OPERATOR) {
                sendError(exchange, 403, "OPERATOR role required");
                return;
            }
            if (mutating && auth.isCrossOrigin(exchange.getRequestHeaders())) {
                sendError(exchange, 403, "Cross-origin request rejected");
                return;
            }
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            String path = exchange.getRequestURI().getPath();
            if (path.equals(UI) || path.equals(UI + "/")) {
                redirect(exchange, UI + "/index.html");
            } else if (path.equals(UI + "/index.html")) {
                exchange.getResponseHeaders().set("Content-Security-Policy", CSP);
                sendBytes(exchange, 200, "text/html; charset=utf-8", indexPage);
            } else if (path.startsWith(API + "/")) {
                api(exchange, method, path.substring(API.length()), principal.name());
            } else {
                send(exchange, 404, "text/plain", "Not found");
            }
        } catch (IllegalArgumentException exception) {
            sendError(exchange, 400, exception.getMessage());
        } catch (InstanceRegistry.CapacityExceededException exception) {
            sendError(exchange, 429, exception.getMessage());
        } catch (RuntimeException exception) {
            LOG.log(System.Logger.Level.WARNING, "ASL admin UI request failed", exception);
            sendError(exchange, 500, "Internal error");
        }
    }

    private void api(HttpExchange exchange, String method, String path, String actor) throws IOException {
        long now = clock.millis();
        Map<String, String> query = form(exchange.getRequestURI().getRawQuery());
        if (method.equals("GET") && path.equals("/server")) {
            sendJson(exchange, new Json().beginObject().field("applications", registry.applicationCount())
                    .field("instances", registry.instanceCount()).field("storageMode", config.storageMode.name())
                    .field("storeBytes", store.sizeBytes()).field("historySeries", store.seriesCount())
                    .field("historyPointsPerSeries", store.slots()).field("historyResolutionMillis", config.historyResolution.toMillis())
                    .field("intervalMillis", registry.intervalMillis()).endObject());
            return;
        }
        if (method.equals("GET") && path.equals("/apps")) {
            Json json = new Json();
            registry.writeApplications(json);
            sendJson(exchange, json);
            return;
        }
        if (method.equals("GET") && path.equals("/audit")) {
            int limit = Math.max(1, Math.min(1_000, Integer.parseInt(query.getOrDefault("limit", "100"))));
            Json json = new Json().beginArray();
            for (AdminStore.AuditEntry entry : store.recentAudit(limit)) {
                json.beginObject().field("timeMillis", entry.timeMillis()).field("actor", entry.actor())
                        .field("application", entry.application()).field("action", entry.action()).field("detail", entry.detail()).endObject();
            }
            sendJson(exchange, json.endArray());
            return;
        }
        if (path.startsWith("/apps/")) {
            String rest = path.substring("/apps/".length());
            int slash = rest.indexOf('/');
            String application = slash < 0 ? rest : rest.substring(0, slash);
            String action = slash < 0 ? "" : rest.substring(slash);
            if (method.equals("GET") && action.isEmpty()) {
                registry.watch(application, now);
                Json json = new Json();
                registry.writeApplication(json, application, now);
                sendJson(exchange, json);
                return;
            }
            if (method.equals("GET") && action.equals("/history")) {
                long step = config.historyResolution.toMillis();
                Json json = new Json().beginObject().field("resolutionMillis", step)
                        .name("fields").beginArray().value("state").value("maxConcurrency").value("consumerThreads").value("inFlight")
                        .value("success").value("error").value("rejected").value("queuePending").value("queueFailed").value("queueInProgress").endArray()
                        .name("instances").beginArray();
                for (AdminStore.Series series : store.history(application, key(query), step)) {
                    json.beginObject().field("instance", series.instance()).name("points").beginArray();
                    for (AdminStore.Point point : series.points()) {
                        json.beginArray().value(point.timeMillis());
                        for (long value : point.values()) {
                            json.value(value);
                        }
                        json.endArray();
                    }
                    json.endArray().endObject();
                }
                sendJson(exchange, json.endArray().endObject());
                return;
            }
            if (action.equals("/overrides") && (method.equals("POST") || method.equals("DELETE"))) {
                Map<String, String> form = method.equals("POST") ? form(readForm(exchange)) : query;
                MethodSetting setting = method.equals("DELETE") ? MethodSetting.NONE : new MethodSetting(
                        bool(form.get("enabled")), bool(form.get("async")), integer(form.get("maxConcurrency")), integer(form.get("consumerThreads")));
                registry.setOverride(application, key(form), form.get("instance"), setting, actor, now);
                sendEmpty(exchange, 204);
                return;
            }
            if (action.equals("/commands") && method.equals("POST")) {
                Map<String, String> form = form(readForm(exchange));
                String instance = form.getOrDefault("instance", "");
                List<Long> ids = registry.enqueueCommand(application, instance.isBlank() ? "*" : instance, key(form),
                        commandType(form.get("type")), form.get("entryId"), Math.max(0, integerOrZero(form.get("limit"))), actor, now);
                Json json = new Json().beginObject().name("commandIds").beginArray();
                for (long id : ids) {
                    json.value(id);
                }
                sendJson(exchange, json.endArray().endObject());
                return;
            }
        }
        sendError(exchange, 404, "Not found");
    }

    // ---- helpers ----------------------------------------------------------------------------

    private byte[] readBody(HttpExchange exchange) throws IOException {
        String declared = exchange.getRequestHeaders().getFirst("Content-Length");
        if (declared != null) {
            try {
                if (Long.parseLong(declared.trim()) > config.maxBodyBytes) {
                    return null;
                }
            } catch (NumberFormatException ignored) {
                // fall through to the bounded read
            }
        }
        byte[] body = exchange.getRequestBody().readNBytes(config.maxBodyBytes + 1);
        return body.length > config.maxBodyBytes ? null : body;
    }

    private String readForm(HttpExchange exchange) throws IOException {
        byte[] body = readBody(exchange);
        if (body == null) {
            throw new IllegalArgumentException("Request too large");
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    static Map<String, String> form(String encoded) {
        Map<String, String> result = new HashMap<>();
        if (encoded == null || encoded.isBlank()) {
            return result;
        }
        for (String pair : encoded.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
            String value = equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            result.put(name, value);
        }
        return result;
    }

    private static MethodKey key(Map<String, String> form) {
        String serviceId = form.get("serviceId");
        String methodId = form.get("methodId");
        if (serviceId == null || serviceId.isBlank() || methodId == null || methodId.isBlank()) {
            throw new IllegalArgumentException("serviceId and methodId are required");
        }
        return new MethodKey(serviceId, methodId);
    }

    private static int commandType(String type) {
        return switch (type == null ? "" : type) {
            case "CLEAR_QUEUE" -> Command.CLEAR_QUEUE;
            case "REPLAY_ENTRY" -> Command.REPLAY_ENTRY;
            case "DELETE_ENTRY" -> Command.DELETE_ENTRY;
            case "BROWSE_QUEUE" -> Command.BROWSE_QUEUE;
            case "RESET_OBSERVATIONS" -> Command.RESET_OBSERVATIONS;
            default -> throw new IllegalArgumentException("Unknown command type " + type);
        };
    }

    private static Boolean bool(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!value.equals("true") && !value.equals("false")) {
            throw new IllegalArgumentException("Expected true or false: " + value);
        }
        return Boolean.valueOf(value);
    }

    private static Integer integer(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Expected a number: " + value);
        }
    }

    private static int integerOrZero(String value) {
        Integer parsed = integer(value);
        return parsed == null ? 0 : parsed;
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        sendEmpty(exchange, 302);
    }

    private static void sendJson(HttpExchange exchange, Json json) throws IOException {
        send(exchange, 200, "application/json; charset=utf-8", json.toString());
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", new Json().beginObject().field("error", message).endObject().toString());
    }

    private static void sendBinary(HttpExchange exchange, byte[] body) throws IOException {
        sendBytes(exchange, 200, AslAdminProtocol.CONTENT_TYPE, body);
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        sendBytes(exchange, status, contentType, body == null ? null : body.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendEmpty(HttpExchange exchange, int status) throws IOException {
        sendBytes(exchange, status, null, null);
    }

    private static void sendBytes(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        if (body == null || body.length == 0 || status == 204 || status == 304) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
