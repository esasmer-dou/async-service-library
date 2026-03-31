package com.reactor.asl.spring.boot;

import com.reactor.asl.core.ExecutionMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties("asl.admin")
public class AslAdminProperties {
    private boolean enabled = true;
    private String path = "/asl";
    private String apiPath = "/asl/api";
    private int bufferPreviewLimit = 50;
    private Dashboard dashboard = new Dashboard();
    private Ui ui = new Ui();
    private Map<String, ServiceConfiguration> services = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getApiPath() {
        return apiPath;
    }

    public void setApiPath(String apiPath) {
        this.apiPath = apiPath;
    }

    public int getBufferPreviewLimit() {
        return bufferPreviewLimit;
    }

    public void setBufferPreviewLimit(int bufferPreviewLimit) {
        this.bufferPreviewLimit = bufferPreviewLimit;
    }

    public Ui getUi() {
        return ui;
    }

    public void setUi(Ui ui) {
        this.ui = ui == null ? new Ui() : ui;
    }

    public Dashboard getDashboard() {
        return dashboard;
    }

    public void setDashboard(Dashboard dashboard) {
        this.dashboard = dashboard == null ? new Dashboard() : dashboard;
    }

    public Map<String, ServiceConfiguration> getServices() {
        return services;
    }

    public void setServices(Map<String, ServiceConfiguration> services) {
        this.services = services == null ? new LinkedHashMap<>() : new LinkedHashMap<>(services);
    }

    public static final class Ui {
        private String pageTitle = "ASL Control Plane";
        private String heroTitle = "ASL Control Plane";
        private String heroDescription = "Review governed methods, stop or resume traffic, change concurrency and async settings, and inspect queue state from the same Spring Boot port.";
        private String restBadgePrefix = "REST:";
        private String emptyTitle = "No governed services registered";
        private String emptyDescription = "The admin UI is active, but the runtime registry is empty.";
        private String servicesTitle = "Services";
        private String serviceSearchPlaceholder = "Search services";
        private String serviceTabNote = "Open this service subform";
        private String serviceDetailNote = "Select a method from the left subform list to manage its full details.";
        private String methodsTitle = "Methods";
        private String allLabel = "All";
        private String noParametersLabel = "No parameters";
        private String runningLabel = "RUNNING";
        private String stoppedLabel = "STOPPED";
        private String syncModeLabel = "SYNC";
        private String asyncLabel = "ASYNC";
        private String errorLabel = "ERROR";
        private String successLabel = "Success";
        private String rejectedLabel = "Rejected";
        private String loadLabel = "Load";
        private String peakInFlightLabel = "Peak In Flight";
        private String executionModeLabel = "Execution Mode";
        private String consumerThreadsLabel = "Consumer Threads";
        private String lastErrorLabel = "Last Error";
        private String noneLabel = "none";
        private String methodStateTitle = "Method State";
        private String startMethodLabel = "Start Method";
        private String stopMethodLabel = "Stop Method";
        private String disablePlaceholder = "Reason shown to callers";
        private String methodStateHint = "Stopping a method returns the configured message to incoming callers.";
        private String syncConcurrencyTitle = "Sync Concurrency";
        private String updateLimitLabel = "Update Limit";
        private String syncConcurrencyHint = "Defines how many concurrent executions are allowed for this method.";
        private String asyncControlsTitle = "Async Controls";
        private String applyModeLabel = "Apply";
        private String updateConsumersLabel = "Update";
        private String asyncHint = "Use async mode only for methods designed to be safely queued and consumed later.";
        private String queueBufferTitle = "Queue Buffer";
        private String loadOverviewTitle = "Load Overview";
        private String noBufferMessage = "No buffer provider is currently attached to this method.";
        private String clearBufferLabel = "Clear Buffer";
        private String replayEntryLabel = "Replay Entry";
        private String deleteEntryLabel = "Delete Entry";
        private String processedLabel = "Processed";
        private String activeWorkLabel = "Active Work";
        private String queueDepthLabel = "Queue Depth";
        private String utilizationLabel = "Utilization";
        private String workPressureLabel = "Work Pressure";
        private String workerCapacityLabel = "Worker Capacity";
        private String liveRefreshLabel = "Live Refresh";
        private String refreshNowLabel = "Refresh Now";
        private String refreshIntervalLabel = "Refresh Interval";
        private String refreshBufferLabel = "Refresh Buffer";
        private String liveBufferLabel = "Live Buffer";
        private String scrollTopLabel = "Top";
        private String scrollBottomLabel = "Bottom";
        private String readyStatusLabel = "Ready";
        private String applyingChangeMessage = "Applying change...";
        private String changeAppliedMessage = "Change applied";
        private String requestFailedMessage = "Request failed";
        private String refreshingMetricsMessage = "Refreshing live metrics...";
        private String metricsRefreshedMessage = "Metrics refreshed";
        private String refreshingBufferMessage = "Refreshing buffer...";
        private String bufferRefreshedMessage = "Buffer refreshed";
        private String entryIdLabel = "Entry Id";
        private String attemptsLabel = "Attempts";
        private String codecLabel = "Codec";
        private String payloadTypeLabel = "Payload Type";
        private String payloadVersionLabel = "Payload Version";
        private String errorTypeLabel = "Error Type";
        private String errorCategoryLabel = "Error Category";
        private String methodsCountSuffix = "methods";
        private String asyncCapableSuffix = "async-capable";
        private String stoppedSuffix = "stopped";
        private String methodsWithErrorsSuffix = "with errors";
        private String pendingLabel = "Pending";
        private String failedLabel = "Failed";
        private String inProgressLabel = "In progress";

