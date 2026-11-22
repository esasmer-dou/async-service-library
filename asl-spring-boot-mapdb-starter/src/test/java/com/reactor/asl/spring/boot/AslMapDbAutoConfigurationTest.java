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
}
