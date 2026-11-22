package com.reactor.asl.spring.boot;

import com.reactor.asl.core.AsyncPayloadCodec;
import com.reactor.asl.mapdb.MapDbAsyncExecutionEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AslMapDbAutoConfigurationTest {
    @TempDir
    Path tempDir;

    @Test
    void createsDefaultJacksonCodecAfterBootObjectMapperAutoConfiguration() {
        String path = tempDir.resolve("default-codec.db").toString().replace('\\', '/');

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        JacksonAutoConfiguration.class,
                        AslCoreAutoConfiguration.class,
                        AslMapDbAutoConfiguration.class
                ))
                .withPropertyValues(
                        "asl.async.mapdb.enabled=true",
                        "asl.async.mapdb.path=" + path
                )
                .run(context -> {
                    assertTrue(context.isRunning());
                    assertInstanceOf(JacksonJsonAsyncPayloadCodec.class, context.getBean(AsyncPayloadCodec.class));
                    assertTrue(context.containsBean("mapDbAsyncExecutionEngine"));
                    assertInstanceOf(
                            MapDbAsyncExecutionEngine.class,
                            context.getBean("mapDbAsyncExecutionEngine")
                    );
                });
    }

    @Test
    void laneLimitKeysFromYamlNeedBrackets() {
        String path = tempDir.resolve("lanes.db").toString().replace('\\', '/');
        String bracketed = """
                asl:
                  async:
                    mapdb:
                      services:
                        "[mail.service]":
                          methods:
                            "[send(java.lang.String)]":
                              max-entries: 100
                """;
        runner(path).withInitializer(yaml(bracketed)).run(context -> assertNull(context.getStartupFailure()));

        String plain = bracketed.replace("\"[mail.service]\"", "\"mail.service\"").replace("\"[send(java.lang.String)]\"", "\"send(java.lang.String)\"");
        runner(path).withInitializer(yaml(plain)).run(context -> {
            assertNotNull(context.getStartupFailure());
            Throwable cause = context.getStartupFailure();
            while (cause.getCause() != null && !(cause instanceof IllegalStateException && cause.getMessage().contains("brackets"))) {
                cause = cause.getCause();
            }
            assertTrue(cause.getMessage().contains("brackets"), String.valueOf(context.getStartupFailure()));
        });
    }

    private static ApplicationContextRunner runner(String path) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        JacksonAutoConfiguration.class,
                        AslCoreAutoConfiguration.class,
                        AslMapDbAutoConfiguration.class
                ))
                .withPropertyValues("asl.async.mapdb.enabled=true", "asl.async.mapdb.path=" + path);
    }

    private static org.springframework.context.ApplicationContextInitializer<org.springframework.context.ConfigurableApplicationContext> yaml(String text) {
        return context -> {
            try {
                for (var source : new org.springframework.boot.env.YamlPropertySourceLoader()
                        .load("test.yml", new org.springframework.core.io.ByteArrayResource(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)))) {
                    context.getEnvironment().getPropertySources().addFirst(source);
                }
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        };
    }
}
