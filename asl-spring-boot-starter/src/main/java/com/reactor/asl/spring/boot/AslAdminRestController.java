package com.reactor.asl.spring.boot;

import com.reactor.asl.core.ExecutionMode;
import com.reactor.asl.core.MethodBufferSnapshot;
import com.reactor.asl.core.MethodRuntimeSnapshot;
import com.reactor.asl.core.ServiceRuntimeSnapshot;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("${asl.admin.api-path:/asl/api}")
public class AslAdminRestController {
    private final AslAdminFacade facade;

    public AslAdminRestController(AslAdminFacade facade) {
        this.facade = facade;
    }

    @GetMapping("/services")
    public List<ServiceRuntimeSnapshot> services() {
        return facade.services();
    }

    @GetMapping("/summary")
    public AslAdminFacade.AdminDashboardSummary summary() {
        return facade.summary();
    }

    @GetMapping("/config")
    public AslAdminRuntimeConfiguration.ConfigSnapshot config() {
        return facade.config();
    }

    @PostMapping("/config/ui")
    public AslAdminRuntimeConfiguration.ConfigSnapshot updateUi(
            @RequestBody AslAdminRuntimeConfiguration.UiPatch patch
    ) {
        facade.updateUi(patch);
        return facade.config();
    }

    @PostMapping("/config/runtime")
    public AslAdminRuntimeConfiguration.ConfigSnapshot updateRuntime(
            @RequestBody RuntimeConfigRequest request
    ) {
        if (request.bufferPreviewLimit() != null) {
            facade.setBufferPreviewLimit(request.bufferPreviewLimit());
        }
        if (request.attentionLimit() != null) {
            facade.setAttentionLimit(request.attentionLimit());
        }
        if (request.mediumUtilizationPercent() != null || request.highUtilizationPercent() != null) {
            AslAdminRuntimeConfiguration.ConfigSnapshot current = facade.config();
            facade.setUtilizationThresholds(
                    request.mediumUtilizationPercent() == null ? current.mediumUtilizationPercent() : request.mediumUtilizationPercent(),
                    request.highUtilizationPercent() == null ? current.highUtilizationPercent() : request.highUtilizationPercent()
            );
        }
        if (request.hasRefreshPatch()) {
            AslAdminRuntimeConfiguration.RefreshPatch patch = new AslAdminRuntimeConfiguration.RefreshPatch();
            patch.setLiveRefreshEnabled(request.liveRefreshEnabled());
            patch.setLiveBufferEnabled(request.liveBufferEnabled());
            patch.setDefaultIntervalMs(request.defaultIntervalMs());
            patch.setIntervalOptionsMs(request.intervalOptionsMs());
            patch.setChangeFlashMs(request.changeFlashMs());
            patch.setSuccessMessageAutoHideMs(request.successMessageAutoHideMs());
            patch.setErrorMessageAutoHideMs(request.errorMessageAutoHideMs());
            facade.updateRefresh(patch);
        }
        return facade.config();
    }

    @GetMapping("/services/{serviceId}")
    public ServiceRuntimeSnapshot service(@PathVariable("serviceId") String serviceId) {
        return facade.service(serviceId);
    }

    @PostMapping("/services/{serviceId}/methods/{methodId}/enable")
    public MethodRuntimeSnapshot enable(
            @PathVariable("serviceId") String serviceId,
            @PathVariable("methodId") String methodId
    ) {
        return facade.enable(serviceId, methodId);
    }

    @PostMapping("/services/{serviceId}/methods/{methodId}/disable")
    public MethodRuntimeSnapshot disable(
            @PathVariable("serviceId") String serviceId,
            @PathVariable("methodId") String methodId,
            @RequestParam(name = "message", required = false) String message
    ) {
        return facade.disable(serviceId, methodId, message);
    }

