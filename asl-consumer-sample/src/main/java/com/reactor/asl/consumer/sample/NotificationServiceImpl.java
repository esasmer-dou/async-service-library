package com.reactor.asl.consumer.sample;

import org.springframework.stereotype.Service;

@Service
public class NotificationServiceImpl extends AbstractSyntheticServiceSupport implements NotificationService {
    public NotificationServiceImpl() {
        super("notification");
    }

    @Override
    public String composeNotification(String notificationId) {
        return register(notificationId, "COMPOSED");
    }

    @Override
    public String dispatchNotification(String notificationId) {
        return transition(notificationId, "DISPATCHED");
    }

    @Override
    public void publishFanout(String notificationId) {
        publishPrimary(notificationId);
    }

    @Override
    public void publishDigest(String notificationId) {
        publishSecondary(notificationId);
    }

    @Override
    public String health() {
        return healthStatus();
    }
}
