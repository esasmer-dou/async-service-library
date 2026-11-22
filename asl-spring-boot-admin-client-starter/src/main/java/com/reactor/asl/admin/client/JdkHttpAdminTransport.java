package com.reactor.asl.admin.client;

import com.reactor.asl.admin.protocol.AslAdminProtocol;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/** {@link AslAdminTransport} on the JDK HTTP client: HTTP/1.1, keep-alive, no extra dependency. */
public final class JdkHttpAdminTransport implements AslAdminTransport {
    /** Replies above this size are refused; a valid reply is a few hundred bytes. */
    static final int MAX_REPLY_BYTES = 1024 * 1024;

    private final HttpClient client;
    private final String baseUrl;
    private final Duration requestTimeout;

    public JdkHttpAdminTransport(String serverUrl, Duration connectTimeout, Duration requestTimeout) {
        Objects.requireNonNull(serverUrl, "serverUrl");
        String trimmed = serverUrl.trim();
        this.baseUrl = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        URI.create(baseUrl);
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Objects.requireNonNull(connectTimeout, "connectTimeout"))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public Response post(String path, byte[] body, String authHeader) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(requestTimeout)
                .header("Content-Type", AslAdminProtocol.CONTENT_TYPE)
                .header(AslAdminProtocol.AUTH_HEADER, authHeader)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        byte[] reply = response.body();
        if (reply != null && reply.length > MAX_REPLY_BYTES) {
            throw new IOException("Admin server reply too large: " + reply.length + " bytes");
        }
        return new Response(response.statusCode(), reply == null ? new byte[0] : reply);
    }
}
