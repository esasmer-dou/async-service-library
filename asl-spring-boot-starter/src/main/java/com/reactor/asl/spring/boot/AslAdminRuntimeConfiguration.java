package com.reactor.asl.spring.boot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class AslAdminRuntimeConfiguration {
    private final String pagePath;
    private final String apiPath;
    private volatile int bufferPreviewLimit;
    private volatile int attentionLimit;
    private volatile int mediumUtilizationPercent;
    private volatile int highUtilizationPercent;
    private volatile RefreshSettings refresh;
    private UiSnapshot ui;

    public AslAdminRuntimeConfiguration(AslAdminProperties properties) {
        Objects.requireNonNull(properties, "properties");
        this.pagePath = properties.getPath();
        this.apiPath = properties.getApiPath();
        this.bufferPreviewLimit = properties.getBufferPreviewLimit();
        this.attentionLimit = properties.getDashboard().getAttentionLimit();
        this.mediumUtilizationPercent = properties.getDashboard().getMediumUtilizationPercent();
        this.highUtilizationPercent = properties.getDashboard().getHighUtilizationPercent();
        this.refresh = RefreshSettings.from(properties.getDashboard().getRefresh());
        this.ui = UiSnapshot.from(properties.getUi());
        validateThresholds(this.mediumUtilizationPercent, this.highUtilizationPercent);
    }

    public synchronized ConfigSnapshot snapshot() {
        return new ConfigSnapshot(pagePath, apiPath, bufferPreviewLimit, attentionLimit, mediumUtilizationPercent, highUtilizationPercent, refresh, ui);
    }

    public synchronized UiSnapshot ui() { return ui; }

    public synchronized UiSnapshot updateUi(UiPatch patch) {
        Objects.requireNonNull(patch, "patch");
        ui = ui.merge(patch);
        return ui;
    }

    public int bufferPreviewLimit() { return bufferPreviewLimit; }
    public int attentionLimit() { return attentionLimit; }
    public int mediumUtilizationPercent() { return mediumUtilizationPercent; }
    public int highUtilizationPercent() { return highUtilizationPercent; }
    public synchronized RefreshSettings refresh() { return refresh; }
    public String pagePath() { return pagePath; }
    public String apiPath() { return apiPath; }

    public synchronized int setBufferPreviewLimit(int bufferPreviewLimit) {
        if (bufferPreviewLimit <= 0) throw new IllegalArgumentException("bufferPreviewLimit must be positive");
        this.bufferPreviewLimit = bufferPreviewLimit;
        return this.bufferPreviewLimit;
    }

    public synchronized int setAttentionLimit(int attentionLimit) {
        if (attentionLimit <= 0) throw new IllegalArgumentException("attentionLimit must be positive");
        this.attentionLimit = attentionLimit;
        return this.attentionLimit;
    }

    public synchronized void setUtilizationThresholds(int mediumUtilizationPercent, int highUtilizationPercent) {
        validateThresholds(mediumUtilizationPercent, highUtilizationPercent);
        this.mediumUtilizationPercent = mediumUtilizationPercent;
        this.highUtilizationPercent = highUtilizationPercent;
    }

    public synchronized RefreshSettings updateRefresh(RefreshPatch patch) {
        Objects.requireNonNull(patch, "patch");
        refresh = refresh.merge(patch);
        return refresh;
    }

    private static void validateThresholds(int mediumUtilizationPercent, int highUtilizationPercent) {
        if (mediumUtilizationPercent < 0 || mediumUtilizationPercent > 100) throw new IllegalArgumentException("mediumUtilizationPercent must be between 0 and 100");
        if (highUtilizationPercent < 0 || highUtilizationPercent > 100) throw new IllegalArgumentException("highUtilizationPercent must be between 0 and 100");
        if (mediumUtilizationPercent > highUtilizationPercent) throw new IllegalArgumentException("mediumUtilizationPercent must be less than or equal to highUtilizationPercent");
    }

    public record ConfigSnapshot(
            String pagePath,
            String apiPath,
            int bufferPreviewLimit,
            int attentionLimit,
            int mediumUtilizationPercent,
            int highUtilizationPercent,
            RefreshSettings refresh,
            UiSnapshot ui
    ) { }

    public record RefreshSettings(
            boolean liveRefreshEnabled,
            boolean liveBufferEnabled,
            int defaultIntervalMs,
            List<Integer> intervalOptionsMs,
            int changeFlashMs,
            int successMessageAutoHideMs,
            int errorMessageAutoHideMs
    ) {
        static RefreshSettings from(AslAdminProperties.Refresh refresh) {
            return new RefreshSettings(
                    refresh.isLiveRefreshEnabled(),
                    refresh.isLiveBufferEnabled(),
                    refresh.getDefaultIntervalMs(),
                    List.copyOf(refresh.getIntervalOptionsMs()),
                    refresh.getChangeFlashMs(),
                    refresh.getSuccessMessageAutoHideMs(),
                    refresh.getErrorMessageAutoHideMs()
            );
        }

        RefreshSettings merge(RefreshPatch patch) {
            List<Integer> mergedIntervals = patch.intervalOptionsMs == null ? intervalOptionsMs : List.copyOf(new ArrayList<>(patch.intervalOptionsMs));
            int mergedDefault = patch.defaultIntervalMs == null ? defaultIntervalMs : patch.defaultIntervalMs;
            validate(mergedDefault, mergedIntervals, patch.changeFlashMs == null ? changeFlashMs : patch.changeFlashMs,
                    patch.successMessageAutoHideMs == null ? successMessageAutoHideMs : patch.successMessageAutoHideMs,
                    patch.errorMessageAutoHideMs == null ? errorMessageAutoHideMs : patch.errorMessageAutoHideMs);
            return new RefreshSettings(
                    patch.liveRefreshEnabled == null ? liveRefreshEnabled : patch.liveRefreshEnabled,
                    patch.liveBufferEnabled == null ? liveBufferEnabled : patch.liveBufferEnabled,
                    mergedDefault,
                    mergedIntervals,
                    patch.changeFlashMs == null ? changeFlashMs : patch.changeFlashMs,
                    patch.successMessageAutoHideMs == null ? successMessageAutoHideMs : patch.successMessageAutoHideMs,
                    patch.errorMessageAutoHideMs == null ? errorMessageAutoHideMs : patch.errorMessageAutoHideMs
            );
        }

        private static void validate(int defaultIntervalMs, List<Integer> intervalOptionsMs, int changeFlashMs, int successMessageAutoHideMs, int errorMessageAutoHideMs) {
            if (defaultIntervalMs <= 0) throw new IllegalArgumentException("defaultIntervalMs must be positive");
            if (intervalOptionsMs == null || intervalOptionsMs.isEmpty()) throw new IllegalArgumentException("intervalOptionsMs must not be empty");
            if (intervalOptionsMs.stream().anyMatch(value -> value == null || value <= 0)) throw new IllegalArgumentException("intervalOptionsMs must contain only positive values");
            if (!intervalOptionsMs.contains(defaultIntervalMs)) throw new IllegalArgumentException("defaultIntervalMs must exist in intervalOptionsMs");
            if (changeFlashMs <= 0 || successMessageAutoHideMs <= 0 || errorMessageAutoHideMs <= 0) throw new IllegalArgumentException("refresh timing values must be positive");
        }
    }

    public static final class RefreshPatch {
        private Boolean liveRefreshEnabled;
        private Boolean liveBufferEnabled;
        private Integer defaultIntervalMs;
        private List<Integer> intervalOptionsMs;
        private Integer changeFlashMs;
        private Integer successMessageAutoHideMs;
        private Integer errorMessageAutoHideMs;

        public Boolean getLiveRefreshEnabled() { return liveRefreshEnabled; }
        public void setLiveRefreshEnabled(Boolean liveRefreshEnabled) { this.liveRefreshEnabled = liveRefreshEnabled; }
        public Boolean getLiveBufferEnabled() { return liveBufferEnabled; }
        public void setLiveBufferEnabled(Boolean liveBufferEnabled) { this.liveBufferEnabled = liveBufferEnabled; }
        public Integer getDefaultIntervalMs() { return defaultIntervalMs; }
        public void setDefaultIntervalMs(Integer defaultIntervalMs) { this.defaultIntervalMs = defaultIntervalMs; }
        public List<Integer> getIntervalOptionsMs() { return intervalOptionsMs; }
        public void setIntervalOptionsMs(List<Integer> intervalOptionsMs) { this.intervalOptionsMs = intervalOptionsMs; }
        public Integer getChangeFlashMs() { return changeFlashMs; }
        public void setChangeFlashMs(Integer changeFlashMs) { this.changeFlashMs = changeFlashMs; }
        public Integer getSuccessMessageAutoHideMs() { return successMessageAutoHideMs; }
        public void setSuccessMessageAutoHideMs(Integer successMessageAutoHideMs) { this.successMessageAutoHideMs = successMessageAutoHideMs; }
        public Integer getErrorMessageAutoHideMs() { return errorMessageAutoHideMs; }
        public void setErrorMessageAutoHideMs(Integer errorMessageAutoHideMs) { this.errorMessageAutoHideMs = errorMessageAutoHideMs; }
    }

    public record UiSnapshot(
            String pageTitle, String heroTitle, String heroDescription, String restBadgePrefix,
            String emptyTitle, String emptyDescription, String servicesTitle, String serviceSearchPlaceholder,
            String serviceTabNote, String serviceDetailNote, String methodsTitle, String allLabel,
            String noParametersLabel, String runningLabel, String stoppedLabel, String syncModeLabel,
            String asyncLabel, String errorLabel, String successLabel, String rejectedLabel,
            String loadLabel, String peakInFlightLabel, String executionModeLabel, String consumerThreadsLabel,
            String lastErrorLabel, String noneLabel, String methodStateTitle, String startMethodLabel,
            String stopMethodLabel, String disablePlaceholder, String methodStateHint, String syncConcurrencyTitle,
            String updateLimitLabel, String syncConcurrencyHint, String asyncControlsTitle, String applyModeLabel,
            String updateConsumersLabel, String asyncHint, String queueBufferTitle, String loadOverviewTitle,
            String noBufferMessage, String clearBufferLabel, String replayEntryLabel, String deleteEntryLabel,
            String processedLabel, String activeWorkLabel, String queueDepthLabel, String utilizationLabel,
            String workPressureLabel, String workerCapacityLabel, String liveRefreshLabel, String refreshNowLabel,
            String refreshIntervalLabel, String refreshBufferLabel, String liveBufferLabel, String scrollTopLabel,
            String scrollBottomLabel, String readyStatusLabel, String applyingChangeMessage, String changeAppliedMessage,
            String requestFailedMessage, String refreshingMetricsMessage, String metricsRefreshedMessage,
            String refreshingBufferMessage, String bufferRefreshedMessage, String entryIdLabel, String attemptsLabel,
            String codecLabel, String payloadTypeLabel, String payloadVersionLabel, String errorTypeLabel,
            String errorCategoryLabel, String methodsCountSuffix, String asyncCapableSuffix, String stoppedSuffix,
            String methodsWithErrorsSuffix, String pendingLabel, String failedLabel, String inProgressLabel
    ) {
        static UiSnapshot from(AslAdminProperties.Ui ui) {
            return new UiSnapshot(
                    ui.getPageTitle(), ui.getHeroTitle(), ui.getHeroDescription(), ui.getRestBadgePrefix(),
                    ui.getEmptyTitle(), ui.getEmptyDescription(), ui.getServicesTitle(), ui.getServiceSearchPlaceholder(),
                    ui.getServiceTabNote(), ui.getServiceDetailNote(), ui.getMethodsTitle(), ui.getAllLabel(),
                    ui.getNoParametersLabel(), ui.getRunningLabel(), ui.getStoppedLabel(), ui.getSyncModeLabel(),
                    ui.getAsyncLabel(), ui.getErrorLabel(), ui.getSuccessLabel(), ui.getRejectedLabel(),
                    ui.getLoadLabel(), ui.getPeakInFlightLabel(), ui.getExecutionModeLabel(), ui.getConsumerThreadsLabel(),
                    ui.getLastErrorLabel(), ui.getNoneLabel(), ui.getMethodStateTitle(), ui.getStartMethodLabel(),
                    ui.getStopMethodLabel(), ui.getDisablePlaceholder(), ui.getMethodStateHint(), ui.getSyncConcurrencyTitle(),
                    ui.getUpdateLimitLabel(), ui.getSyncConcurrencyHint(), ui.getAsyncControlsTitle(), ui.getApplyModeLabel(),
                    ui.getUpdateConsumersLabel(), ui.getAsyncHint(), ui.getQueueBufferTitle(), ui.getLoadOverviewTitle(),
                    ui.getNoBufferMessage(), ui.getClearBufferLabel(), ui.getReplayEntryLabel(), ui.getDeleteEntryLabel(),
                    ui.getProcessedLabel(), ui.getActiveWorkLabel(), ui.getQueueDepthLabel(), ui.getUtilizationLabel(),
                    ui.getWorkPressureLabel(), ui.getWorkerCapacityLabel(), ui.getLiveRefreshLabel(), ui.getRefreshNowLabel(),
                    ui.getRefreshIntervalLabel(), ui.getRefreshBufferLabel(), ui.getLiveBufferLabel(), ui.getScrollTopLabel(),
                    ui.getScrollBottomLabel(), ui.getReadyStatusLabel(), ui.getApplyingChangeMessage(), ui.getChangeAppliedMessage(),
                    ui.getRequestFailedMessage(), ui.getRefreshingMetricsMessage(), ui.getMetricsRefreshedMessage(),
                    ui.getRefreshingBufferMessage(), ui.getBufferRefreshedMessage(), ui.getEntryIdLabel(), ui.getAttemptsLabel(),
                    ui.getCodecLabel(), ui.getPayloadTypeLabel(), ui.getPayloadVersionLabel(), ui.getErrorTypeLabel(),
                    ui.getErrorCategoryLabel(), ui.getMethodsCountSuffix(), ui.getAsyncCapableSuffix(), ui.getStoppedSuffix(),
                    ui.getMethodsWithErrorsSuffix(), ui.getPendingLabel(), ui.getFailedLabel(), ui.getInProgressLabel()
            );
        }

        UiSnapshot merge(UiPatch patch) {
            return new UiSnapshot(
                    firstNonNull(patch.pageTitle, pageTitle), firstNonNull(patch.heroTitle, heroTitle), firstNonNull(patch.heroDescription, heroDescription), firstNonNull(patch.restBadgePrefix, restBadgePrefix),
                    firstNonNull(patch.emptyTitle, emptyTitle), firstNonNull(patch.emptyDescription, emptyDescription), firstNonNull(patch.servicesTitle, servicesTitle), firstNonNull(patch.serviceSearchPlaceholder, serviceSearchPlaceholder),
                    firstNonNull(patch.serviceTabNote, serviceTabNote), firstNonNull(patch.serviceDetailNote, serviceDetailNote), firstNonNull(patch.methodsTitle, methodsTitle), firstNonNull(patch.allLabel, allLabel),
                    firstNonNull(patch.noParametersLabel, noParametersLabel), firstNonNull(patch.runningLabel, runningLabel), firstNonNull(patch.stoppedLabel, stoppedLabel), firstNonNull(patch.syncModeLabel, syncModeLabel),
                    firstNonNull(patch.asyncLabel, asyncLabel), firstNonNull(patch.errorLabel, errorLabel), firstNonNull(patch.successLabel, successLabel), firstNonNull(patch.rejectedLabel, rejectedLabel),
                    firstNonNull(patch.loadLabel, loadLabel), firstNonNull(patch.peakInFlightLabel, peakInFlightLabel), firstNonNull(patch.executionModeLabel, executionModeLabel), firstNonNull(patch.consumerThreadsLabel, consumerThreadsLabel),
                    firstNonNull(patch.lastErrorLabel, lastErrorLabel), firstNonNull(patch.noneLabel, noneLabel), firstNonNull(patch.methodStateTitle, methodStateTitle), firstNonNull(patch.startMethodLabel, startMethodLabel),
                    firstNonNull(patch.stopMethodLabel, stopMethodLabel), firstNonNull(patch.disablePlaceholder, disablePlaceholder), firstNonNull(patch.methodStateHint, methodStateHint), firstNonNull(patch.syncConcurrencyTitle, syncConcurrencyTitle),
                    firstNonNull(patch.updateLimitLabel, updateLimitLabel), firstNonNull(patch.syncConcurrencyHint, syncConcurrencyHint), firstNonNull(patch.asyncControlsTitle, asyncControlsTitle), firstNonNull(patch.applyModeLabel, applyModeLabel),
                    firstNonNull(patch.updateConsumersLabel, updateConsumersLabel), firstNonNull(patch.asyncHint, asyncHint), firstNonNull(patch.queueBufferTitle, queueBufferTitle), firstNonNull(patch.loadOverviewTitle, loadOverviewTitle),
                    firstNonNull(patch.noBufferMessage, noBufferMessage), firstNonNull(patch.clearBufferLabel, clearBufferLabel), firstNonNull(patch.replayEntryLabel, replayEntryLabel), firstNonNull(patch.deleteEntryLabel, deleteEntryLabel),
                    firstNonNull(patch.processedLabel, processedLabel), firstNonNull(patch.activeWorkLabel, activeWorkLabel), firstNonNull(patch.queueDepthLabel, queueDepthLabel), firstNonNull(patch.utilizationLabel, utilizationLabel),
                    firstNonNull(patch.workPressureLabel, workPressureLabel), firstNonNull(patch.workerCapacityLabel, workerCapacityLabel), firstNonNull(patch.liveRefreshLabel, liveRefreshLabel), firstNonNull(patch.refreshNowLabel, refreshNowLabel),
                    firstNonNull(patch.refreshIntervalLabel, refreshIntervalLabel), firstNonNull(patch.refreshBufferLabel, refreshBufferLabel), firstNonNull(patch.liveBufferLabel, liveBufferLabel), firstNonNull(patch.scrollTopLabel, scrollTopLabel),
                    firstNonNull(patch.scrollBottomLabel, scrollBottomLabel), firstNonNull(patch.readyStatusLabel, readyStatusLabel), firstNonNull(patch.applyingChangeMessage, applyingChangeMessage), firstNonNull(patch.changeAppliedMessage, changeAppliedMessage),
                    firstNonNull(patch.requestFailedMessage, requestFailedMessage), firstNonNull(patch.refreshingMetricsMessage, refreshingMetricsMessage), firstNonNull(patch.metricsRefreshedMessage, metricsRefreshedMessage),
                    firstNonNull(patch.refreshingBufferMessage, refreshingBufferMessage), firstNonNull(patch.bufferRefreshedMessage, bufferRefreshedMessage), firstNonNull(patch.entryIdLabel, entryIdLabel), firstNonNull(patch.attemptsLabel, attemptsLabel),
                    firstNonNull(patch.codecLabel, codecLabel), firstNonNull(patch.payloadTypeLabel, payloadTypeLabel), firstNonNull(patch.payloadVersionLabel, payloadVersionLabel), firstNonNull(patch.errorTypeLabel, errorTypeLabel),
                    firstNonNull(patch.errorCategoryLabel, errorCategoryLabel), firstNonNull(patch.methodsCountSuffix, methodsCountSuffix), firstNonNull(patch.asyncCapableSuffix, asyncCapableSuffix), firstNonNull(patch.stoppedSuffix, stoppedSuffix),
                    firstNonNull(patch.methodsWithErrorsSuffix, methodsWithErrorsSuffix), firstNonNull(patch.pendingLabel, pendingLabel), firstNonNull(patch.failedLabel, failedLabel), firstNonNull(patch.inProgressLabel, inProgressLabel)
            );
        }

        private static String firstNonNull(String value, String fallback) { return value == null ? fallback : value; }
    }

    public static final class UiPatch {
        private String pageTitle, heroTitle, heroDescription, restBadgePrefix, emptyTitle, emptyDescription, servicesTitle, serviceSearchPlaceholder,
                serviceTabNote, serviceDetailNote, methodsTitle, allLabel, noParametersLabel, runningLabel, stoppedLabel, syncModeLabel,
                asyncLabel, errorLabel, successLabel, rejectedLabel, loadLabel, peakInFlightLabel, executionModeLabel, consumerThreadsLabel,
                lastErrorLabel, noneLabel, methodStateTitle, startMethodLabel, stopMethodLabel, disablePlaceholder, methodStateHint,
                syncConcurrencyTitle, updateLimitLabel, syncConcurrencyHint, asyncControlsTitle, applyModeLabel, updateConsumersLabel,
                asyncHint, queueBufferTitle, loadOverviewTitle, noBufferMessage, clearBufferLabel, replayEntryLabel, deleteEntryLabel,
                processedLabel, activeWorkLabel, queueDepthLabel, utilizationLabel, workPressureLabel, workerCapacityLabel, liveRefreshLabel,
                refreshNowLabel, refreshIntervalLabel, refreshBufferLabel, liveBufferLabel, scrollTopLabel, scrollBottomLabel, readyStatusLabel,
                applyingChangeMessage, changeAppliedMessage, requestFailedMessage, refreshingMetricsMessage, metricsRefreshedMessage,
                refreshingBufferMessage, bufferRefreshedMessage, entryIdLabel, attemptsLabel, codecLabel, payloadTypeLabel, payloadVersionLabel,
                errorTypeLabel, errorCategoryLabel, methodsCountSuffix, asyncCapableSuffix, stoppedSuffix, methodsWithErrorsSuffix, pendingLabel,
                failedLabel, inProgressLabel;

        public String getPageTitle() { return pageTitle; } public void setPageTitle(String pageTitle) { this.pageTitle = pageTitle; }
        public String getHeroTitle() { return heroTitle; } public void setHeroTitle(String heroTitle) { this.heroTitle = heroTitle; }
        public String getHeroDescription() { return heroDescription; } public void setHeroDescription(String heroDescription) { this.heroDescription = heroDescription; }
        public String getRestBadgePrefix() { return restBadgePrefix; } public void setRestBadgePrefix(String restBadgePrefix) { this.restBadgePrefix = restBadgePrefix; }
        public String getEmptyTitle() { return emptyTitle; } public void setEmptyTitle(String emptyTitle) { this.emptyTitle = emptyTitle; }
        public String getEmptyDescription() { return emptyDescription; } public void setEmptyDescription(String emptyDescription) { this.emptyDescription = emptyDescription; }
        public String getServicesTitle() { return servicesTitle; } public void setServicesTitle(String servicesTitle) { this.servicesTitle = servicesTitle; }
        public String getServiceSearchPlaceholder() { return serviceSearchPlaceholder; } public void setServiceSearchPlaceholder(String serviceSearchPlaceholder) { this.serviceSearchPlaceholder = serviceSearchPlaceholder; }
        public String getServiceTabNote() { return serviceTabNote; } public void setServiceTabNote(String serviceTabNote) { this.serviceTabNote = serviceTabNote; }
        public String getServiceDetailNote() { return serviceDetailNote; } public void setServiceDetailNote(String serviceDetailNote) { this.serviceDetailNote = serviceDetailNote; }
        public String getMethodsTitle() { return methodsTitle; } public void setMethodsTitle(String methodsTitle) { this.methodsTitle = methodsTitle; }
        public String getAllLabel() { return allLabel; } public void setAllLabel(String allLabel) { this.allLabel = allLabel; }
        public String getNoParametersLabel() { return noParametersLabel; } public void setNoParametersLabel(String noParametersLabel) { this.noParametersLabel = noParametersLabel; }
        public String getRunningLabel() { return runningLabel; } public void setRunningLabel(String runningLabel) { this.runningLabel = runningLabel; }
        public String getStoppedLabel() { return stoppedLabel; } public void setStoppedLabel(String stoppedLabel) { this.stoppedLabel = stoppedLabel; }
        public String getSyncModeLabel() { return syncModeLabel; } public void setSyncModeLabel(String syncModeLabel) { this.syncModeLabel = syncModeLabel; }
        public String getAsyncLabel() { return asyncLabel; } public void setAsyncLabel(String asyncLabel) { this.asyncLabel = asyncLabel; }
        public String getErrorLabel() { return errorLabel; } public void setErrorLabel(String errorLabel) { this.errorLabel = errorLabel; }
        public String getSuccessLabel() { return successLabel; } public void setSuccessLabel(String successLabel) { this.successLabel = successLabel; }
        public String getRejectedLabel() { return rejectedLabel; } public void setRejectedLabel(String rejectedLabel) { this.rejectedLabel = rejectedLabel; }
        public String getLoadLabel() { return loadLabel; } public void setLoadLabel(String loadLabel) { this.loadLabel = loadLabel; }
        public String getPeakInFlightLabel() { return peakInFlightLabel; } public void setPeakInFlightLabel(String peakInFlightLabel) { this.peakInFlightLabel = peakInFlightLabel; }
        public String getExecutionModeLabel() { return executionModeLabel; } public void setExecutionModeLabel(String executionModeLabel) { this.executionModeLabel = executionModeLabel; }
        public String getConsumerThreadsLabel() { return consumerThreadsLabel; } public void setConsumerThreadsLabel(String consumerThreadsLabel) { this.consumerThreadsLabel = consumerThreadsLabel; }
        public String getLastErrorLabel() { return lastErrorLabel; } public void setLastErrorLabel(String lastErrorLabel) { this.lastErrorLabel = lastErrorLabel; }
        public String getNoneLabel() { return noneLabel; } public void setNoneLabel(String noneLabel) { this.noneLabel = noneLabel; }
        public String getMethodStateTitle() { return methodStateTitle; } public void setMethodStateTitle(String methodStateTitle) { this.methodStateTitle = methodStateTitle; }
        public String getStartMethodLabel() { return startMethodLabel; } public void setStartMethodLabel(String startMethodLabel) { this.startMethodLabel = startMethodLabel; }
        public String getStopMethodLabel() { return stopMethodLabel; } public void setStopMethodLabel(String stopMethodLabel) { this.stopMethodLabel = stopMethodLabel; }
        public String getDisablePlaceholder() { return disablePlaceholder; } public void setDisablePlaceholder(String disablePlaceholder) { this.disablePlaceholder = disablePlaceholder; }
        public String getMethodStateHint() { return methodStateHint; } public void setMethodStateHint(String methodStateHint) { this.methodStateHint = methodStateHint; }
        public String getSyncConcurrencyTitle() { return syncConcurrencyTitle; } public void setSyncConcurrencyTitle(String syncConcurrencyTitle) { this.syncConcurrencyTitle = syncConcurrencyTitle; }
        public String getUpdateLimitLabel() { return updateLimitLabel; } public void setUpdateLimitLabel(String updateLimitLabel) { this.updateLimitLabel = updateLimitLabel; }
        public String getSyncConcurrencyHint() { return syncConcurrencyHint; } public void setSyncConcurrencyHint(String syncConcurrencyHint) { this.syncConcurrencyHint = syncConcurrencyHint; }
        public String getAsyncControlsTitle() { return asyncControlsTitle; } public void setAsyncControlsTitle(String asyncControlsTitle) { this.asyncControlsTitle = asyncControlsTitle; }
        public String getApplyModeLabel() { return applyModeLabel; } public void setApplyModeLabel(String applyModeLabel) { this.applyModeLabel = applyModeLabel; }
        public String getUpdateConsumersLabel() { return updateConsumersLabel; } public void setUpdateConsumersLabel(String updateConsumersLabel) { this.updateConsumersLabel = updateConsumersLabel; }
        public String getAsyncHint() { return asyncHint; } public void setAsyncHint(String asyncHint) { this.asyncHint = asyncHint; }
        public String getQueueBufferTitle() { return queueBufferTitle; } public void setQueueBufferTitle(String queueBufferTitle) { this.queueBufferTitle = queueBufferTitle; }
        public String getLoadOverviewTitle() { return loadOverviewTitle; } public void setLoadOverviewTitle(String loadOverviewTitle) { this.loadOverviewTitle = loadOverviewTitle; }
        public String getNoBufferMessage() { return noBufferMessage; } public void setNoBufferMessage(String noBufferMessage) { this.noBufferMessage = noBufferMessage; }
        public String getClearBufferLabel() { return clearBufferLabel; } public void setClearBufferLabel(String clearBufferLabel) { this.clearBufferLabel = clearBufferLabel; }
        public String getReplayEntryLabel() { return replayEntryLabel; } public void setReplayEntryLabel(String replayEntryLabel) { this.replayEntryLabel = replayEntryLabel; }
        public String getDeleteEntryLabel() { return deleteEntryLabel; } public void setDeleteEntryLabel(String deleteEntryLabel) { this.deleteEntryLabel = deleteEntryLabel; }
        public String getProcessedLabel() { return processedLabel; } public void setProcessedLabel(String processedLabel) { this.processedLabel = processedLabel; }
        public String getActiveWorkLabel() { return activeWorkLabel; } public void setActiveWorkLabel(String activeWorkLabel) { this.activeWorkLabel = activeWorkLabel; }
        public String getQueueDepthLabel() { return queueDepthLabel; } public void setQueueDepthLabel(String queueDepthLabel) { this.queueDepthLabel = queueDepthLabel; }
        public String getUtilizationLabel() { return utilizationLabel; } public void setUtilizationLabel(String utilizationLabel) { this.utilizationLabel = utilizationLabel; }
        public String getWorkPressureLabel() { return workPressureLabel; } public void setWorkPressureLabel(String workPressureLabel) { this.workPressureLabel = workPressureLabel; }
        public String getWorkerCapacityLabel() { return workerCapacityLabel; } public void setWorkerCapacityLabel(String workerCapacityLabel) { this.workerCapacityLabel = workerCapacityLabel; }
        public String getLiveRefreshLabel() { return liveRefreshLabel; } public void setLiveRefreshLabel(String liveRefreshLabel) { this.liveRefreshLabel = liveRefreshLabel; }
        public String getRefreshNowLabel() { return refreshNowLabel; } public void setRefreshNowLabel(String refreshNowLabel) { this.refreshNowLabel = refreshNowLabel; }
        public String getRefreshIntervalLabel() { return refreshIntervalLabel; } public void setRefreshIntervalLabel(String refreshIntervalLabel) { this.refreshIntervalLabel = refreshIntervalLabel; }
        public String getRefreshBufferLabel() { return refreshBufferLabel; } public void setRefreshBufferLabel(String refreshBufferLabel) { this.refreshBufferLabel = refreshBufferLabel; }
        public String getLiveBufferLabel() { return liveBufferLabel; } public void setLiveBufferLabel(String liveBufferLabel) { this.liveBufferLabel = liveBufferLabel; }
        public String getScrollTopLabel() { return scrollTopLabel; } public void setScrollTopLabel(String scrollTopLabel) { this.scrollTopLabel = scrollTopLabel; }
        public String getScrollBottomLabel() { return scrollBottomLabel; } public void setScrollBottomLabel(String scrollBottomLabel) { this.scrollBottomLabel = scrollBottomLabel; }
        public String getReadyStatusLabel() { return readyStatusLabel; } public void setReadyStatusLabel(String readyStatusLabel) { this.readyStatusLabel = readyStatusLabel; }
        public String getApplyingChangeMessage() { return applyingChangeMessage; } public void setApplyingChangeMessage(String applyingChangeMessage) { this.applyingChangeMessage = applyingChangeMessage; }
        public String getChangeAppliedMessage() { return changeAppliedMessage; } public void setChangeAppliedMessage(String changeAppliedMessage) { this.changeAppliedMessage = changeAppliedMessage; }
        public String getRequestFailedMessage() { return requestFailedMessage; } public void setRequestFailedMessage(String requestFailedMessage) { this.requestFailedMessage = requestFailedMessage; }
        public String getRefreshingMetricsMessage() { return refreshingMetricsMessage; } public void setRefreshingMetricsMessage(String refreshingMetricsMessage) { this.refreshingMetricsMessage = refreshingMetricsMessage; }
        public String getMetricsRefreshedMessage() { return metricsRefreshedMessage; } public void setMetricsRefreshedMessage(String metricsRefreshedMessage) { this.metricsRefreshedMessage = metricsRefreshedMessage; }
        public String getRefreshingBufferMessage() { return refreshingBufferMessage; } public void setRefreshingBufferMessage(String refreshingBufferMessage) { this.refreshingBufferMessage = refreshingBufferMessage; }
        public String getBufferRefreshedMessage() { return bufferRefreshedMessage; } public void setBufferRefreshedMessage(String bufferRefreshedMessage) { this.bufferRefreshedMessage = bufferRefreshedMessage; }
        public String getEntryIdLabel() { return entryIdLabel; } public void setEntryIdLabel(String entryIdLabel) { this.entryIdLabel = entryIdLabel; }
        public String getAttemptsLabel() { return attemptsLabel; } public void setAttemptsLabel(String attemptsLabel) { this.attemptsLabel = attemptsLabel; }
        public String getCodecLabel() { return codecLabel; } public void setCodecLabel(String codecLabel) { this.codecLabel = codecLabel; }
        public String getPayloadTypeLabel() { return payloadTypeLabel; } public void setPayloadTypeLabel(String payloadTypeLabel) { this.payloadTypeLabel = payloadTypeLabel; }
        public String getPayloadVersionLabel() { return payloadVersionLabel; } public void setPayloadVersionLabel(String payloadVersionLabel) { this.payloadVersionLabel = payloadVersionLabel; }
        public String getErrorTypeLabel() { return errorTypeLabel; } public void setErrorTypeLabel(String errorTypeLabel) { this.errorTypeLabel = errorTypeLabel; }
        public String getErrorCategoryLabel() { return errorCategoryLabel; } public void setErrorCategoryLabel(String errorCategoryLabel) { this.errorCategoryLabel = errorCategoryLabel; }
        public String getMethodsCountSuffix() { return methodsCountSuffix; } public void setMethodsCountSuffix(String methodsCountSuffix) { this.methodsCountSuffix = methodsCountSuffix; }
        public String getAsyncCapableSuffix() { return asyncCapableSuffix; } public void setAsyncCapableSuffix(String asyncCapableSuffix) { this.asyncCapableSuffix = asyncCapableSuffix; }
        public String getStoppedSuffix() { return stoppedSuffix; } public void setStoppedSuffix(String stoppedSuffix) { this.stoppedSuffix = stoppedSuffix; }
        public String getMethodsWithErrorsSuffix() { return methodsWithErrorsSuffix; } public void setMethodsWithErrorsSuffix(String methodsWithErrorsSuffix) { this.methodsWithErrorsSuffix = methodsWithErrorsSuffix; }
        public String getPendingLabel() { return pendingLabel; } public void setPendingLabel(String pendingLabel) { this.pendingLabel = pendingLabel; }
        public String getFailedLabel() { return failedLabel; } public void setFailedLabel(String failedLabel) { this.failedLabel = failedLabel; }
        public String getInProgressLabel() { return inProgressLabel; } public void setInProgressLabel(String inProgressLabel) { this.inProgressLabel = inProgressLabel; }
    }
}
