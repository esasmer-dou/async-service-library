package com.reactor.asl.consumer.sample;

import com.reactor.asl.annotations.Excluded;
import com.reactor.asl.annotations.GovernedMethod;
import com.reactor.asl.annotations.GovernedService;

import java.util.List;

@GovernedService(id = "mail.service")
public interface MailService {
    @GovernedMethod(initialMaxConcurrency = 8, unavailableMessage = "draft creation lane closed")
    MailMessageView createDraft(CreateMailRequest request);

    @Excluded
    List<MailMessageView> list();

    @Excluded
    MailMessageView get(String mailId);

    @GovernedMethod(initialMaxConcurrency = 4, unavailableMessage = "mail send lane closed")
    MailMessageView send(String mailId);

    @GovernedMethod(asyncCapable = true, initialMaxConcurrency = 2, initialConsumerThreads = 0)
    void publishAudit(String mailId);

    @Excluded
    String health();
}