        public String getPageTitle() {
            return pageTitle;
        }

        public void setPageTitle(String pageTitle) {
            this.pageTitle = pageTitle;
        }

        public String getHeroTitle() {
            return heroTitle;
        }

        public void setHeroTitle(String heroTitle) {
            this.heroTitle = heroTitle;
        }

        public String getHeroDescription() {
            return heroDescription;
        }

        public void setHeroDescription(String heroDescription) {
            this.heroDescription = heroDescription;
        }

        public String getRestBadgePrefix() {
            return restBadgePrefix;
        }

        public void setRestBadgePrefix(String restBadgePrefix) {
            this.restBadgePrefix = restBadgePrefix;
        }

        public String getEmptyTitle() {
            return emptyTitle;
        }

        public void setEmptyTitle(String emptyTitle) {
            this.emptyTitle = emptyTitle;
        }

        public String getEmptyDescription() {
            return emptyDescription;
        }

        public void setEmptyDescription(String emptyDescription) {
            this.emptyDescription = emptyDescription;
        }

        public String getServicesTitle() {
            return servicesTitle;
        }

        public void setServicesTitle(String servicesTitle) {
            this.servicesTitle = servicesTitle;
        }

        public String getServiceSearchPlaceholder() {
            return serviceSearchPlaceholder;
        }

        public void setServiceSearchPlaceholder(String serviceSearchPlaceholder) {
            this.serviceSearchPlaceholder = serviceSearchPlaceholder;
        }

        public String getServiceTabNote() {
            return serviceTabNote;
        }

        public void setServiceTabNote(String serviceTabNote) {
            this.serviceTabNote = serviceTabNote;
        }

        public String getServiceDetailNote() {
            return serviceDetailNote;
        }

        public void setServiceDetailNote(String serviceDetailNote) {
            this.serviceDetailNote = serviceDetailNote;
        }

        public String getMethodsTitle() {
            return methodsTitle;
        }

        public void setMethodsTitle(String methodsTitle) {
            this.methodsTitle = methodsTitle;
        }

        public String getAllLabel() {
            return allLabel;
        }

        public void setAllLabel(String allLabel) {
            this.allLabel = allLabel;
        }

        public String getNoParametersLabel() {
            return noParametersLabel;
        }

        public void setNoParametersLabel(String noParametersLabel) {
            this.noParametersLabel = noParametersLabel;
        }

        public String getRunningLabel() {
            return runningLabel;
        }

        public void setRunningLabel(String runningLabel) {
            this.runningLabel = runningLabel;
        }

        public String getStoppedLabel() {
            return stoppedLabel;
        }

        public void setStoppedLabel(String stoppedLabel) {
            this.stoppedLabel = stoppedLabel;
        }

        public String getAsyncLabel() {
            return asyncLabel;
        }

        public void setAsyncLabel(String asyncLabel) {
            this.asyncLabel = asyncLabel;
        }

        public String getSyncModeLabel() {
            return syncModeLabel;
        }

        public void setSyncModeLabel(String syncModeLabel) {
            this.syncModeLabel = syncModeLabel;
        }

