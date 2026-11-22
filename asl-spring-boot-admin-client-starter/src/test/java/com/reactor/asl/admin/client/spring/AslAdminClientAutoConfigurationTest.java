package com.reactor.asl.admin.client.spring;

import com.reactor.asl.admin.client.AslAdminClient;
import com.reactor.asl.spring.boot.AslCoreAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AslAdminClientAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AslCoreAutoConfiguration.class, AslAdminClientAutoConfiguration.class));

    @Test
    void disabledByDefault() {
        runner.run(context -> assertEquals(0, context.getBeanNamesForType(AslAdminClient.class).length));
    }

    @Test
    void startsWhenEnabledAndConfigured() {
        runner.withPropertyValues(
                "asl.admin.client.enabled=true",
                "asl.admin.client.server-url=http://127.0.0.1:1/",
                "asl.admin.client.secret=0123456789abcdef-secret",
                "spring.application.name=orders"
        ).run(context -> {
            assertNotNull(context.getBean(AslAdminClient.class));
            assertEquals(15_000, context.getBean(AslAdminClient.class).currentIntervalMillis());
        });
    }

    @Test
    void failsFastWithoutServerUrlOrSecret() {
        runner.withPropertyValues("asl.admin.client.enabled=true", "asl.admin.client.secret=0123456789abcdef-secret")
                .run(context -> assertTrue(rootMessage(context.getStartupFailure()).contains("server-url")));
        runner.withPropertyValues("asl.admin.client.enabled=true", "asl.admin.client.server-url=http://localhost:9")
                .run(context -> assertTrue(rootMessage(context.getStartupFailure()).contains("secret")));
        runner.withPropertyValues("asl.admin.client.enabled=true", "asl.admin.client.server-url=http://localhost:9",
                        "asl.admin.client.secret=short")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return String.valueOf(current.getMessage());
    }
}
