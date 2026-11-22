package com.reactor.asl.spring.boot;

import com.reactor.asl.mapdb.MapDbAsyncExecutionEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AslMapDbMetricsBinderTest {
    @TempDir
    Path tempDir;

    @Test
    void publishesBoundedWriterAndSharedWorkerHealth() {
        AslObservabilityProperties properties = new AslObservabilityProperties();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        try (MapDbAsyncExecutionEngine engine = new MapDbAsyncExecutionEngine(tempDir.resolve("metrics.db"))) {
            new AslMapDbMetricsBinder(engine, meterRegistry, properties);

            assertNotNull(meterRegistry.find("asl.mapdb.writer.queue.depth").gauge());
            assertNotNull(meterRegistry.find("asl.mapdb.writer.commit.average.nanos").gauge());
            assertNotNull(meterRegistry.find("asl.mapdb.worker.active").gauge());
            assertNotNull(meterRegistry.find("asl.mapdb.projection.version").gauge());
            assertEquals(1.0d, meterRegistry.find("asl.mapdb.engine.healthy").gauge().value());
            assertEquals(1.0d, meterRegistry.find("asl.mapdb.writer.accepting").gauge().value());
        } finally {
            meterRegistry.close();
        }
    }
}
