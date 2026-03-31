package com.reactor.asl.sample;

import com.reactor.asl.core.ExecutionMode;
import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.MethodStatsSnapshot;
import com.reactor.asl.core.MethodUnavailableException;
import com.reactor.asl.core.ServiceRuntime;
import com.reactor.asl.mapdb.MapDbAsyncExecutionEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedWrapperTest {
    @Test
    void wrapsAnnotatedInterfaceWithoutReflection() {
        GovernanceRegistry registry = new GovernanceRegistry();
        SampleService service = SampleServiceAsl.wrap(new SampleServiceImpl(), registry);

        assertEquals("echo:test", service.echo("test"));

        ServiceRuntime runtime = registry.service(SampleServiceAsl.SERVICE_ID);
        MethodStatsSnapshot stats = runtime.methodByName("echo").stats();
        assertEquals(1, stats.successCount());
        assertEquals(0, stats.errorCount());
        assertEquals(0, stats.rejectedCount());
    }

    @Test
    void canDisableMethodAtRuntime() {
        GovernanceRegistry registry = new GovernanceRegistry();
        SampleService service = SampleServiceAsl.wrap(new SampleServiceImpl(), registry);
        ServiceRuntime runtime = registry.service(SampleServiceAsl.SERVICE_ID);

        runtime.methodByName("echo").disable("faucet closed");

        MethodUnavailableException exception = assertThrows(MethodUnavailableException.class, () -> service.echo("x"));
        assertTrue(exception.getMessage().contains("faucet closed"));
        assertEquals("UP", service.health());
    }

    @Test
    void rejectsWhenMaxConcurrencyIsReached() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        SampleService delegate = new SampleService() {
            @Override
            public String echo(String value) {
                return value;
            }

            @Override
            public String slow(String value) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timeout waiting release");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return value;
            }

            @Override
            public void publish(String value) {
            }

            @Override
            public String health() {
                return "UP";
            }
        };

        GovernanceRegistry registry = new GovernanceRegistry();
        SampleService service = SampleServiceAsl.wrap(delegate, registry);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> service.slow("first"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            Future<String> second = executor.submit(() -> service.slow("second"));
            ExecutionException executionException = assertThrows(ExecutionException.class, second::get);
            assertInstanceOf(MethodUnavailableException.class, executionException.getCause());

            release.countDown();
            assertEquals("first", first.get(5, TimeUnit.SECONDS));

            MethodStatsSnapshot stats = registry.service(SampleServiceAsl.SERVICE_ID).methodByName("slow").stats();
            assertEquals(1, stats.successCount());
            assertEquals(1, stats.rejectedCount());
            assertEquals(0, stats.errorCount());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void dispatchesAsyncCapableVoidMethodThroughMapDbQueue() throws Exception {
        Path dbDir = Files.createTempDirectory("asl-sample-async");
        Path dbFile = dbDir.resolve("queue.db");
        CountDownLatch published = new CountDownLatch(1);
        CopyOnWriteArrayList<String> publishedValues = new CopyOnWriteArrayList<>();

        SampleService delegate = new SampleService() {
            @Override
            public String echo(String value) {
                return value;
            }

            @Override
            public String slow(String value) {
                return value;
            }

            @Override
            public void publish(String value) {
                publishedValues.add(value);
                published.countDown();
            }

            @Override
            public String health() {
                return "UP";
            }
        };

        GovernanceRegistry registry = new GovernanceRegistry();
        try (MapDbAsyncExecutionEngine engine = new MapDbAsyncExecutionEngine(dbFile)) {
            registry.attachAsyncExecutionEngine(engine);
            SampleService service = SampleServiceAsl.wrap(delegate, registry);

            ServiceRuntime runtime = registry.service(SampleServiceAsl.SERVICE_ID);
            var publishRuntime = runtime.methodByName("publish");
            publishRuntime.switchMode(ExecutionMode.ASYNC);
            publishRuntime.setConsumerThreads(1);
            engine.applyRuntime(publishRuntime);

            service.publish("queued-message");

            assertTrue(published.await(5, TimeUnit.SECONDS));
            assertEquals(1, publishedValues.size());
            assertEquals("queued-message", publishedValues.getFirst());
            MethodStatsSnapshot stats = publishRuntime.stats();
            assertEquals(1, stats.successCount());
            assertEquals(0, stats.errorCount());
            assertEquals(0, stats.rejectedCount());
        }
    }
}
