package com.reactor.asl.consumer.sample;

import com.reactor.asl.annotations.Excluded;
import com.reactor.asl.annotations.GovernedMethod;
import com.reactor.asl.annotations.GovernedService;

@GovernedService(id = "notification.service")
public interface NotificationService {
    @GovernedMethod(initialMaxConcurrency = 7, unavailableMessage = "notification compose lane closed")
    String composeNotification(String notificationId);

    @GovernedMethod(initialMaxConcurrency = 5, unavailableMessage = "notification dispatch lane closed")
    String dispatchNotification(String notificationId);

    @GovernedMethod(asyncCapable = true, initialMaxConcurrency = 2, initialConsumerThreads = 0)
    void publishFanout(String notificationId);

    @GovernedMethod(asyncCapable = true, initialMaxConcurrency = 1, initialConsumerThreads = 2)
    void publishDigest(String notificationId);

    @Excluded
    String health();
}
