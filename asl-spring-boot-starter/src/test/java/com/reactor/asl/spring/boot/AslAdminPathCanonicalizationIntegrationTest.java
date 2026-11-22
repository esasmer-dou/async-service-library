package com.reactor.asl.spring.boot;

import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.MethodDescriptor;
import com.reactor.asl.core.ServiceDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for admin authorization bypass through non-canonical URL spellings.
 * Runs against a real servlet container because MockMvc does not reproduce Tomcat URI handling.
 */
@SpringBootTest(
        classes = AslAdminPathCanonicalizationIntegrationTest.DenyAllApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "asl.admin.enabled=true"
)
class AslAdminPathCanonicalizationIntegrationTest {
    private static final String SERVICE_ID = "guarded.service";
    private static final String METHOD_ID = "run()";
    private static final List<AslAdminAuditEvent> AUDIT = new CopyOnWriteArrayList<>();

    @LocalServerPort
    private int port;

    @Autowired
    private GovernanceRegistry registry;

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void reset() {
        registry.service(SERVICE_ID).methodById(METHOD_ID).enable();
        AUDIT.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/asl/api/services/guarded.service/methods/run%28%29/disable",
            "/asl;x=1/api/services/guarded.service/methods/run%28%29/disable",
            "/asl/api;x=1/services/guarded.service/methods/run%28%29/disable",
            "/%61sl/api/services/guarded.service/methods/run%28%29/disable",
            "/asl/%61pi/services/guarded.service/methods/run%28%29/disable",
            "//asl/api/services/guarded.service/methods/run%28%29/disable",
            "/asl//api/services/guarded.service/methods/run%28%29/disable",
            "/asl/services/guarded.service/methods/run%28%29/disable",
            "/%61sl/services/guarded.service/methods/run%28%29/disable",
            "/asl;jsessionid=abc/services/guarded.service/methods/run%28%29/disable"
    })
    void deniesAdminMutationForEveryUrlSpelling(String path) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertTrue(
                response.statusCode() == 401 || response.statusCode() == 403 || response.statusCode() == 404
                        || response.statusCode() == 400,
                () -> path + " returned " + response.statusCode()
        );
        assertTrue(
                registry.service(SERVICE_ID).methodById(METHOD_ID).policy().enabled(),
                () -> path + " mutated the method without authorization"
        );
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            assertFalse(AUDIT.isEmpty(), () -> path + " was denied without an audit event");
            assertFalse(AUDIT.getFirst().authorized());
        }
    }

    @SpringBootApplication
    static class DenyAllApplication {
        @Bean
        GovernanceRegistry governanceRegistry() {
            GovernanceRegistry registry = new GovernanceRegistry();
            registry.register(new ServiceDescriptor(SERVICE_ID, new MethodDescriptor[]{
                    new MethodDescriptor(METHOD_ID, "run", true, 4, "", false, 0)
            }));
            return registry;
        }

        @Bean
        AslAdminAccessPolicy denyAll() {
            return request -> false;
        }

        @Bean
        AslAdminAuditSink recordingAuditSink() {
            return AUDIT::add;
        }
    }
}
