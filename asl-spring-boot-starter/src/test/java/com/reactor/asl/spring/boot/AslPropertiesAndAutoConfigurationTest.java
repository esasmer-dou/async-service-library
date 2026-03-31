package com.reactor.asl.spring.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactor.asl.core.AsyncExecutionEngine;
import com.reactor.asl.core.AsyncMethodBinding;
import com.reactor.asl.core.AsyncPayloadCodec;
import com.reactor.asl.core.ExecutionMode;
import com.reactor.asl.core.BufferAdminProvider;
import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.JavaObjectStreamAsyncPayloadCodec;
import com.reactor.asl.core.MethodBufferSnapshot;
import com.reactor.asl.core.MethodDescriptor;
import com.reactor.asl.core.MethodRuntime;
import com.reactor.asl.core.ServiceDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AslPropertiesAndAutoConfigurationTest {
    @Test
    void readsAndWritesConfigurationProperties() {
        AslAdminProperties admin = new AslAdminProperties();
        admin.setEnabled(false);
        admin.setPath("/ops");
        admin.setApiPath("/ops/api");
        admin.setBufferPreviewLimit(7);
        admin.getUi().setHeroTitle("Reactor Control");
        AslAdminProperties.ServiceConfiguration service = new AslAdminProperties.ServiceConfiguration();
        AslAdminProperties.MethodConfiguration method = new AslAdminProperties.MethodConfiguration();
        method.setEnabled(false);
        method.setMaxConcurrency(9);
        method.setExecutionMode(ExecutionMode.ASYNC);
        service.getMethods().put("send(java.lang.String)", method);
        admin.getServices().put("mail.service", service);

        assertFalse(admin.isEnabled());
        assertEquals("/ops", admin.getPath());
        assertEquals("/ops/api", admin.getApiPath());
        assertEquals(7, admin.getBufferPreviewLimit());
        assertEquals("Reactor Control", admin.getUi().getHeroTitle());
        assertEquals("Services", admin.getUi().getServicesTitle());
        assertEquals("Search services", admin.getUi().getServiceSearchPlaceholder());
        assertEquals("All", admin.getUi().getAllLabel());
        assertEquals("Load Overview", admin.getUi().getLoadOverviewTitle());
        assertEquals("Live Refresh", admin.getUi().getLiveRefreshLabel());
        assertEquals("Refresh Interval", admin.getUi().getRefreshIntervalLabel());
        assertEquals("Refresh Buffer", admin.getUi().getRefreshBufferLabel());
        assertEquals("Top", admin.getUi().getScrollTopLabel());
        assertEquals("Bottom", admin.getUi().getScrollBottomLabel());
        assertEquals(8, admin.getDashboard().getAttentionLimit());
        assertEquals(40, admin.getDashboard().getMediumUtilizationPercent());
        assertEquals(80, admin.getDashboard().getHighUtilizationPercent());
        assertTrue(admin.getDashboard().getRefresh().isLiveRefreshEnabled());
        assertTrue(admin.getDashboard().getRefresh().isLiveBufferEnabled());
        assertEquals(5_000, admin.getDashboard().getRefresh().getDefaultIntervalMs());
        assertEquals(List.of(3000, 5000, 10000, 30000), admin.getDashboard().getRefresh().getIntervalOptionsMs());
        assertEquals(1400, admin.getDashboard().getRefresh().getChangeFlashMs());
        assertEquals(1600, admin.getDashboard().getRefresh().getSuccessMessageAutoHideMs());
        assertEquals(3200, admin.getDashboard().getRefresh().getErrorMessageAutoHideMs());
        assertFalse(admin.getServices().get("mail.service").getMethods().get("send(java.lang.String)").getEnabled());
        assertEquals(9, admin.getServices().get("mail.service").getMethods().get("send(java.lang.String)").getMaxConcurrency());
        assertEquals(ExecutionMode.ASYNC, admin.getServices().get("mail.service").getMethods().get("send(java.lang.String)").getExecutionMode());

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
        async.setResetIfCorrupt(true);

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
        assertTrue(async.isResetIfCorrupt());

        AslRuntimeProperties runtime = new AslRuntimeProperties();
        runtime.setDefaultUnavailableMessage("Service paused by policy");
        runtime.setMaxConcurrencyExceededMessageTemplate("Concurrency cap hit: %d");

        assertEquals("Service paused by policy", runtime.getDefaultUnavailableMessage());
        assertEquals("Concurrency cap hit: %d", runtime.getMaxConcurrencyExceededMessageTemplate());
    }

    @Test
    void autoConfigurationCreatesAndBacksOffBeans() throws IOException {
        String dbPath = testDataPath("ctx-queue").toString().replace("\\", "/");

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AslCoreAutoConfiguration.class))
                .withPropertyValues(
                        "asl.async.mapdb.enabled=true",
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
                .withConfiguration(AutoConfigurations.of(AslCoreAutoConfiguration.class))
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
                .withConfiguration(AutoConfigurations.of(AslCoreAutoConfiguration.class))
                .withPropertyValues(
                        "asl.async.mapdb.enabled=true",
                        "asl.async.mapdb.path=" + corruptDbPath.toString().replace("\\", "/"),
                        "asl.async.mapdb.reset-if-corrupt=true"
                )
                .run(context -> {
                    assertTrue(context.containsBean("mapDbAsyncExecutionEngine"));
                    assertTrue(context.isRunning());
                });

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AslAdminAutoConfiguration.class))
                .withBean(GovernanceRegistry.class, GovernanceRegistry::new)
                .withBean(BufferAdminProvider.class, () -> new BufferAdminProvider() {
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
                })
                .withPropertyValues("asl.admin.enabled=true")
                .run(context -> {
                    assertTrue(context.containsBean("aslAdminFacade"));
                    assertTrue(context.containsBean("aslAdminRuntimeConfiguration"));
                    assertTrue(context.containsBean("aslAdminStartupConfigurationApplier"));
                    assertTrue(context.containsBean("aslAdminRestController"));
                    assertTrue(context.containsBean("aslAdminPageController"));
                    assertNotNull(context.getBean(AslAdminFacade.class));
                });

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AslCoreAutoConfiguration.class, AslAdminAutoConfiguration.class))
                .withBean(GovernanceRegistry.class, GovernanceRegistry::new)
                .withBean(AslAdminFacade.class, () -> new AslAdminFacade(new GovernanceRegistry(), new AslAdminProperties(), List.of()))
                .withPropertyValues("asl.admin.enabled=false")
                .run(context -> {
                    assertTrue(context.containsBean("governanceRegistry"));
                    assertFalse(context.containsBean("aslAdminRestController"));
                    assertFalse(context.containsBean("aslAdminPageController"));
                    assertEquals(1, context.getBeansOfType(AslAdminFacade.class).size());
                });
    }

    @Test
    void appliesStartupMethodOverridesFromConfigurationProperties() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AslAdminAutoConfiguration.class))
                .withBean(GovernanceRegistry.class, () -> {
                    GovernanceRegistry registry = new GovernanceRegistry();
                    registry.register(new ServiceDescriptor(
                            "demo.service",
                            new MethodDescriptor[]{
                                    new MethodDescriptor("process(java.lang.String)", "process", true, 3, "Method is disabled", false, 0),
                                    new MethodDescriptor("publish(java.lang.String)", "publish", true, 1, "Publish is disabled", true, 1)
                            }
                    ));
                    registry.attachAsyncExecutionEngine(new NoOpAsyncEngine());
                    return registry;
                })
                .withBean(BufferAdminProvider.class, () -> new NoOpAsyncEngine())
                .withPropertyValues(
                        "asl.admin.enabled=true",
                        "asl.admin.ui.hero-title=Reactor Admin Console",
                        "asl.admin.buffer-preview-limit=11",
                        "asl.admin.dashboard.attention-limit=5",
                        "asl.admin.dashboard.medium-utilization-percent=35",
                        "asl.admin.dashboard.high-utilization-percent=75",
                        "asl.admin.dashboard.refresh.live-refresh-enabled=false",
                        "asl.admin.dashboard.refresh.live-buffer-enabled=false",
                        "asl.admin.dashboard.refresh.default-interval-ms=10000",
                        "asl.admin.dashboard.refresh.interval-options-ms[0]=5000",
                        "asl.admin.dashboard.refresh.interval-options-ms[1]=10000",
                        "asl.admin.dashboard.refresh.change-flash-ms=900",
                        "asl.admin.dashboard.refresh.success-message-auto-hide-ms=1800",
                        "asl.admin.dashboard.refresh.error-message-auto-hide-ms=3600",
                        "asl.admin.services[demo.service].methods[process(java.lang.String)].enabled=false",
                        "asl.admin.services[demo.service].methods[process(java.lang.String)].max-concurrency=9",
                        "asl.admin.services[demo.service].methods[process(java.lang.String)].unavailable-message=temporarily-closed",
                        "asl.admin.services[demo.service].methods[publish(java.lang.String)].execution-mode=ASYNC",
                        "asl.admin.services[demo.service].methods[publish(java.lang.String)].consumer-threads=4"
                )
                .run(context -> {
                    GovernanceRegistry registry = context.getBean(GovernanceRegistry.class);
                    assertFalse(registry.service("demo.service").methodById("process(java.lang.String)").snapshot().enabled());
                    assertEquals(9, registry.service("demo.service").methodById("process(java.lang.String)").snapshot().maxConcurrency());
                    assertEquals("temporarily-closed", registry.service("demo.service").methodById("process(java.lang.String)").snapshot().unavailableMessage());
                    assertEquals(ExecutionMode.ASYNC, registry.service("demo.service").methodById("publish(java.lang.String)").snapshot().executionMode());
                    assertEquals(4, registry.service("demo.service").methodById("publish(java.lang.String)").snapshot().consumerThreads());

                    AslAdminRuntimeConfiguration runtimeConfiguration = context.getBean(AslAdminRuntimeConfiguration.class);
                    assertEquals("Reactor Admin Console", runtimeConfiguration.ui().heroTitle());
                    assertEquals(11, runtimeConfiguration.bufferPreviewLimit());
                    assertEquals(5, runtimeConfiguration.attentionLimit());
                    assertEquals(35, runtimeConfiguration.mediumUtilizationPercent());
                    assertEquals(75, runtimeConfiguration.highUtilizationPercent());
                    assertFalse(runtimeConfiguration.refresh().liveRefreshEnabled());
                    assertFalse(runtimeConfiguration.refresh().liveBufferEnabled());
                    assertEquals(10_000, runtimeConfiguration.refresh().defaultIntervalMs());
                    assertEquals(List.of(5000, 10000), runtimeConfiguration.refresh().intervalOptionsMs());
                    assertEquals(900, runtimeConfiguration.refresh().changeFlashMs());
                    assertEquals(1800, runtimeConfiguration.refresh().successMessageAutoHideMs());
                    assertEquals(3600, runtimeConfiguration.refresh().errorMessageAutoHideMs());
                });
    }

    private static final class NoOpAsyncEngine implements AsyncExecutionEngine, BufferAdminProvider {
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

    private static void writeCorruptStore(Path path) throws IOException {
        Files.write(path, new byte[]{1, 2, 3, 4, 5});
    }

    private static Path testDataPath(String prefix) throws IOException {
        Path dir = Path.of("target", "test-data");
        Files.createDirectories(dir);
        return dir.resolve(prefix + "-" + UUID.randomUUID() + ".db");
    }
}
