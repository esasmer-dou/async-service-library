package com.reactor.asl.spring.boot;

import com.reactor.asl.core.ExecutionMode;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("${asl.admin.path:/asl}")
public class AslAdminPageController {
    private final AslAdminFacade facade;

    public AslAdminPageController(AslAdminFacade facade) {
        this.facade = facade;
    }

    @GetMapping
    public String dashboard(Model model) {
        model.addAttribute("services", facade.dashboard());
        model.addAttribute("summary", facade.summary());
        model.addAttribute("pagePath", facade.pagePath());
        model.addAttribute("apiPath", facade.apiPath());
        model.addAttribute("config", facade.config());
        model.addAttribute("ui", facade.ui());
        return "asl/dashboard";
    }

    @PostMapping("/services/{serviceId}/methods/{methodId}/enable")
    public String enable(
            @PathVariable("serviceId") String serviceId,
            @PathVariable("methodId") String methodId
    ) {
        facade.enable(serviceId, methodId);
        return "redirect:" + facade.pagePath();
    }

    @PostMapping("/services/{serviceId}/methods/{methodId}/disable")
    public String disable(
            @PathVariable("serviceId") String serviceId,
            @PathVariable("methodId") String methodId,
            @RequestParam(name = "message", required = false) String message
    ) {
        facade.disable(serviceId, methodId, message);
        return "redirect:" + facade.pagePath();
    }

    @PostMapping("/services/{serviceId}/methods/{methodId}/concurrency")
    public String concurrency(
            @PathVariable("serviceId") String serviceId,
            @PathVariable("methodId") String methodId,
            @RequestParam(name = "maxConcurrency") int maxConcurrency
    ) {
        facade.setMaxConcurrency(serviceId, methodId, maxConcurrency);
        return "redirect:" + facade.pagePath();
    }

    @PostMapping("/services/{serviceId}/methods/{methodId}/mode")
    public String mode(
            @PathVariable("serviceId") String serviceId,
            @PathVariable("methodId") String methodId,
            @RequestParam(name = "executionMode") ExecutionMode executionMode
    ) {
        facade.switchMode(serviceId, methodId, executionMode);
        return "redirect:" + facade.pagePath();
    }

    @PostMapping("/services/{serviceId}/methods/{methodId}/consumer-threads")
    public String consumerThreads(
            @PathVariable("serviceId") String serviceId,
            @PathVariable("methodId") String methodId,
            @RequestParam(name = "consumerThreads") int consumerThreads
    ) {
        facade.setConsumerThreads(serviceId, methodId, consumerThreads);
        return "redirect:" + facade.pagePath();
    }

    @PostMapping("/services/{serviceId}/methods/{methodId}/buffer/clear")
    public String clearBuffer(
            @PathVariable("serviceId") String serviceId,
            @PathVariable("methodId") String methodId
    ) {
        facade.clearBuffer(serviceId, methodId);
        return "redirect:" + facade.pagePath();
    }

    @PostMapping("/services/{serviceId}/methods/{methodId}/buffer/{entryId}/replay")
    public String replayBuffer(
            @PathVariable("serviceId") String serviceId,
            @PathVariable("methodId") String methodId,
            @PathVariable("entryId") String entryId
    ) {
        facade.replayBufferEntry(serviceId, methodId, entryId);
        return "redirect:" + facade.pagePath();
    }

    @PostMapping("/services/{serviceId}/methods/{methodId}/buffer/{entryId}/delete")
    public String deleteBuffer(
            @PathVariable("serviceId") String serviceId,
            @PathVariable("methodId") String methodId,
            @PathVariable("entryId") String entryId
    ) {
        facade.deleteBufferEntry(serviceId, methodId, entryId);
        return "redirect:" + facade.pagePath();
    }
}
