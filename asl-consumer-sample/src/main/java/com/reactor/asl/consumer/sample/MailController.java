package com.reactor.asl.consumer.sample;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class MailController {
    private final MailService mailService;
    private final MailServiceImpl delegate;

    public MailController(MailService mailService, MailServiceImpl delegate) {
        this.mailService = mailService;
        this.delegate = delegate;
    }

    @PostMapping("/mails")
    @ResponseStatus(HttpStatus.CREATED)
    public MailMessageView createDraft(@RequestBody CreateMailRequest request) {
        return mailService.createDraft(request);
    }

    @GetMapping("/mails")
    public List<MailMessageView> list() {
        return mailService.list();
    }

    @GetMapping("/mails/{mailId}")
    public MailMessageView get(@PathVariable("mailId") String mailId) {
        return mailService.get(mailId);
    }

    @PostMapping("/mails/{mailId}/send")
    public MailMessageView send(@PathVariable("mailId") String mailId) {
        return mailService.send(mailId);
    }

    @PostMapping("/mails/{mailId}/publish-audit")
    public Map<String, String> publishAudit(@PathVariable("mailId") String mailId) {
        mailService.publishAudit(mailId);
        return Map.of("status", "accepted", "mailId", mailId);
    }

    @GetMapping("/mails/audit-events")
    public List<String> auditEvents() {
        return delegate.auditEvents();
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", mailService.health());
    }
}
