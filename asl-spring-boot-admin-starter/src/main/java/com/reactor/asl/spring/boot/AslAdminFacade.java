package com.reactor.asl.spring.boot;

import com.reactor.asl.core.AsyncAdminProvider;
import com.reactor.asl.core.BufferAdminProvider;
import com.reactor.asl.core.ExecutionMode;
import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.core.MethodBufferSnapshot;
import com.reactor.asl.core.MethodPolicy;
import com.reactor.asl.core.MethodRuntime;
import com.reactor.asl.core.MethodRuntimeSnapshot;
import com.reactor.asl.core.ServiceRuntime;
import com.reactor.asl.core.ServiceRuntimeSnapshot;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class AslAdminFacade {
    private final GovernanceRegistry registry;
    private final AslAdminProperties properties;
    private final AslAdminRuntimeConfiguration runtimeConfiguration;
    private final AslStartupRecoveryState startupRecoveryState;
    private final List<BufferAdminProvider> bufferAdminProviders;

    public AslAdminFacade(
            GovernanceRegistry registry,
            AslAdminProperties properties,
            List<BufferAdminProvider> bufferAdminProviders
    ) {
        this(
                registry,
                properties,
                new AslAdminRuntimeConfiguration(properties),
                new AslStartupRecoveryState(),
                bufferAdminProviders
        );
    }

    public AslAdminFacade(
            GovernanceRegistry registry,
            AslAdminProperties properties,
            AslAdminRuntimeConfiguration runtimeConfiguration,
            List<BufferAdminProvider> bufferAdminProviders
    ) {
        this(registry, properties, runtimeConfiguration, new AslStartupRecoveryState(), bufferAdminProviders);
    }

    public AslAdminFacade(
            GovernanceRegistry registry,
            AslAdminProperties properties,
            AslAdminRuntimeConfiguration runtimeConfiguration,
            AslStartupRecoveryState startupRecoveryState,
            List<BufferAdminProvider> bufferAdminProviders
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.runtimeConfiguration = Objects.requireNonNull(runtimeConfiguration, "runtimeConfiguration");
        this.startupRecoveryState = Objects.requireNonNull(startupRecoveryState, "startupRecoveryState");
        this.bufferAdminProviders = List.copyOf(bufferAdminProviders);
    }

    public List<ServiceRuntimeSnapshot> services() {
        return registry.services().stream()
                .map(ServiceRuntime::snapshot)
                .sorted(Comparator.comparing(ServiceRuntimeSnapshot::serviceId))
                .toList();
    }

    public List<AdminServiceView> dashboard() {
        return dashboard(services(), runtimeConfiguration.bufferPreviewLimit());
    }

    public AdminDashboardSummary summary() {
        return summarize(dashboard(services(), 0));
    }

    public AdminDashboardProjection projection(int requestedPage, int requestedSize) {
        return projectionUnlessMatching(requestedPage, requestedSize, etag -> false).projection();
    }

    /**
     * Takes one runtime snapshot, fingerprints it and builds the projection from the same snapshot
     * only when the caller's ETag does not match. Previously a mismatch took the snapshots twice.
     */
    public ProjectionOutcome projectionUnlessMatching(
            int requestedPage,
            int requestedSize,
            java.util.function.Predicate<String> callerHasEtag
    ) {
        int pageSize = validateProjectionRequest(requestedPage, requestedSize);
        List<ServiceRuntimeSnapshot> snapshots = services();
        ProjectionFingerprint fingerprint = projectionFingerprint(snapshots);
        if (fingerprint.cacheable() && callerHasEtag.test(fingerprint.etag())) {
            return new ProjectionOutcome(fingerprint.etag(), null);
        }
        return new ProjectionOutcome(fingerprint.etag(), projection(requestedPage, pageSize, snapshots, fingerprint));
    }

    private AdminDashboardProjection projection(
            int requestedPage,
            int pageSize,
            List<ServiceRuntimeSnapshot> snapshots,
            ProjectionFingerprint fingerprint
    ) {
        int totalServices = snapshots.size();
        int totalPages = totalServices == 0 ? 0 : (int) Math.ceil((double) totalServices / pageSize);
        int fromIndex = (int) Math.min((long) requestedPage * pageSize, totalServices);
        int toIndex = Math.min(fromIndex + pageSize, totalServices);

        List<AdminServiceView> summaryViews = new ArrayList<>(totalServices);
        List<AdminServiceView> pageViews = new ArrayList<>(Math.max(0, toIndex - fromIndex));
        for (int index = 0; index < snapshots.size(); index++) {
            int previewLimit = index >= fromIndex && index < toIndex
                    ? runtimeConfiguration.bufferPreviewLimit()
                    : 0;
            AdminServiceView view = dashboard(snapshots.get(index), previewLimit);
            summaryViews.add(view);
            if (index >= fromIndex && index < toIndex) {
                pageViews.add(view);
            }
        }

        return new AdminDashboardProjection(
                fingerprint.etag(),
                fingerprint.cacheable(),
                requestedPage,
                pageSize,
                totalServices,
                totalPages,
                List.copyOf(pageViews),
                summarize(summaryViews)
        );
    }

    public int validateProjectionRequest(int requestedPage, int requestedSize) {
        if (requestedPage < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (requestedSize < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
        int pageSize = requestedSize == 0 ? runtimeConfiguration.defaultPageSize() : requestedSize;
        if (pageSize > runtimeConfiguration.maxPageSize()) {
            throw new IllegalArgumentException(
                    "size must not exceed " + runtimeConfiguration.maxPageSize()
            );
        }
        return pageSize;
    }

    public ProjectionFingerprint projectionFingerprint() {
        return projectionFingerprint(services());
    }

    private AdminDashboardSummary summarize(List<AdminServiceView> services) {
        long methodCount = services.stream().mapToLong(AdminServiceView::totalMethods).sum();
        long runningMethodCount = services.stream()
                .flatMap(service -> service.methods().stream())
                .filter(AdminMethodView::enabled)
                .count();
        long stoppedMethodCount = methodCount - runningMethodCount;
        long asyncCapableMethodCount = services.stream().mapToLong(AdminServiceView::asyncCapableMethods).sum();
        long asyncActiveMethodCount = services.stream()
                .flatMap(service -> service.methods().stream())
                .filter(AdminMethodView::isAsyncMode)
                .count();
        long methodsWithErrors = services.stream().mapToLong(AdminServiceView::methodsWithErrors).sum();
        long pendingEntries = services.stream()
                .flatMap(service -> service.methods().stream())
                .mapToLong(method -> method.buffer().pendingCount())
                .sum();
        long failedEntries = services.stream()
                .flatMap(service -> service.methods().stream())
                .mapToLong(method -> method.buffer().failedCount())
                .sum();
        long inProgressEntries = services.stream()
                .flatMap(service -> service.methods().stream())
                .mapToLong(method -> method.buffer().inProgressCount())
                .sum();
        long totalQueueDepth = pendingEntries + failedEntries + inProgressEntries;
        long totalInFlight = services.stream()
                .flatMap(service -> service.methods().stream())
                .mapToLong(method -> method.method().inFlight())
                .sum();
        long totalProcessed = services.stream()
                .flatMap(service -> service.methods().stream())
                .mapToLong(AdminMethodView::totalProcessed)
                .sum();
        long totalRejected = services.stream()
                .flatMap(service -> service.methods().stream())
                .mapToLong(method -> method.method().rejectedCount())
                .sum();
        List<AttentionItem> attentionItems = services.stream()
                .flatMap(service -> service.methods().stream().map(this::attentionItem).flatMap(Optional::stream))
                .sorted(Comparator
                        .comparingInt(AttentionItem::priority)
                        .thenComparing(AttentionItem::serviceId)
                        .thenComparing(AttentionItem::methodId))
                .limit(runtimeConfiguration.attentionLimit())
                .toList();
        String overallPressure = overallPressure(failedEntries, totalQueueDepth, attentionItems, totalRejected, totalInFlight);
        return new AdminDashboardSummary(
                services.size(),
                methodCount,
                runningMethodCount,
                stoppedMethodCount,
                asyncCapableMethodCount,
                asyncActiveMethodCount,
                methodsWithErrors,
                totalQueueDepth,
                pendingEntries,
                failedEntries,
                inProgressEntries,
                totalInFlight,
                totalProcessed,
                totalRejected,
                overallPressure,
                attentionItems,
                startupRecoveryState.currentNotice()
        );
    }

    private List<AdminServiceView> dashboard(
            List<ServiceRuntimeSnapshot> serviceSnapshots,
            int previewLimit
    ) {
        return serviceSnapshots.stream()
                .map(service -> dashboard(service, previewLimit))
                .toList();
    }

    private AdminServiceView dashboard(ServiceRuntimeSnapshot service, int previewLimit) {
        return new AdminServiceView(
                service.serviceId(),
                service.methods().stream()
                        .map(method -> new AdminMethodView(
                                method,
                                buffer(method.serviceId(), method.methodId(), previewLimit)
                        ))
                        .toList()
        );
    }

    public ServiceRuntimeSnapshot service(String serviceId) {
        return registry.service(serviceId).snapshot();
    }

    public MethodRuntimeSnapshot enable(String serviceId, String methodId) {
        MethodRuntime runtime = method(serviceId, methodId);
        runtime.enable();
        return runtime.snapshot();
    }

    public MethodRuntimeSnapshot disable(String serviceId, String methodId, String message) {
        MethodRuntime runtime = method(serviceId, methodId);
        runtime.disable(message);
        return runtime.snapshot();
    }

    public MethodRuntimeSnapshot setMaxConcurrency(String serviceId, String methodId, int maxConcurrency) {
        MethodRuntime runtime = method(serviceId, methodId);
        runtime.setMaxConcurrency(maxConcurrency);
        return runtime.snapshot();
    }

    public MethodRuntimeSnapshot switchMode(String serviceId, String methodId, ExecutionMode executionMode) {
        MethodRuntime runtime = method(serviceId, methodId);
        if (executionMode == ExecutionMode.ASYNC && !registry.hasAsyncExecutionEngine()) {
            throw new IllegalStateException("No async execution engine attached");
        }
        runtime.switchMode(executionMode);
        applyAsyncRuntime(runtime);
        return runtime.snapshot();
    }

    public MethodRuntimeSnapshot setConsumerThreads(String serviceId, String methodId, int consumerThreads) {
        MethodRuntime runtime = method(serviceId, methodId);
        runtime.setConsumerThreads(consumerThreads);
        applyAsyncRuntime(runtime);
        return runtime.snapshot();
    }

    public MethodBufferSnapshot buffer(String serviceId, String methodId) {
        return buffer(serviceId, methodId, runtimeConfiguration.bufferPreviewLimit());
    }

    private MethodBufferSnapshot buffer(String serviceId, String methodId, int previewLimit) {
        return bufferProvider(serviceId, methodId)
                .map(provider -> provider.snapshot(serviceId, methodId, previewLimit))
                .orElseGet(() -> MethodBufferSnapshot.unavailable(serviceId, methodId));
    }

    public int clearBuffer(String serviceId, String methodId) {
        return bufferProvider(serviceId, methodId)
                .map(provider -> provider.clear(serviceId, methodId))
                .orElse(0);
    }

    public boolean deleteBufferEntry(String serviceId, String methodId, String entryId) {
        return bufferProvider(serviceId, methodId)
                .map(provider -> provider.delete(serviceId, methodId, entryId))
                .orElse(false);
    }

    public boolean replayBufferEntry(String serviceId, String methodId, String entryId) {
        return asyncProvider(serviceId, methodId)
                .map(provider -> provider.replay(serviceId, methodId, entryId))
                .orElse(false);
    }

    public String pagePath() {
        return runtimeConfiguration.pagePath();
    }

    public String apiPath() {
        return runtimeConfiguration.apiPath();
    }

    public AslAdminRuntimeConfiguration.ConfigSnapshot config() {
        return runtimeConfiguration.snapshot();
    }

    public AslAdminRuntimeConfiguration.UiSnapshot ui() {
        return runtimeConfiguration.ui();
    }

    public AslAdminRuntimeConfiguration.UiSnapshot updateUi(AslAdminRuntimeConfiguration.UiPatch patch) {
        return runtimeConfiguration.updateUi(patch);
    }

    public AslAdminRuntimeConfiguration.ConfigSnapshot setBufferPreviewLimit(int bufferPreviewLimit) {
        runtimeConfiguration.setBufferPreviewLimit(bufferPreviewLimit);
        return runtimeConfiguration.snapshot();
    }

    public AslAdminRuntimeConfiguration.ConfigSnapshot setAttentionLimit(int attentionLimit) {
        runtimeConfiguration.setAttentionLimit(attentionLimit);
        return runtimeConfiguration.snapshot();
    }

    public AslAdminRuntimeConfiguration.ConfigSnapshot setUtilizationThresholds(
            int mediumUtilizationPercent,
            int highUtilizationPercent
    ) {
        runtimeConfiguration.setUtilizationThresholds(mediumUtilizationPercent, highUtilizationPercent);
        return runtimeConfiguration.snapshot();
    }

    public AslAdminRuntimeConfiguration.ConfigSnapshot updateRefresh(
            AslAdminRuntimeConfiguration.RefreshPatch patch
    ) {
        runtimeConfiguration.updateRefresh(patch);
        return runtimeConfiguration.snapshot();
    }

    public MethodRuntimeSnapshot applyMethodConfiguration(
            String serviceId,
            String methodId,
            AslAdminProperties.MethodConfiguration configuration
    ) {
        Objects.requireNonNull(configuration, "configuration");
        MethodRuntime runtime = method(serviceId, methodId);
        if (configuration.getMaxConcurrency() != null) {
            runtime.setMaxConcurrency(configuration.getMaxConcurrency());
        }
        if (configuration.getConsumerThreads() != null) {
            runtime.setConsumerThreads(configuration.getConsumerThreads());
        }
        if (configuration.getUnavailableMessage() != null) {
            MethodPolicy current = runtime.policy();
            runtime.updatePolicy(current.withUnavailableMessage(configuration.getUnavailableMessage()));
        }
        if (configuration.getEnabled() != null) {
            if (configuration.getEnabled()) {
                runtime.enable();
            } else {
                runtime.disable(configuration.getUnavailableMessage());
            }
        }
        if (configuration.getExecutionMode() != null) {
            switchMode(serviceId, methodId, configuration.getExecutionMode());
        } else {
            applyAsyncRuntime(runtime);
        }
        return runtime.snapshot();
    }

    public void applyConfiguredMethodOverrides() {
        for (Map.Entry<String, AslAdminProperties.ServiceConfiguration> serviceEntry : properties.getServices().entrySet()) {
            String serviceId = serviceEntry.getKey();
            for (Map.Entry<String, AslAdminProperties.MethodConfiguration> methodEntry : serviceEntry.getValue().getMethods().entrySet()) {
                applyMethodConfiguration(serviceId, methodEntry.getKey(), methodEntry.getValue());
            }
        }
    }

    private MethodRuntime method(String serviceId, String methodId) {
        return registry.service(serviceId).methodById(methodId);
    }

    private Optional<BufferAdminProvider> bufferProvider(String serviceId, String methodId) {
        return bufferAdminProviders.stream()
                .filter(provider -> provider.supports(serviceId, methodId))
                .findFirst();
    }

    private Optional<AsyncAdminProvider> asyncProvider(String serviceId, String methodId) {
        return bufferAdminProviders.stream()
                .filter(provider -> provider instanceof AsyncAdminProvider)
                .map(provider -> (AsyncAdminProvider) provider)
                .filter(provider -> provider.supports(serviceId, methodId))
                .findFirst();
    }

    private void applyAsyncRuntime(MethodRuntime runtime) {
        asyncProvider(runtime.serviceId(), runtime.methodId()).ifPresent(provider -> provider.applyRuntime(runtime));
    }

    private Optional<AttentionItem> attentionItem(AdminMethodView method) {
        if (method.queueDepth() > 0 && method.method().consumerThreads() <= 0) {
            return Optional.of(new AttentionItem(
                    "HIGH",
                    method.method().serviceId(),
                    method.method().methodId(),
                    method.displayTitle(),
                    "Queued work is blocked",
                    method.queueDepth() + " queued entries exist while consumer threads are 0"
                            + (method.isAsyncMode() ? "." : " (lane is in SYNC mode; accepted backlog still needs consumers)."),
                    "Raise consumer threads to drain the backlog, or clear it deliberately."
            ));
        }
        if (method.buffer().failedCount() > 0) {
            return Optional.of(new AttentionItem(
                    "HIGH",
                    method.method().serviceId(),
                    method.method().methodId(),
                    method.displayTitle(),
                    "Failures detected",
                    "Failed buffer entries currently retained: " + method.buffer().failedCount() + ".",
                    "Inspect the last error, then replay or clear failed buffer entries."
            ));
        }
        if (!method.enabled()) {
            return Optional.of(new AttentionItem(
                    "WARN",
                    method.method().serviceId(),
                    method.method().methodId(),
                    method.displayTitle(),
                    "Method is stopped",
                    "Incoming callers currently receive the configured unavailable message.",
                    "Re-enable the method when traffic can be resumed safely."
            ));
        }
        if (method.utilizationPercent() >= runtimeConfiguration.highUtilizationPercent()) {
            return Optional.of(new AttentionItem(
                    "WARN",
                    method.method().serviceId(),
                    method.method().methodId(),
                    method.displayTitle(),
                    "High utilization",
                    "Current utilization is " + method.utilizationPercent() + "% with " + method.currentWork() + " active work items.",
                    "Increase concurrency carefully or reduce upstream pressure."
            ));
        }
        if (method.queueDepth() > 0) {
            return Optional.of(new AttentionItem(
                    "WARN",
                    method.method().serviceId(),
                    method.method().methodId(),
                    method.displayTitle(),
                    "Queue pressure",
                    "Queue depth is " + method.queueDepth() + " and work pressure is "
                            + method.workPressure(runtimeConfiguration.mediumUtilizationPercent(), runtimeConfiguration.highUtilizationPercent()) + ".",
                    "Watch backlog growth and confirm consumers drain work at the expected rate."
            ));
        }
        if (method.asyncCapable() && !method.isAsyncMode()) {
            return Optional.of(new AttentionItem(
                    "INFO",
                    method.method().serviceId(),
                    method.method().methodId(),
                    method.displayTitle(),
                    "Async-capable lane is running inline",
                    "This method can queue work but is currently executing in sync mode.",
                    "Keep sync if latency is acceptable, or move to async when buffering is required."
            ));
        }
        return Optional.empty();
    }

    private String overallPressure(
            long failedEntries,
            long totalQueueDepth,
            List<AttentionItem> attentionItems,
            long totalRejected,
            long totalInFlight
    ) {
        boolean hasHighAttention = attentionItems.stream().anyMatch(item -> "HIGH".equals(item.severity()));
        boolean hasWarnOrHigherAttention = attentionItems.stream()
                .anyMatch(item -> "HIGH".equals(item.severity()) || "WARN".equals(item.severity()));
        if (failedEntries > 0 || totalRejected > 0 || hasHighAttention) {
            return "HIGH";
        }
        if (totalQueueDepth > 0 || totalInFlight > 0 || hasWarnOrHigherAttention) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private ProjectionFingerprint projectionFingerprint(List<ServiceRuntimeSnapshot> snapshots) {
        long hash = 0xcbf29ce484222325L;
        for (ServiceRuntimeSnapshot service : snapshots) {
            hash = hash(hash, service.serviceId());
            for (MethodRuntimeSnapshot method : service.methods()) {
                hash = hash(hash, method.serviceId());
                hash = hash(hash, method.methodId());
                hash = hash(hash, method.methodName());
                hash = hash(hash, method.enabled());
                hash = hash(hash, method.maxConcurrency());
                hash = hash(hash, method.asyncCapable());
                hash = hash(hash, method.executionMode().ordinal());
                hash = hash(hash, method.consumerThreads());
                hash = hash(hash, method.unavailableMessage());
                hash = hash(hash, method.successCount());
                hash = hash(hash, method.errorCount());
                hash = hash(hash, method.rejectedCount());
                hash = hash(hash, method.inFlight());
                hash = hash(hash, method.peakInFlight());
                hash = hash(hash, method.lastError());
            }
        }

        boolean cacheable = true;
        for (BufferAdminProvider provider : bufferAdminProviders) {
            long providerVersion = provider.projectionVersion();
            if (providerVersion < 0L) {
                cacheable = false;
            } else {
                hash = hash(hash, providerVersion);
            }
        }
        hash = hash(hash, runtimeConfiguration.snapshot().hashCode());
        hash = hash(hash, startupRecoveryState.currentNotice().hashCode());
        return new ProjectionFingerprint("W/\"" + Long.toUnsignedString(hash, 16) + "\"", cacheable);
    }

    private static long hash(long current, boolean value) {
        return hash(current, value ? 1L : 0L);
    }

    private static long hash(long current, int value) {
        return hash(current, (long) value);
    }

    private static long hash(long current, long value) {
        long result = current;
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            result ^= (value >>> shift) & 0xffL;
            result *= 0x100000001b3L;
        }
        return result;
    }

    private static long hash(long current, String value) {
        if (value == null) {
            return hash(current, -1L);
        }
        long result = current;
        for (int index = 0; index < value.length(); index++) {
            result ^= value.charAt(index);
            result *= 0x100000001b3L;
        }
        return result;
    }

    public record AdminServiceView(String serviceId, List<AdminMethodView> methods) {
        public long totalMethods() {
            return methods.size();
        }

        public long asyncCapableMethods() {
            return methods.stream().filter(AdminMethodView::asyncCapable).count();
        }

        public long stoppedMethods() {
            return methods.stream().filter(method -> !method.enabled()).count();
        }

        public long methodsWithErrors() {
            return methods.stream().filter(AdminMethodView::hasError).count();
        }
    }

    public record AdminMethodView(MethodRuntimeSnapshot method, MethodBufferSnapshot buffer) {
        public String displayTitle() {
            return method.methodName();
        }

        public String displaySignature() {
            return displayParameters();
        }

        public String rawMethodId() {
            return method.methodId();
        }

        public boolean hasParameters() {
            return !displayParameters().isBlank();
        }

        public boolean enabled() {
            return method.enabled();
        }

        public boolean asyncCapable() {
            return method.asyncCapable();
        }

        public boolean isAsyncMode() {
            return method.executionMode() == ExecutionMode.ASYNC;
        }

        public boolean hasError() {
            return method.lastError() != null || method.errorCount() > 0 || buffer.failedCount() > 0;
        }

        public long totalProcessed() {
            return method.successCount() + method.errorCount();
        }

        public long currentWork() {
            return method.inFlight() + buffer.pendingCount() + buffer.inProgressCount();
        }

        public long queueDepth() {
            return buffer.pendingCount() + buffer.inProgressCount() + buffer.failedCount();
        }

        public int utilizationPercent() {
            if (method.maxConcurrency() <= 0) {
                return 0;
            }
            return (int) Math.round((method.inFlight() * 100.0d) / method.maxConcurrency());
        }

        public String workPressure(int mediumUtilizationPercent, int highUtilizationPercent) {
            if (queueDepth() > method.maxConcurrency() || utilizationPercent() >= highUtilizationPercent) {
                return "HIGH";
            }
            if (currentWork() > 0 || utilizationPercent() >= mediumUtilizationPercent) {
                return "MEDIUM";
            }
            return "LOW";
        }

        public String workerCapacityText() {
            if (method.asyncCapable()) {
                return method.consumerThreads() + " async / " + method.maxConcurrency() + " max";
            }
            return method.maxConcurrency() + " sync max";
        }

        private String displayParameters() {
            String methodId = method.methodId();
            int start = methodId.indexOf('(');
            int end = methodId.lastIndexOf(')');
            if (start < 0 || end <= start + 1) {
                return "";
            }
            String parameterBlock = methodId.substring(start + 1, end).trim();
            if (parameterBlock.isBlank()) {
                return "";
            }
            return java.util.Arrays.stream(parameterBlock.split(","))
                    .map(String::trim)
                    .map(AdminMethodView::shortTypeName)
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("");
        }

        private static String shortTypeName(String typeName) {
            if (typeName.isBlank()) {
                return typeName;
            }
            int genericStart = typeName.indexOf('<');
            if (genericStart >= 0) {
                String rawType = typeName.substring(0, genericStart);
                String genericPart = typeName.substring(genericStart);
                return shortTypeName(rawType) + genericPart;
            }
            int lastDot = typeName.lastIndexOf('.');
            return lastDot >= 0 ? typeName.substring(lastDot + 1) : typeName;
        }
    }

    public record AdminDashboardSummary(
            int serviceCount,
            long methodCount,
            long runningMethodCount,
            long stoppedMethodCount,
            long asyncCapableMethodCount,
            long asyncActiveMethodCount,
            long methodsWithErrors,
            long totalQueueDepth,
            long pendingEntries,
            long failedEntries,
            long inProgressEntries,
            long totalInFlight,
            long totalProcessed,
            long totalRejected,
            String overallPressure,
            List<AttentionItem> attentionItems,
            AslStartupRecoveryState.StorageRecoveryNotice storageRecovery
    ) {
        public int attentionCount() {
            return attentionItems.size();
        }

        public boolean hasAttention() {
            return !attentionItems.isEmpty();
        }

        public boolean hasStorageRecovery() {
            return storageRecovery.visible();
        }
    }

    public record AdminDashboardProjection(
            String version,
            boolean cacheable,
            int page,
            int size,
            int totalServices,
            int totalPages,
            List<AdminServiceView> services,
            AdminDashboardSummary summary
    ) {
        public AdminDashboardProjection {
            services = List.copyOf(services);
        }
    }

    public record ProjectionFingerprint(String etag, boolean cacheable) {
    }

    /** {@code projection} is {@code null} when the caller's ETag is still current. */
    public record ProjectionOutcome(String etag, AdminDashboardProjection projection) {
        public boolean notModified() {
            return projection == null;
        }
    }

    public record AttentionItem(
            String severity,
            String serviceId,
            String methodId,
            String methodName,
            String headline,
            String detail,
            String action
    ) {
        public int priority() {
            return switch (severity) {
                case "HIGH" -> 0;
                case "WARN" -> 1;
                default -> 2;
            };
        }
    }
}