        public String getErrorLabel() {
            return errorLabel;
        }

        public void setErrorLabel(String errorLabel) {
            this.errorLabel = errorLabel;
        }

        public String getSuccessLabel() {
            return successLabel;
        }

        public void setSuccessLabel(String successLabel) {
            this.successLabel = successLabel;
        }

        public String getRejectedLabel() {
            return rejectedLabel;
        }

        public void setRejectedLabel(String rejectedLabel) {
            this.rejectedLabel = rejectedLabel;
        }

        public String getLoadLabel() {
            return loadLabel;
        }

        public void setLoadLabel(String loadLabel) {
            this.loadLabel = loadLabel;
        }

        public String getPeakInFlightLabel() {
            return peakInFlightLabel;
        }

        public void setPeakInFlightLabel(String peakInFlightLabel) {
            this.peakInFlightLabel = peakInFlightLabel;
        }

        public String getExecutionModeLabel() {
            return executionModeLabel;
        }

        public void setExecutionModeLabel(String executionModeLabel) {
            this.executionModeLabel = executionModeLabel;
        }

        public String getConsumerThreadsLabel() {
            return consumerThreadsLabel;
        }

        public void setConsumerThreadsLabel(String consumerThreadsLabel) {
            this.consumerThreadsLabel = consumerThreadsLabel;
        }

        public String getLastErrorLabel() {
            return lastErrorLabel;
        }

        public void setLastErrorLabel(String lastErrorLabel) {
            this.lastErrorLabel = lastErrorLabel;
        }

        public String getNoneLabel() {
            return noneLabel;
        }

        public void setNoneLabel(String noneLabel) {
            this.noneLabel = noneLabel;
        }

        public String getMethodStateTitle() {
            return methodStateTitle;
        }

        public void setMethodStateTitle(String methodStateTitle) {
            this.methodStateTitle = methodStateTitle;
        }

        public String getStartMethodLabel() {
            return startMethodLabel;
        }

        public void setStartMethodLabel(String startMethodLabel) {
            this.startMethodLabel = startMethodLabel;
        }

        public String getStopMethodLabel() {
            return stopMethodLabel;
        }

        public void setStopMethodLabel(String stopMethodLabel) {
            this.stopMethodLabel = stopMethodLabel;
        }

        public String getDisablePlaceholder() {
            return disablePlaceholder;
        }

        public void setDisablePlaceholder(String disablePlaceholder) {
            this.disablePlaceholder = disablePlaceholder;
        }

        public String getMethodStateHint() {
            return methodStateHint;
        }

        public void setMethodStateHint(String methodStateHint) {
            this.methodStateHint = methodStateHint;
        }

        public String getSyncConcurrencyTitle() {
            return syncConcurrencyTitle;
        }

        public void setSyncConcurrencyTitle(String syncConcurrencyTitle) {
            this.syncConcurrencyTitle = syncConcurrencyTitle;
        }

        public String getUpdateLimitLabel() {
            return updateLimitLabel;
        }

        public void setUpdateLimitLabel(String updateLimitLabel) {
            this.updateLimitLabel = updateLimitLabel;
        }

        public String getSyncConcurrencyHint() {
            return syncConcurrencyHint;
        }

        public void setSyncConcurrencyHint(String syncConcurrencyHint) {
            this.syncConcurrencyHint = syncConcurrencyHint;
        }

        public String getAsyncControlsTitle() {
            return asyncControlsTitle;
        }

        public void setAsyncControlsTitle(String asyncControlsTitle) {
            this.asyncControlsTitle = asyncControlsTitle;
        }

        public String getApplyModeLabel() {
            return applyModeLabel;
        }

        public void setApplyModeLabel(String applyModeLabel) {
            this.applyModeLabel = applyModeLabel;
        }

        public String getUpdateConsumersLabel() {
            return updateConsumersLabel;
        }

        public void setUpdateConsumersLabel(String updateConsumersLabel) {
            this.updateConsumersLabel = updateConsumersLabel;
        }

        public String getAsyncHint() {
            return asyncHint;
        }

        public void setAsyncHint(String asyncHint) {
            this.asyncHint = asyncHint;
        }

        public String getQueueBufferTitle() {
            return queueBufferTitle;
        }

        public void setQueueBufferTitle(String queueBufferTitle) {
            this.queueBufferTitle = queueBufferTitle;
        }