    @PostMapping("/services/{serviceId}/methods/{methodId}/concurrency")
    public MethodRuntimeSnapshot setConcurrency(
            @PathVariable("serviceId") String serviceId,
            @PathVariable("methodId") String methodId,
            @RequestBody ConcurrencyRequest request
    ) {
        return facade.setMaxConcurrency(serviceId, methodId, request.maxConcurrency());
    }

    @PostMapping("/services/{serviceId}/methods/{methodId}/mode")
    public MethodRuntimeSnapshot setMode(
            @PathVariable("serviceId") String serviceId,
            @PathVariable("methodId") String methodId,
            @RequestBody ModeRequest request
    ) {
        return facade.switchMode(serviceId, methodId, request.executionMode());
    }

    @PostMapping("/services/{serviceId}/methods/{methodId}/consumer-threads")
    public MethodRuntimeSnapshot setConsumerThreads(
            @PathVariable("serviceId") String serviceId,
            @PathVariable("methodId") String methodId,
            @RequestBody ConsumerThreadsRequest request
    ) {
        return facade.setConsumerThreads(serviceId, methodId, request.consumerThreads());
    }

    @PostMapping("/services/{serviceId}/methods/{methodId}/configuration")
    public MethodRuntimeSnapshot configureMethod(
            @PathVariable("serviceId") String serviceId,
            @PathVariable("methodId") String methodId,
            @RequestBody AslAdminProperties.MethodConfiguration request
    ) {
        return facade.applyMethodConfiguration(serviceId, methodId, request);
    }

    @GetMapping("/services/{serviceId}/methods/{methodId}/buffer")
    public MethodBufferSnapshot buffer(
            @PathVariable("serviceId") String serviceId,
            @PathVariable("methodId") String methodId
    ) {
        return facade.buffer(serviceId, methodId);
    }

    @DeleteMapping("/services/{serviceId}/methods/{methodId}/buffer")
    public Map<String, Integer> clearBuffer(
            @PathVariable("serviceId") String serviceId,
            @PathVariable("methodId") String methodId
    ) {
        return Map.of("cleared", facade.clearBuffer(serviceId, methodId));
    }

    @DeleteMapping("/services/{serviceId}/methods/{methodId}/buffer/{entryId}")
    public ResponseEntity<Void> deleteBufferEntry(
            @PathVariable("serviceId") String serviceId,
            @PathVariable("methodId") String methodId,
            @PathVariable("entryId") String entryId
    ) {
        boolean deleted = facade.deleteBufferEntry(serviceId, methodId, entryId);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/services/{serviceId}/methods/{methodId}/buffer/{entryId}/replay")
    public ResponseEntity<Void> replayBufferEntry(
            @PathVariable("serviceId") String serviceId,
            @PathVariable("methodId") String methodId,
            @PathVariable("entryId") String entryId
    ) {
        boolean replayed = facade.replayBufferEntry(serviceId, methodId, entryId);
        return replayed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    public record ConcurrencyRequest(int maxConcurrency) {
    }

    public record ModeRequest(ExecutionMode executionMode) {
    }

    public record ConsumerThreadsRequest(int consumerThreads) {
    }

    public record RuntimeConfigRequest(
            Integer bufferPreviewLimit,
            Integer attentionLimit,
            Integer mediumUtilizationPercent,
            Integer highUtilizationPercent,
            Boolean liveRefreshEnabled,
            Boolean liveBufferEnabled,
            Integer defaultIntervalMs,
            List<Integer> intervalOptionsMs,
            Integer changeFlashMs,
            Integer successMessageAutoHideMs,
            Integer errorMessageAutoHideMs
    ) {
        boolean hasRefreshPatch() {
            return liveRefreshEnabled != null
                    || liveBufferEnabled != null
                    || defaultIntervalMs != null
                    || intervalOptionsMs != null
                    || changeFlashMs != null
                    || successMessageAutoHideMs != null
                    || errorMessageAutoHideMs != null;
        }
    }
}
