package com.reactor.asl.consumer.sample;

import org.springframework.stereotype.Service;

@Service
public class ShipmentServiceImpl extends AbstractSyntheticServiceSupport implements ShipmentService {
    public ShipmentServiceImpl() {
        super("shipment");
    }

    @Override
    public String createShipment(String shipmentId) {
        return register(shipmentId, "CREATED");
    }

    @Override
    public String confirmShipment(String shipmentId) {
        return transition(shipmentId, "CONFIRMED");
    }

    @Override
    public void publishTracking(String shipmentId) {
        publishPrimary(shipmentId);
    }

    @Override
    public String health() {
        return healthStatus();
    }
}