        public String getLoadOverviewTitle() {
            return loadOverviewTitle;
        }

        public void setLoadOverviewTitle(String loadOverviewTitle) {
            this.loadOverviewTitle = loadOverviewTitle;
        }

        public String getNoBufferMessage() {
            return noBufferMessage;
        }

        public void setNoBufferMessage(String noBufferMessage) {
            this.noBufferMessage = noBufferMessage;
        }

        public String getClearBufferLabel() {
            return clearBufferLabel;
        }

        public void setClearBufferLabel(String clearBufferLabel) {
            this.clearBufferLabel = clearBufferLabel;
        }

        public String getReplayEntryLabel() {
            return replayEntryLabel;
        }

        public void setReplayEntryLabel(String replayEntryLabel) {
            this.replayEntryLabel = replayEntryLabel;
        }

        public String getDeleteEntryLabel() {
            return deleteEntryLabel;
        }

        public void setDeleteEntryLabel(String deleteEntryLabel) {
            this.deleteEntryLabel = deleteEntryLabel;
        }

        public String getProcessedLabel() {
            return processedLabel;
        }

        public void setProcessedLabel(String processedLabel) {
            this.processedLabel = processedLabel;
        }

        public String getActiveWorkLabel() {
            return activeWorkLabel;
        }

        public void setActiveWorkLabel(String activeWorkLabel) {
            this.activeWorkLabel = activeWorkLabel;
        }

        public String getQueueDepthLabel() {
            return queueDepthLabel;
        }

        public void setQueueDepthLabel(String queueDepthLabel) {
            this.queueDepthLabel = queueDepthLabel;
        }

        public String getUtilizationLabel() {
            return utilizationLabel;
        }

        public void setUtilizationLabel(String utilizationLabel) {
            this.utilizationLabel = utilizationLabel;
        }

        public String getWorkPressureLabel() {
            return workPressureLabel;
        }

        public void setWorkPressureLabel(String workPressureLabel) {
            this.workPressureLabel = workPressureLabel;
        }

        public String getWorkerCapacityLabel() {
            return workerCapacityLabel;
        }

        public void setWorkerCapacityLabel(String workerCapacityLabel) {
            this.workerCapacityLabel = workerCapacityLabel;
        }

        public String getLiveRefreshLabel() {
            return liveRefreshLabel;
        }

        public void setLiveRefreshLabel(String liveRefreshLabel) {
            this.liveRefreshLabel = liveRefreshLabel;
        }

        public String getRefreshNowLabel() {
            return refreshNowLabel;
        }

        public void setRefreshNowLabel(String refreshNowLabel) {
            this.refreshNowLabel = refreshNowLabel;
        }

        public String getRefreshIntervalLabel() {
            return refreshIntervalLabel;
        }

        public void setRefreshIntervalLabel(String refreshIntervalLabel) {
            this.refreshIntervalLabel = refreshIntervalLabel;
        }

        public String getRefreshBufferLabel() {
            return refreshBufferLabel;
        }

        public void setRefreshBufferLabel(String refreshBufferLabel) {
            this.refreshBufferLabel = refreshBufferLabel;
        }

        public String getLiveBufferLabel() {
            return liveBufferLabel;
        }

        public void setLiveBufferLabel(String liveBufferLabel) {
            this.liveBufferLabel = liveBufferLabel;
        }

        public String getScrollTopLabel() {
            return scrollTopLabel;
        }

        public void setScrollTopLabel(String scrollTopLabel) {
            this.scrollTopLabel = scrollTopLabel;
        }

        public String getScrollBottomLabel() {
            return scrollBottomLabel;
        }

        public void setScrollBottomLabel(String scrollBottomLabel) {
            this.scrollBottomLabel = scrollBottomLabel;
        }

        public String getReadyStatusLabel() {
            return readyStatusLabel;
        }

        public void setReadyStatusLabel(String readyStatusLabel) {
            this.readyStatusLabel = readyStatusLabel;
        }

        public String getApplyingChangeMessage() {
            return applyingChangeMessage;
        }

        public void setApplyingChangeMessage(String applyingChangeMessage) {
            this.applyingChangeMessage = applyingChangeMessage;
        }

        public String getChangeAppliedMessage() {
            return changeAppliedMessage;
        }

