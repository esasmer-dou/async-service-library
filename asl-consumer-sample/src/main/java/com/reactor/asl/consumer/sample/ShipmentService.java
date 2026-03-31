package com.reactor.asl.consumer.sample;

import com.reactor.asl.annotations.Excluded;
import com.reactor.asl.annotations.GovernedMethod;
import com.reactor.asl.annotations.GovernedService;

@GovernedService(id = "shipment.service")
public interface ShipmentService {
    @GovernedMethod(initialMaxConcurrency = 5, unavailableMessage = "shipment creation lane closed")
    String createShipment(String shipmentId);

    @GovernedMethod(initialMaxConcurrency = 3, unavailableMessage = "shipment confirm lane closed")
    String confirmShipment(String shipmentId);

    @GovernedMethod(asyncCapable = true, initialMaxConcurrency = 2, initialConsumerThreads = 1)
    void publishTracking(String shipmentId);

    @Excluded
    String health();
}
