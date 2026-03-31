package com.reactor.asl.consumer.sample;

import java.time.Instant;

final class MailRecord {
    private final String id;
    private final String recipient;
    private final String subject;
    private final String body;
    private final Instant createdAt;
    private volatile MailMessageStatus status;
    private volatile Instant sentAt;

    MailRecord(String id, String recipient, String subject, String body, Instant createdAt) {
        this.id = id;
        this.recipient = recipient;
        this.subject = subject;
        this.body = body;
        this.createdAt = createdAt;
        this.status = MailMessageStatus.DRAFT;
    }

    String id() {
        return id;
    }

    String recipient() {
        return recipient;
    }

    String subject() {
        return subject;
    }

    String body() {
        return body;
    }

    Instant createdAt() {
        return createdAt;
    }

    MailMessageStatus status() {
        return status;
    }

    Instant sentAt() {
        return sentAt;
    }

    void markSent(Instant sentAt) {
        this.status = MailMessageStatus.SENT;
        this.sentAt = sentAt;
    }
}