        public void setChangeAppliedMessage(String changeAppliedMessage) {
            this.changeAppliedMessage = changeAppliedMessage;
        }

        public String getRequestFailedMessage() {
            return requestFailedMessage;
        }

        public void setRequestFailedMessage(String requestFailedMessage) {
            this.requestFailedMessage = requestFailedMessage;
        }

        public String getRefreshingMetricsMessage() {
            return refreshingMetricsMessage;
        }

        public void setRefreshingMetricsMessage(String refreshingMetricsMessage) {
            this.refreshingMetricsMessage = refreshingMetricsMessage;
        }

        public String getMetricsRefreshedMessage() {
            return metricsRefreshedMessage;
        }

        public void setMetricsRefreshedMessage(String metricsRefreshedMessage) {
            this.metricsRefreshedMessage = metricsRefreshedMessage;
        }

        public String getRefreshingBufferMessage() {
            return refreshingBufferMessage;
        }

        public void setRefreshingBufferMessage(String refreshingBufferMessage) {
            this.refreshingBufferMessage = refreshingBufferMessage;
        }

        public String getBufferRefreshedMessage() {
            return bufferRefreshedMessage;
        }

        public void setBufferRefreshedMessage(String bufferRefreshedMessage) {
            this.bufferRefreshedMessage = bufferRefreshedMessage;
        }

        public String getEntryIdLabel() {
            return entryIdLabel;
        }

        public void setEntryIdLabel(String entryIdLabel) {
            this.entryIdLabel = entryIdLabel;
        }

        public String getAttemptsLabel() {
            return attemptsLabel;
        }

        public void setAttemptsLabel(String attemptsLabel) {
            this.attemptsLabel = attemptsLabel;
        }

        public String getCodecLabel() {
            return codecLabel;
        }

        public void setCodecLabel(String codecLabel) {
            this.codecLabel = codecLabel;
        }

        public String getPayloadTypeLabel() {
            return payloadTypeLabel;
        }

        public void setPayloadTypeLabel(String payloadTypeLabel) {
            this.payloadTypeLabel = payloadTypeLabel;
        }

        public String getPayloadVersionLabel() {
            return payloadVersionLabel;
        }

        public void setPayloadVersionLabel(String payloadVersionLabel) {
            this.payloadVersionLabel = payloadVersionLabel;
        }

        public String getErrorTypeLabel() {
            return errorTypeLabel;
        }

        public void setErrorTypeLabel(String errorTypeLabel) {
            this.errorTypeLabel = errorTypeLabel;
        }

        public String getErrorCategoryLabel() {
            return errorCategoryLabel;
        }

        public void setErrorCategoryLabel(String errorCategoryLabel) {
            this.errorCategoryLabel = errorCategoryLabel;
        }

        public String getMethodsCountSuffix() {
            return methodsCountSuffix;
        }

        public void setMethodsCountSuffix(String methodsCountSuffix) {
            this.methodsCountSuffix = methodsCountSuffix;
        }

        public String getAsyncCapableSuffix() {
            return asyncCapableSuffix;
        }

        public void setAsyncCapableSuffix(String asyncCapableSuffix) {
            this.asyncCapableSuffix = asyncCapableSuffix;
        }

        public String getStoppedSuffix() {
            return stoppedSuffix;
        }

        public void setStoppedSuffix(String stoppedSuffix) {
            this.stoppedSuffix = stoppedSuffix;
        }

        public String getMethodsWithErrorsSuffix() {
            return methodsWithErrorsSuffix;
        }

        public void setMethodsWithErrorsSuffix(String methodsWithErrorsSuffix) {
            this.methodsWithErrorsSuffix = methodsWithErrorsSuffix;
        }

        public String getPendingLabel() {
            return pendingLabel;
        }

        public void setPendingLabel(String pendingLabel) {
            this.pendingLabel = pendingLabel;
        }

        public String getFailedLabel() {
            return failedLabel;
        }

        public void setFailedLabel(String failedLabel) {
            this.failedLabel = failedLabel;
        }

        public String getInProgressLabel() {
            return inProgressLabel;
        }

        public void setInProgressLabel(String inProgressLabel) {
            this.inProgressLabel = inProgressLabel;
        }
    }

    public static final class Dashboard {
        private int attentionLimit = 8;
        private int mediumUtilizationPercent = 40;
        private int highUtilizationPercent = 80;
        private Refresh refresh = new Refresh();

