package com.reactor.asl.spring.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactor.asl.core.AsyncPayloadCodec;
import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.JavaObjectStreamAsyncPayloadCodec;
import com.reactor.asl.core.RuntimeErrorDetailPolicy;
import com.reactor.asl.mapdb.MapDbCorruptionPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AslPropertiesAndAutoConfigurationTest {
    @Test
    void readsAndWritesConfigurationProperties() {
        AslAsyncMapDbProperties async = new AslAsyncMapDbProperties();
        async.setEnabled(true);
        async.setPath("queue.db");
        async.setCodec("jackson-json");
        async.setWorkerShutdownAwaitMillis(15_000L);
        async.setRegistrationIdleSleepMillis(120L);
        async.setEmptyQueueSleepMillis(55L);
        async.setRequeueDelayMillis(90L);
        async.setRecoveredInProgressMessage("Recovered async work");
        async.setTransactionsEnabled(true);
        async.setMemoryMappedEnabled(false);
        async.setCorruptionPolicy(MapDbCorruptionPolicy.ARCHIVE_AND_RESET);

        assertTrue(async.isEnabled());
        assertEquals("queue.db", async.getPath());
        assertEquals("jackson-json", async.getCodec());
        assertEquals(15_000L, async.getWorkerShutdownAwaitMillis());
        assertEquals(120L, async.getRegistrationIdleSleepMillis());
        assertEquals(55L, async.getEmptyQueueSleepMillis());
        assertEquals(90L, async.getRequeueDelayMillis());
        assertEquals("Recovered async work", async.getRecoveredInProgressMessage());
        assertTrue(async.isTransactionsEnabled());
        assertFalse(async.isMemoryMappedEnabled());
        assertEquals(MapDbCorruptionPolicy.ARCHIVE_AND_RESET, async.getCorruptionPolicy());

        AslRuntimeProperties runtime = new AslRuntimeProperties();
        runtime.setDefaultUnavailableMessage("Service paused by policy");
        runtime.setMaxConcurrencyExceededMessageTemplate("Concurrency cap hit: %d");
        runtime.setMaxLastErrorCharacters(512);
        runtime.setErrorDetailPolicy(RuntimeErrorDetailPolicy.TYPE_AND_MESSAGE);

        assertEquals("Service paused by policy", runtime.getDefaultUnavailableMessage());
        assertEquals("Concurrency cap hit: %d", runtime.getMaxConcurrencyExceededMessageTemplate());
        assertEquals(512, runtime.getMaxLastErrorCharacters());
        assertEquals(RuntimeErrorDetailPolicy.TYPE_AND_MESSAGE, runtime.getErrorDetailPolicy());
    }

    @Test
    void autoConfigurationCreatesAndBacksOffBeans() throws IOException {
        String dbPath = testDataPath("ctx-queue").toString().replace("\\", "/");

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AslCoreAutoConfiguration.class,
                        AslMapDbAutoConfiguration.class
                ))
                .withPropertyValues(
                        "asl.async.mapdb.enabled=true",
                        "asl.async.mapdb.codec=java-object-stream",
                        "asl.async.mapdb.path=" + dbPath
                )
                .run(context -> {
                    assertTrue(context.containsBean("governanceRegistry"));
                    assertTrue(context.containsBean("asyncPayloadCodec"));
                    assertTrue(context.containsBean("mapDbAsyncExecutionEngine"));
                    assertTrue(context.containsBean("aslCoreRuntimeDefaultsApplier"));
                    assertSame(context.getBean(GovernanceRegistry.class), context.getBean("governanceRegistry"));
                    assertTrue(context.getBean(AsyncPayloadCodec.class) instanceof JavaObjectStreamAsyncPayloadCodec);
                });

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AslCoreAutoConfiguration.class,
                        AslMapDbAutoConfiguration.class
                ))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues(
                        "asl.async.mapdb.enabled=true",
                        "asl.async.mapdb.codec=jackson-json",
                        "asl.async.mapdb.path=" + testDataPath("ctx-queue-jackson").toString().replace("\\", "/")
                )
                .run(context -> assertTrue(context.getBean(AsyncPayloadCodec.class) instanceof JacksonJsonAsyncPayloadCodec));

        Path corruptDbPath = testDataPath("ctx-queue-corrupt");
        writeCorruptStore(corruptDbPath);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AslCoreAutoConfiguration.class,
                        AslMapDbAutoConfiguration.class
                ))
                .withPropertyValues(
                        "asl.async.mapdb.enabled=true",
                        "asl.async.mapdb.codec=java-object-stream",
                        "asl.async.mapdb.path=" + corruptDbPath.toString().replace("\\", "/"),
                        "asl.async.mapdb.corruption-policy=ARCHIVE_AND_RESET"
                )
                .run(context -> {
                    assertTrue(context.containsBean("mapDbAsyncExecutionEngine"));
                    assertTrue(context.isRunning());
                });
    }

    private static void writeCorruptStore(Path path) throws IOException {
        Files.write(path, new byte[]{1, 2, 3, 4, 5});
    }

    private static Path testDataPath(String prefix) throws IOException {
        Path dir = Path.of("target", "test-data");
        Files.createDirectories(dir);
        return dir.resolve(prefix + "-" + UUID.randomUUID() + ".db");
    }
}
