package com.reactor.asl.spring.boot;

import com.reactor.asl.core.AsyncAdminProvider;
import com.reactor.asl.core.AsyncExecutionEngine;
import com.reactor.asl.core.AsyncMethodBinding;
import com.reactor.asl.core.ExecutionMode;
import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.MethodBufferEntryView;
import com.reactor.asl.core.MethodBufferSnapshot;
import com.reactor.asl.core.MethodDescriptor;
import com.reactor.asl.core.MethodRuntime;
import com.reactor.asl.core.MethodRuntimeSnapshot;
import com.reactor.asl.core.ServiceDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AslAdminFacadeTest {
    private static final String SERVICE_ID = "svc";
    private static final String SYNC_METHOD_ID = "sync()";
    private static final String ASYNC_METHOD_ID = "async(java.lang.String)";

    private GovernanceRegistry registry;
    private TrackingAsyncProvider provider;
    private AslAdminProperties properties;
    private AslAdminFacade facade;
    private AslStartupRecoveryState startupRecoveryState;

    @BeforeEach
    void setUp() {
        registry = new GovernanceRegistry();
        registry.register(new ServiceDescriptor(
                SERVICE_ID,
                new MethodDescriptor[]{
                        new MethodDescriptor(SYNC_METHOD_ID, "sync", true, 1, "", false, 0),
                        new MethodDescriptor(ASYNC_METHOD_ID, "async", true, 2, "", true, 1)
                }
        ));
        properties = new AslAdminProperties();
        properties.setPath("/console");
        properties.setApiPath("/console/api");
        properties.setBufferPreviewLimit(1);
        provider = new TrackingAsyncProvider();
        startupRecoveryState = new AslStartupRecoveryState();
        facade = new AslAdminFacade(
                registry,
                properties,
                new AslAdminRuntimeConfiguration(properties),
                startupRecoveryState,
                List.of(provider)
        );
    }

    @Test
    void exposesDashboardPathsAndUnavailableBufferFallback() {
        assertEquals("/console", facade.pagePath());
        assertEquals("/console/api", facade.apiPath());
        assertEquals(1, facade.services().size());
        assertEquals(1, facade.dashboard().size());

        MethodBufferSnapshot unavailable = facade.buffer(SERVICE_ID, SYNC_METHOD_ID);
        assertFalse(unavailable.available());
        assertEquals(0, facade.clearBuffer(SERVICE_ID, SYNC_METHOD_ID));
        assertFalse(facade.deleteBufferEntry(SERVICE_ID, SYNC_METHOD_ID, "missing"));
        assertFalse(facade.replayBufferEntry(SERVICE_ID, SYNC_METHOD_ID, "missing"));
    }

    @Test
    void appliesAsyncProviderAndSupportsControlOperations() {
        registry.attachAsyncExecutionEngine(new NoOpAsyncExecutionEngine());

        MethodRuntimeSnapshot modeSnapshot = facade.switchMode(SERVICE_ID, ASYNC_METHOD_ID, ExecutionMode.ASYNC);
        assertEquals(ExecutionMode.ASYNC, modeSnapshot.executionMode());
        assertEquals(1, provider.applyRuntimeCalls.get());

        MethodRuntimeSnapshot consumerSnapshot = facade.setConsumerThreads(SERVICE_ID, ASYNC_METHOD_ID, 5);
        assertEquals(5, consumerSnapshot.consumerThreads());
        assertEquals(2, provider.applyRuntimeCalls.get());

        MethodBufferSnapshot snapshot = facade.buffer(SERVICE_ID, ASYNC_METHOD_ID);
        assertTrue(snapshot.available());
        assertEquals(1, snapshot.entries().size());
        assertEquals("buffer-1", snapshot.entries().getFirst().entryId());
        assertEquals(1, provider.lastLimit);

        assertEquals(2, facade.clearBuffer(SERVICE_ID, ASYNC_METHOD_ID));
        provider.resetEntries();
        assertTrue(facade.deleteBufferEntry(SERVICE_ID, ASYNC_METHOD_ID, "buffer-1"));
        provider.resetEntries();
        assertTrue(facade.replayBufferEntry(SERVICE_ID, ASYNC_METHOD_ID, "buffer-1"));
    }

    @Test
    void rejectsAsyncSwitchWithoutExecutionEngine() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> facade.switchMode(SERVICE_ID, ASYNC_METHOD_ID, ExecutionMode.ASYNC)
        );

        assertTrue(exception.getMessage().contains("No async execution engine attached"));
    }

    @Test
    void computesDashboardSummaryAndAttention() {
        registry.attachAsyncExecutionEngine(new NoOpAsyncExecutionEngine());
        MethodRuntime asyncRuntime = registry.service(SERVICE_ID).methodById(ASYNC_METHOD_ID);
        asyncRuntime.switchMode(ExecutionMode.ASYNC);
        asyncRuntime.setConsumerThreads(0);

        AslAdminFacade.AdminDashboardSummary summary = facade.summary();

        assertEquals(1, summary.serviceCount());
        assertEquals(2, summary.methodCount());
        assertEquals(2, summary.runningMethodCount());
        assertEquals(0, summary.stoppedMethodCount());
        assertEquals(1, summary.asyncCapableMethodCount());
        assertEquals(1, summary.asyncActiveMethodCount());
        assertEquals(1, summary.methodsWithErrors());
        assertEquals(2, summary.totalQueueDepth());
        assertEquals(1, summary.pendingEntries());
        assertEquals(1, summary.failedEntries());
        assertEquals("HIGH", summary.overallPressure());
        assertFalse(summary.attentionItems().isEmpty());
        assertEquals("HIGH", summary.attentionItems().getFirst().severity());
        assertFalse(summary.storageRecovery().visible());
    }

    @Test
    void keepsOverallPressureLowWhenOnlyInfoAttentionExists() {
        TrackingAsyncProvider infoOnlyProvider = new TrackingAsyncProvider();
        infoOnlyProvider.entries = List.of();
        AslAdminFacade infoFacade = new AslAdminFacade(registry, properties, List.of(infoOnlyProvider));

        AslAdminFacade.AdminDashboardSummary summary = infoFacade.summary();

        assertEquals(0, summary.totalQueueDepth());
        assertEquals(0, summary.failedEntries());
        assertEquals("LOW", summary.overallPressure());
        assertFalse(summary.attentionItems().isEmpty());
        assertEquals("INFO", summary.attentionItems().getFirst().severity());
    }

    @Test
    void surfacesStartupRecoveryNoticeInSummary() {
        startupRecoveryState.record(
                AslStartupRecoveryState.StorageRecoveryNotice.archivedCorruptStore(
                        java.nio.file.Path.of("data", "queue.db"),
                        java.nio.file.Path.of("data", "queue.db.corrupt-123"),
                        java.nio.file.Path.of("data", "queue.db")
                )
        );

        AslAdminFacade.AdminDashboardSummary summary = facade.summary();

        assertTrue(summary.hasStorageRecovery());
        assertEquals("Recovered queue store on startup", summary.storageRecovery().headline());
        assertEquals("Corrupt file archived", summary.storageRecovery().statusLabel());
        assertTrue(summary.storageRecovery().movedToPath().contains("queue.db.corrupt-123"));
    }

    private static final class TrackingAsyncProvider implements AsyncAdminProvider {
        private final AtomicInteger applyRuntimeCalls = new AtomicInteger();
        private List<MethodBufferEntryView> entries = List.of(
                new MethodBufferEntryView("buffer-1", "FAILED", "payload", Instant.parse("2026-03-24T10:00:00Z"), 1, "boom", "java.lang.IllegalStateException", "BUSINESS", "jackson-json-v1", "mail.audit", "v2"),
                new MethodBufferEntryView("buffer-2", "PENDING", "payload-2", Instant.parse("2026-03-24T10:01:00Z"), 0, null, null, null, "jackson-json-v1", "mail.audit", "v2")
        );
        private int lastLimit;

        @Override
        public boolean replay(String serviceId, String methodId, String entryId) {
            return supports(serviceId, methodId) && entries.stream().anyMatch(entry -> entry.entryId().equals(entryId));
        }

        @Override
        public void applyRuntime(MethodRuntime runtime) {
            applyRuntimeCalls.incrementAndGet();
        }

        @Override
        public boolean supports(String serviceId, String methodId) {
            return SERVICE_ID.equals(serviceId) && ASYNC_METHOD_ID.equals(methodId);
        }

        @Override
        public MethodBufferSnapshot snapshot(String serviceId, String methodId, int limit) {
            lastLimit = limit;
            List<MethodBufferEntryView> preview = entries.stream().limit(limit).toList();
            long pendingCount = entries.stream().filter(entry -> "PENDING".equals(entry.state())).count();
            long failedCount = entries.stream().filter(entry -> "FAILED".equals(entry.state())).count();
            long inProgressCount = entries.stream().filter(entry -> "IN_PROGRESS".equals(entry.state())).count();
            return new MethodBufferSnapshot(serviceId, methodId, true, pendingCount, failedCount, inProgressCount, preview);
        }

        @Override
        public int clear(String serviceId, String methodId) {
            int size = entries.size();
            entries = List.of();
            return size;
        }

        @Override
        public boolean delete(String serviceId, String methodId, String entryId) {
            if (!supports(serviceId, methodId)) {
                return false;
            }
            boolean exists = entries.stream().anyMatch(entry -> entry.entryId().equals(entryId));
            if (!exists) {
                return false;
            }
            entries = entries.stream().filter(entry -> !entry.entryId().equals(entryId)).toList();
            return true;
        }

        private void resetEntries() {
            entries = List.of(new MethodBufferEntryView("buffer-1", "FAILED", "payload", Instant.parse("2026-03-24T10:00:00Z"), 1, "boom", "java.lang.IllegalStateException", "BUSINESS", "jackson-json-v1", "mail.audit", "v2"));
        }
    }

    private static final class NoOpAsyncExecutionEngine implements AsyncExecutionEngine {
        @Override
        public void register(MethodRuntime runtime, AsyncMethodBinding binding) {
        }

        @Override
        public void enqueue(MethodRuntime runtime, Object[] arguments) {
        }

        @Override
        public void applyRuntime(MethodRuntime runtime) {
        }

        @Override
        public boolean supports(String serviceId, String methodId) {
            return false;
        }

        @Override
        public MethodBufferSnapshot snapshot(String serviceId, String methodId, int limit) {
            return MethodBufferSnapshot.unavailable(serviceId, methodId);
        }

        @Override
        public int clear(String serviceId, String methodId) {
            return 0;
        }

        @Override
        public boolean delete(String serviceId, String methodId, String entryId) {
            return false;
        }

        @Override
        public boolean replay(String serviceId, String methodId, String entryId) {
            return false;
        }
    }
}