        public int getAttentionLimit() {
            return attentionLimit;
        }

        public void setAttentionLimit(int attentionLimit) {
            this.attentionLimit = attentionLimit;
        }

        public int getMediumUtilizationPercent() {
            return mediumUtilizationPercent;
        }

        public void setMediumUtilizationPercent(int mediumUtilizationPercent) {
            this.mediumUtilizationPercent = mediumUtilizationPercent;
        }

        public int getHighUtilizationPercent() {
            return highUtilizationPercent;
        }

        public void setHighUtilizationPercent(int highUtilizationPercent) {
            this.highUtilizationPercent = highUtilizationPercent;
        }

        public Refresh getRefresh() {
            return refresh;
        }

        public void setRefresh(Refresh refresh) {
            this.refresh = refresh == null ? new Refresh() : refresh;
        }
    }

    public static final class Refresh {
        private boolean liveRefreshEnabled = true;
        private boolean liveBufferEnabled = true;
        private int defaultIntervalMs = 5_000;
        private List<Integer> intervalOptionsMs = new ArrayList<>(List.of(3_000, 5_000, 10_000, 30_000));
        private int changeFlashMs = 1_400;
        private int successMessageAutoHideMs = 1_600;
        private int errorMessageAutoHideMs = 3_200;

        public boolean isLiveRefreshEnabled() {
            return liveRefreshEnabled;
        }

        public void setLiveRefreshEnabled(boolean liveRefreshEnabled) {
            this.liveRefreshEnabled = liveRefreshEnabled;
        }

        public boolean isLiveBufferEnabled() {
            return liveBufferEnabled;
        }

        public void setLiveBufferEnabled(boolean liveBufferEnabled) {
            this.liveBufferEnabled = liveBufferEnabled;
        }

        public int getDefaultIntervalMs() {
            return defaultIntervalMs;
        }

        public void setDefaultIntervalMs(int defaultIntervalMs) {
            this.defaultIntervalMs = defaultIntervalMs;
        }

        public List<Integer> getIntervalOptionsMs() {
            return intervalOptionsMs;
        }

        public void setIntervalOptionsMs(List<Integer> intervalOptionsMs) {
            this.intervalOptionsMs = intervalOptionsMs == null ? new ArrayList<>() : new ArrayList<>(intervalOptionsMs);
        }

        public int getChangeFlashMs() {
            return changeFlashMs;
        }

        public void setChangeFlashMs(int changeFlashMs) {
            this.changeFlashMs = changeFlashMs;
        }

        public int getSuccessMessageAutoHideMs() {
            return successMessageAutoHideMs;
        }

        public void setSuccessMessageAutoHideMs(int successMessageAutoHideMs) {
            this.successMessageAutoHideMs = successMessageAutoHideMs;
        }

        public int getErrorMessageAutoHideMs() {
            return errorMessageAutoHideMs;
        }

        public void setErrorMessageAutoHideMs(int errorMessageAutoHideMs) {
            this.errorMessageAutoHideMs = errorMessageAutoHideMs;
        }
    }

    public static final class ServiceConfiguration {
        private Map<String, MethodConfiguration> methods = new LinkedHashMap<>();

        public Map<String, MethodConfiguration> getMethods() {
            return methods;
        }

        public void setMethods(Map<String, MethodConfiguration> methods) {
            this.methods = methods == null ? new LinkedHashMap<>() : new LinkedHashMap<>(methods);
        }
    }

    public static final class MethodConfiguration {
        private Boolean enabled;
        private Integer maxConcurrency;
        private String unavailableMessage;
        private ExecutionMode executionMode;
        private Integer consumerThreads;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public Integer getMaxConcurrency() {
            return maxConcurrency;
        }

        public void setMaxConcurrency(Integer maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
        }

        public String getUnavailableMessage() {
            return unavailableMessage;
        }

        public void setUnavailableMessage(String unavailableMessage) {
            this.unavailableMessage = unavailableMessage;
        }

        public ExecutionMode getExecutionMode() {
            return executionMode;
        }

        public void setExecutionMode(ExecutionMode executionMode) {
            this.executionMode = executionMode;
        }

        public Integer getConsumerThreads() {
            return consumerThreads;
        }

        public void setConsumerThreads(Integer consumerThreads) {
            this.consumerThreads = consumerThreads;
        }
    }
}
