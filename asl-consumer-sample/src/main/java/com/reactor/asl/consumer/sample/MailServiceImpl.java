package com.reactor.asl.consumer.sample;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class MailServiceImpl implements MailService {
    private final ConcurrentMap<String, MailRecord> mails = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<String> auditEvents = new CopyOnWriteArrayList<>();
    private final AsyncScenarioControl auditScenario = new AsyncScenarioControl("mail.publishAudit");

    @Override
    public MailMessageView createDraft(CreateMailRequest request) {
        validate(request);
        String id = UUID.randomUUID().toString();
        MailRecord record = new MailRecord(id, request.recipient(), request.subject(), request.body(), Instant.now());
        mails.put(id, record);
        return toView(record);
    }

    @Override
    public List<MailMessageView> list() {
        return mails.values().stream()
                .sorted(Comparator.comparing(MailRecord::createdAt))
                .map(this::toView)
                .toList();
    }

    @Override
    public MailMessageView get(String mailId) {
        return toView(requireMail(mailId));
    }

    @Override
    public MailMessageView send(String mailId) {
        MailRecord record = requireMail(mailId);
        record.markSent(Instant.now());
        return toView(record);
    }

    @Override
    public void publishAudit(String mailId) {
        MailRecord record = requireMail(mailId);
        auditScenario.beforeInvocation("Configured async failure for mail audit: " + mailId);
        auditEvents.add("AUDIT:" + record.id() + ":" + record.status().name());
    }

    @Override
    public String health() {
        return "UP";
    }

    public List<String> auditEvents() {
        return List.copyOf(auditEvents);
    }

    public void reset() {
        mails.clear();
        auditEvents.clear();
        auditScenario.reset();
    }

    public AsyncScenarioSnapshot configureAuditScenario(AsyncScenarioRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        auditScenario.configure(request.failuresRemaining(), request.processingDelayMillis());
        return auditScenario.snapshot();
    }

    public AsyncScenarioSnapshot auditScenario() {
        return auditScenario.snapshot();
    }

    private MailRecord requireMail(String mailId) {
        MailRecord record = mails.get(mailId);
        if (record == null) {
            throw new MailNotFoundException(mailId);
        }
        return record;
    }

    private MailMessageView toView(MailRecord record) {
        return new MailMessageView(
                record.id(),
                record.recipient(),
                record.subject(),
                record.body(),
                record.status(),
                record.createdAt(),
                record.sentAt()
        );
    }

    private static void validate(CreateMailRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (request.recipient() == null || request.recipient().isBlank()) {
            throw new IllegalArgumentException("recipient must not be blank");
        }
        if (request.subject() == null || request.subject().isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        if (request.body() == null || request.body().isBlank()) {
            throw new IllegalArgumentException("body must not be blank");
        }
    }
}
